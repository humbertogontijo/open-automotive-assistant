package cc.opencar.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cc.opencar.assistant.feature.shortcuts.QuickEntryMenu
import cc.opencar.assistant.feature.web.SetupActionBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
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
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            // EX5 and similar HUs report density 160 on a ~2560px canvas — CSS px ≈ physical px.
            // Keep layout zoom off; CSS rem/vw scale handles automotive sizing.
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = true
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(0xFF0B0C0E.toInt())
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFF0B0C0E.toInt())
                addView(
                    webView,
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
            webView.loadUrl(oaaUrl(intent))
        }
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
        if (::webView.isInitialized) {
            navigateToSection(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun oaaUrl(intent: Intent?): String {
        val section = intent?.getStringExtra(QuickEntryMenu.EXTRA_SECTION)?.trim().orEmpty()
        return if (section.isNotEmpty() && section != "home") {
            "http://127.0.0.1:8787/" + android.net.Uri.encode(section)
        } else {
            "http://127.0.0.1:8787/"
        }
    }

    private fun navigateToSection(intent: Intent?) {
        val section = intent?.getStringExtra(QuickEntryMenu.EXTRA_SECTION)?.trim().orEmpty()
        // Re-entry without a section must not reload "/" — that wiped in-memory UI place.
        if (section.isEmpty()) return
        // Prefer in-page navigation if UI already loaded.
        webView.evaluateJavascript(
            "(function(){try{if(window.__oaaGoPage){window.__oaaGoPage(" +
                org.json.JSONObject.quote(section) +
                ");return true;}return false;}catch(e){return false;}})()",
        ) { result ->
            if (result != "true") {
                webView.loadUrl(oaaUrl(intent))
            }
        }
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
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
