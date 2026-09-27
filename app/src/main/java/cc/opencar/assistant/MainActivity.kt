package cc.opencar.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cc.opencar.assistant.feature.shortcuts.QuickEntryMenu
import cc.opencar.assistant.feature.web.SetupActionBus
import cc.opencar.assistant.protocol.OaaHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class MainActivity : ComponentActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private var canGoBack = false

    /** Content-script port of the built-in `oaa-ext` extension; null until the page connects. */
    private var bridgePort: WebExtension.Port? = null
    private var nextGotoId = 0
    private val pendingGoto = mutableMapOf<Int, Intent?>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureRuntimePermissions()
        lifecycleScope.launch {
            SetupActionBus.runtimePermissionRequests.collect {
                ensureRuntimePermissions()
            }
        }
        lifecycleScope.launch {
            SetupActionBus.openAndroidSettings.collect {
                openAndroidSettings()
            }
        }
        lifecycleScope.launch {
            SetupActionBus.overlayPermissionRequests.collect {
                openOverlayPermission()
            }
        }
        val runtime = OaaApp.instance.geckoRuntime
        // EX5 and similar HUs report density 160 on a ~2560px canvas — CSS px ≈ physical px.
        // Mobile viewport keeps layout zoom off; CSS rem/vw scale handles automotive sizing.
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .usePrivateMode(false)
                .build(),
        )
        session.permissionDelegate = LocalOriginPermissions
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }
        session.open(runtime)
        installBridge()
        geckoView = GeckoView(this).apply {
            setBackgroundColor(BACKGROUND)
            setSession(this@MainActivity.session)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    geckoView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(30_000) {
                OaaApp.instance.runtime.ready.first { it }
                true
            } == true
            if (!ready) {
                delay(500)
            }
            when (intent?.action) {
                QuickEntryMenu.ACTION_EXIT -> {
                    finishAndRemoveTask()
                    return@launch
                }
                QuickEntryMenu.ACTION_BACKGROUND -> {
                    moveTaskToBack(true)
                    return@launch
                }
            }
            loadOaa(oaaUrl(intent))
        }
    }

    override fun onDestroy() {
        bridgePort = null
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.action) {
            QuickEntryMenu.ACTION_EXIT -> {
                finishAndRemoveTask()
                return
            }
            QuickEntryMenu.ACTION_BACKGROUND -> {
                moveTaskToBack(true)
                return
            }
            else -> Unit
        }
        if (::session.isInitialized) {
            navigateToSection(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::session.isInitialized && canGoBack) session.goBack()
        else super.onBackPressed()
    }

    private fun oaaUrl(intent: Intent?): String {
        val section = intent?.getStringExtra(QuickEntryMenu.EXTRA_SECTION)?.trim().orEmpty()
        return if (section.isNotEmpty() && section != "home") {
            ORIGIN + "/" + android.net.Uri.encode(section)
        } else {
            "$ORIGIN/"
        }
    }

    private fun installBridge() {
        val runtime = OaaApp.instance.geckoRuntime
        runtime.webExtensionController
            .ensureBuiltIn(BRIDGE_URI, BRIDGE_ID)
            .accept(
                { ext ->
                    if (ext == null || !::session.isInitialized) return@accept
                    session.webExtensionController.setMessageDelegate(ext, bridgeDelegate, BRIDGE_APP)
                },
                { t -> Log.w(TAG, "oaa-ext install failed", t) },
            )
    }

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            bridgePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val msg = message as? JSONObject ?: return
                    if (msg.optBoolean("reauth", false)) {
                        reauth(msg.optString("path"))
                        return
                    }
                    val id = msg.optInt("id", -1)
                    val intent = pendingGoto.remove(id) ?: return
                    if (!msg.optBoolean("handled", false)) loadOaa(oaaUrl(intent))
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (bridgePort === port) bridgePort = null
                }
            })
        }
    }

    private fun navigateToSection(intent: Intent?) {
        val section = intent?.getStringExtra(QuickEntryMenu.EXTRA_SECTION)?.trim().orEmpty()
        // Re-entry without a section must not reload "/" — that wiped in-memory UI place.
        if (section.isEmpty()) return
        val port = bridgePort
        if (port == null) {
            loadOaa(oaaUrl(intent))
            return
        }
        val id = ++nextGotoId
        pendingGoto[id] = intent
        port.postMessage(JSONObject().put("id", id).put("goto", section))
        lifecycleScope.launch {
            delay(GOTO_ACK_TIMEOUT_MS)
            if (pendingGoto.remove(id) != null) loadOaa(oaaUrl(intent))
        }
    }

    /**
     * Top-level loads carry the per-launch local key; the server answers with an HttpOnly
     * head unit session cookie, so page scripts never see the key.
     */
    private fun loadOaa(url: String) {
        val key = OaaApp.instance.runtime.auth.localKey
        session.load(
            GeckoSession.Loader()
                .uri(url)
                .additionalHeaders(mapOf(OaaHeaders.LOCAL_KEY to key))
                .headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE),
        )
    }

    private var lastReauthMs = 0L

    private fun reauth(path: String) {
        val now = System.currentTimeMillis()
        if (now - lastReauthMs < REAUTH_MIN_INTERVAL_MS) return
        lastReauthMs = now
        val safe = path.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
        loadOaa(ORIGIN + safe)
    }

    private fun openAndroidSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
            Intent("android.settings.SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            },
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "settings intent failed: ${intent.action}", t)
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.e(TAG, "could not open Android settings", t)
        }
    }

    private fun openOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay permission intent failed", t)
            openAndroidSettings()
        }
    }

    private fun ensureRuntimePermissions() {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Autoplay and media grants for the in-process UI only; everything else is denied. */
    private object LocalOriginPermissions : PermissionDelegate {
        private fun isLocal(uri: String?): Boolean = uri != null && uri.startsWith(ORIGIN)

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: ContentPermission,
        ): GeckoResult<Int> {
            val allow = isLocal(perm.uri) && perm.permission in AUTOPLAY
            return GeckoResult.fromValue(
                if (allow) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            if (isLocal(uri)) callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            else callback.reject()
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) {
            callback.grant()
        }

        private val AUTOPLAY = setOf(
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
        )
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val ORIGIN = "http://127.0.0.1:8787"
        private const val BACKGROUND = 0xFF0B0C0E.toInt()
        private const val BRIDGE_URI = "resource://android/assets/oaa-ext/"
        private const val BRIDGE_ID = "bridge@opencar.cc"
        private const val BRIDGE_APP = "oaa"
        private const val GOTO_ACK_TIMEOUT_MS = 1_500L
        private const val REAUTH_MIN_INTERVAL_MS = 5_000L
    }
}
