package cc.opencar.assistant.feature.web

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.install.ApkInstaller
import cc.opencar.assistant.feature.web.webrtc.CarWebRtc
import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaPorts
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaWebRtc
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Outbound hub link for this car: pair with a code, then keep one WebSocket to
 * the hub's node face. Over it the hub proxies HTTP (`rpc`), relays WebRTC
 * signaling, streams logs, and offers app updates; the car forwards its
 * `/api/events` messages.
 */
class HubClient(
    private val context: Context,
    private val session: VehicleSession,
    private val prefs: SharedPreferences,
    private val debug: ContributorDebugState,
    installer: ApkInstaller,
    dvr: DvrController?,
    private val localPort: Int = OaaPorts.HUMAN_DEFAULT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val wsClient = http.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val joinMutex = Mutex()
    private val wsRef = AtomicReference<WebSocket?>(null)
    @Volatile private var online = false
    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var logJob: Job? = null
    private var appInfo: JSONObject? = null

    private val dvrRef = dvr
    private val webrtc = dvr?.let { CarWebRtc(context, it, ::send) }
    private val ota = OtaUpdater(context, installer, http, scope) { send(OaaFrames.frame(OaaFrames.OTA_STATUS, it)) }

    val nodeId: String? get() = prefs.getString(PREF_NODE_ID, null)

    fun status(): Map<String, Any?> = mapOf(
        "enabled" to prefs.getBoolean(PREF_ENABLED, false),
        "hubUrl" to prefs.getString(PREF_HUB_URL, "")?.trim().orEmpty(),
        "nodeUrl" to prefs.getString(PREF_NODE_URL, null),
        "nodeId" to nodeId,
        "paired" to !prefs.getString(PREF_TOKEN, null).isNullOrBlank(),
        "online" to online,
        "lastError" to prefs.getString(PREF_LAST_ERROR, null),
    )

    fun start() {
        if (loopJob != null) return
        ensureNodeId()
        loopJob = scope.launch {
            while (isActive) {
                try {
                    maintainSession()
                } catch (e: Exception) {
                    setError(e.message ?: e.javaClass.simpleName)
                    Log.w(TAG, "hub session error", e)
                }
                delay(5_000)
            }
        }
    }

    /** Tear down for good; the instance is unusable afterwards (use [leave] to unpair). */
    fun stop() {
        webrtc?.shutdown()
        disconnect()
        scope.cancel()
    }

    /**
     * Pair with the hub's node face. [hubUrl] is the node-face URL, either a plain origin
     * (`http://hub:8788`, pair at `/api/nodes/pair`) or a bridge prefix such as
     * `https://x.ui.nabu.casa/api/oaa_node` (pair at `<prefix>/pair`).
     */
    suspend fun join(hubUrl: String, code: String, displayName: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val base = hubUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrEmpty()) {
            return@withContext mapOf("ok" to false, "error" to "invalid hub URL") + status()
        }
        val prefix = uri!!.rawPath.orEmpty().trimEnd('/')
        val origin = base.removeSuffix(prefix)
        val pairUrl = if (prefix.isEmpty()) origin + OaaPaths.NODES_PAIR else "$origin$prefix/pair"
        val defaultSessionPath = if (prefix.isEmpty()) OaaPaths.NODES_SESSION else "$prefix/session"
        val id = nodeId ?: return@withContext mapOf("ok" to false, "error" to "no nodeId")
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(PREF_NAME, null)
            ?: session.integrationId
        val body = JSONObject()
            .put("code", code.trim())
            .put("nodeId", id)
            .put("name", name)
            .put("integration", session.integrationId)
            .toString()
        val req = Request.Builder()
            .url(pairUrl)
            .post(body.toRequestBody(JSON))
            .build()
        joinMutex.withLock {
            val call = runCatching { http.newCall(req).execute() }
            val failure = call.exceptionOrNull()
            if (failure != null) {
                val err = "cannot reach hub: ${failure.message ?: failure.javaClass.simpleName}"
                setError(err)
                return@withLock mapOf("ok" to false, "error" to err) + status()
            }
            call.getOrThrow().use { resp ->
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                if (!resp.isSuccessful || json == null || !json.optBoolean("ok", false)) {
                    val err = json?.optString("error")?.takeIf { it.isNotBlank() } ?: "pair failed (${resp.code})"
                    setError(err)
                    return@withLock mapOf("ok" to false, "error" to err) + status()
                }
                val publicNodeUrl = json.optString("publicNodeUrl").trim().takeIf { it.isNotEmpty() }?.trimEnd('/')
                prefs.edit()
                    .putBoolean(PREF_ENABLED, true)
                    .putString(PREF_HUB_URL, base)
                    .putString(PREF_TOKEN, json.getString("token"))
                    .putString(PREF_NODE_ID, json.optString("nodeId", id))
                    .putString(PREF_NAME, name)
                    .putString(
                        PREF_SESSION_PATH,
                        json.optString("sessionPath").trim().takeIf { publicNodeUrl != null && it.isNotEmpty() } ?: defaultSessionPath,
                    )
                    .putString(PREF_NODE_URL, publicNodeUrl ?: origin)
                    .remove(PREF_LAST_ERROR)
                    .apply()
                disconnect()
                mapOf("ok" to true) + status()
            }
        }
    }

    fun leave(): Map<String, Any?> {
        prefs.edit()
            .putBoolean(PREF_ENABLED, false)
            .remove(PREF_TOKEN)
            .remove(PREF_HUB_URL)
            .remove(PREF_NODE_URL)
            .remove(PREF_SESSION_PATH)
            .remove(PREF_LAST_ERROR)
            .apply()
        disconnect()
        return mapOf("ok" to true) + status()
    }

    private fun ensureNodeId() {
        if (!nodeId.isNullOrBlank()) return
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        prefs.edit().putString(PREF_NODE_ID, "node-" + androidId.takeLast(12)).apply()
    }

    private suspend fun maintainSession() {
        if (!prefs.getBoolean(PREF_ENABLED, false)) {
            disconnect()
            return
        }
        val token = prefs.getString(PREF_TOKEN, null) ?: return
        val nodeUrl = nodeBaseUrl() ?: return
        if (wsRef.get() != null) return
        val app = appInfo ?: buildAppInfo().also { appInfo = it }

        val wsUrl = nodeUrl.replace(Regex("^http"), "ws") +
            (prefs.getString(PREF_SESSION_PATH, null)?.takeIf { it.isNotBlank() } ?: OaaPaths.NODES_SESSION) +
            "?token=" + URLEncoder.encode(token, Charsets.UTF_8.name())
        val ws = wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), Listener(token, nodeUrl, app))
        wsRef.set(ws)
        // Hold this loop iteration while the socket lives; onClosed/onFailure clear the ref.
        while (wsRef.get() === ws) delay(2_000)
    }

    private inner class Listener(
        private val token: String,
        private val nodeUrl: String,
        private val app: JSONObject,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            online = true
            setError(null)
            LogRingBuffer.append("Hub session connected")
            val hello = OaaFrames.versioned()
                .put("nodeId", nodeId.orEmpty())
                .put("integration", session.integrationId)
                .put("name", prefs.getString(PREF_NAME, "").orEmpty())
                .put("version", OaaBuild.VERSION)
                .put("app", app)
            webSocket.send(OaaFrames.frame(OaaFrames.HELLO, hello))
            eventsJob?.cancel()
            eventsJob = scope.launch {
                uiEvents(session, dvrRef, telemetrySampleMs = 1_000).collect { msg ->
                    webSocket.send("""{"type":"${OaaFrames.EVENT}","payload":${gson.toJson(msg)}}""")
                }
            }
        }

        /** OkHttp delivers messages in order on one thread; signaling stays inline so ICE never overtakes the offer. */
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = OaaFrames.parse(text) ?: return
            val payload = json.optJSONObject("payload")
            when (val type = json.optString("type")) {
                OaaFrames.RPC -> if (payload != null) {
                    scope.launch {
                        val req = OaaRpc.decodeRequest(payload)
                        val res = try {
                            localRpc(req)
                        } catch (e: Exception) {
                            OaaRpc.error(502, e.message ?: "rpc error")
                        }
                        webSocket.send(OaaRpc.encodeResponse(req.id, res))
                    }
                }
                OaaFrames.PING -> webSocket.send(OaaFrames.frame(OaaFrames.PONG))
                OaaFrames.OTA_OFFER -> if (payload != null) ota.offer(payload, nodeUrl, token)
                OaaFrames.LOG_SUBSCRIBE -> startLogStream(webSocket, payload?.optString("token"))
                OaaFrames.LOG_UNSUBSCRIBE -> stopLogStream()
                OaaWebRtc.OFFER, OaaWebRtc.ICE, OaaWebRtc.HANGUP -> {
                    val rtc = webrtc
                    if (rtc != null) {
                        rtc.onSignal(json)
                    } else if (type == OaaWebRtc.OFFER) {
                        webSocket.send(OaaFrames.hangup(payload?.optString("sessionId"), OaaWebRtc.REASON_UNSUPPORTED))
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            setError(t.message ?: "ws failure")
            Log.w(TAG, "hub ws failure", t)
            dropped(webSocket)
        }

        private fun dropped(webSocket: WebSocket) {
            if (wsRef.compareAndSet(webSocket, null)) {
                online = false
                eventsJob?.cancel()
                stopLogStream()
            }
        }
    }

    private fun startLogStream(webSocket: WebSocket, token: String?) {
        stopLogStream()
        if (!debug.checkToken(token)) {
            webSocket.send(OaaFrames.frame(OaaFrames.LOG, JSONObject().put("line", "log stream denied: contributor token required")))
            return
        }
        logJob = scope.launch {
            fun forward(line: String) = webSocket.send(OaaFrames.frame(OaaFrames.LOG, JSONObject().put("line", line)))
            LogRingBuffer.snapshot().takeLast(LOG_BACKLOG).forEach(::forward)
            LogRingBuffer.live.collect(::forward)
        }
    }

    private fun stopLogStream() {
        logJob?.cancel()
        logJob = null
    }

    /** Replay a proxied request against the local server; bodies stay binary-safe and capped. */
    private fun localRpc(req: OaaRpc.Request): OaaRpc.Response {
        val method = req.method.uppercase()
        val body = when {
            method in BODY_METHODS -> (req.body ?: ByteArray(0)).toRequestBody(req.contentType?.toMediaTypeOrNull())
            method == "DELETE" && req.body != null -> req.body!!.toRequestBody(req.contentType?.toMediaTypeOrNull())
            else -> null
        }
        val target = if (req.target.startsWith("/")) req.target else "/${req.target}"
        val request = Request.Builder()
            .url("http://127.0.0.1:$localPort$target")
            .header(OaaHeaders.VIA_HUB, "1")
            .method(method, body)
            .build()
        return http.newCall(request).execute().use { resp ->
            val bytes = ByteArrayOutputStream()
            resp.body?.byteStream()?.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                    if (bytes.size() > OaaRpc.MAX_BODY_BYTES) {
                        return OaaRpc.error(413, "response over ${OaaRpc.MAX_BODY_BYTES} bytes; use the car's LAN URL")
                    }
                }
            }
            OaaRpc.Response(
                status = resp.code,
                contentType = resp.header("Content-Type"),
                contentDisposition = resp.header("Content-Disposition"),
                body = bytes.toByteArray(),
            )
        }
    }

    private fun send(text: String): Boolean = wsRef.get()?.send(text) == true

    private fun disconnect() {
        wsRef.getAndSet(null)?.close(1000, "bye")
        online = false
        eventsJob?.cancel()
        stopLogStream()
    }

    private fun setError(msg: String?) {
        val ed = prefs.edit()
        if (msg == null) ed.remove(PREF_LAST_ERROR) else ed.putString(PREF_LAST_ERROR, msg.take(200))
        ed.apply()
    }

    private fun nodeBaseUrl(): String? =
        prefs.getString(PREF_NODE_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /** Running build, reported in `hello`; the hub treats the APK hash as the installed version. */
    private fun buildAppInfo(): JSONObject {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val sha = runCatching {
            info.applicationInfo?.sourceDir?.let { ApkInstaller(context).sha256(File(it)) }
        }.getOrNull()
        return JSONObject()
            .put("package", context.packageName)
            .put("versionName", info.versionName)
            .put("versionCode", info.longVersionCode)
            .put("apkSha256", sha)
    }

    companion object {
        const val PREF_ENABLED = "hub_enabled"
        const val PREF_HUB_URL = "hub_url"
        /** Public node-face URL (HA Cloud / tunnel) used for the dial-out session. */
        const val PREF_NODE_URL = "hub_node_url"
        const val PREF_SESSION_PATH = "hub_session_path"
        const val PREF_NODE_ID = "hub_node_id"
        const val PREF_TOKEN = "hub_token"
        const val PREF_NAME = "hub_display_name"
        private const val PREF_LAST_ERROR = "hub_last_error"
        private const val TAG = "OaaHubClient"
        private const val LOG_BACKLOG = 200
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
