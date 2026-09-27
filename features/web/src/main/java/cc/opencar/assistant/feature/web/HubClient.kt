package cc.opencar.assistant.feature.web

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.provider.Settings
import android.util.Log
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.install.ApkInstaller
import cc.opencar.assistant.feature.web.webrtc.CarWebRtc
import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaCarAuth
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Outbound hub link for this car: pair with a code, then keep one WebSocket to
 * the hub's node face. Over it the hub proxies HTTP (`rpc`), relays WebRTC
 * signaling, streams logs, and offers app updates; the car forwards its
 * `/api/events` messages. The socket goes to the hub's local URL whenever the hub
 * answers there and to its public URL otherwise (see [HubEndpoints]).
 */
class HubClient(
    private val context: Context,
    private val session: VehicleSession,
    private val prefs: SharedPreferences,
    private val debug: ContributorDebugState,
    private val auth: CarAuth,
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
    private val probeClient = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
    private val joinMutex = Mutex()
    private val wsRef = AtomicReference<WebSocket?>(null)
    @Volatile private var online = false
    @Volatile private var via: HubEndpoints.Via? = null
    private var lastFailed: HubEndpoints.Via? = null
    private val networkChanged = AtomicBoolean(false)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged.set(true)
        override fun onLost(network: Network) = networkChanged.set(true)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = networkChanged.set(true)
    }
    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var logJob: Job? = null
    private var appInfo: JSONObject? = null

    private val dvrRef = dvr
    private val webrtc = dvr?.let { CarWebRtc(it, ::send) }
    private val ota = OtaUpdater(context, installer, http, scope) { send(OaaFrames.frame(OaaFrames.OTA_STATUS, it)) }
    private val advertiser = CarMdnsAdvertiser(context, localPort)

    val nodeId: String? get() = prefs.getString(PREF_NODE_ID, null)

    private val paired: Boolean get() = !prefs.getString(PREF_TOKEN, null).isNullOrBlank()

    fun displayName(): String = prefs.getString(PREF_NAME, null)?.takeIf { it.isNotBlank() } ?: session.integrationId

    fun status(): Map<String, Any?> {
        val endpoints = endpoints()
        val current = via
        return mapOf(
            "enabled" to prefs.getBoolean(PREF_ENABLED, false),
            "hubUrl" to prefs.getString(PREF_HUB_URL, "")?.trim().orEmpty(),
            "hubId" to prefs.getString(PREF_HUB_ID, null),
            "hubName" to prefs.getString(PREF_HUB_NAME, null),
            "nodeUrl" to current?.let { endpoints[it]?.url },
            "localNodeUrl" to endpoints.local?.url,
            "publicNodeUrl" to endpoints.public?.url,
            "via" to current?.wire,
            "nodeId" to nodeId,
            "paired" to paired,
            "online" to online,
            "lastError" to prefs.getString(PREF_LAST_ERROR, null),
        )
    }

    fun start() {
        if (loopJob != null) return
        ensureNodeId()
        updateAdvertising()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
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
        advertiser.stop()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
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
                val publicPath = json.optString("sessionPath").trim().takeIf { it.isNotEmpty() } ?: defaultSessionPath
                val published = publicNodeUrl?.let { NodeUrl(it, publicPath) }
                val typed = NodeUrl(origin, defaultSessionPath)
                val endpoints = if (typed.looksPublic) {
                    HubEndpoints(local = null, public = published ?: typed)
                } else {
                    HubEndpoints(local = typed, public = published?.takeIf { it.url != typed.url })
                }
                val hubId = json.optString("hubId").takeIf { it.isNotBlank() }
                val hubName = json.optString("hubName").takeIf { it.isNotBlank() } ?: uri.host
                trustHub(hubId, hubName)
                prefs.edit()
                    .putBoolean(PREF_ENABLED, true)
                    .putString(PREF_HUB_URL, base)
                    .putString(PREF_TOKEN, json.getString("token"))
                    .putString(PREF_NODE_ID, json.optString("nodeId", id))
                    .putString(PREF_NAME, name)
                    .putEndpoints(endpoints)
                    .putString(PREF_HUB_ID, hubId)
                    .putString(PREF_HUB_NAME, hubName)
                    .remove(PREF_LAST_ERROR)
                    .apply()
                lastFailed = null
                disconnect()
                updateAdvertising()
                mapOf("ok" to true) + status()
            }
        }
    }

    /**
     * Accept a hub that asked to pair and was confirmed with the car's code. [link] carries
     * the node token the hub pre-registered plus its node port and public URLs; the address
     * the hub called from becomes the local URL.
     */
    suspend fun linkFromHub(link: JSONObject, sourceIp: String, requestName: String): Map<String, Any?> {
        val token = link.optString("nodeToken").trim()
        if (token.isEmpty()) return mapOf("ok" to false, "error" to "missing node token")
        val nodePort = link.optInt("nodePort", OaaPorts.NODE_DEFAULT)
        val host = if (':' in sourceIp) "[$sourceIp]" else sourceIp
        val publicPath = link.optString("sessionPath").trim().ifEmpty { OaaPaths.NODES_SESSION }
        val local = NodeUrl("http://$host:$nodePort")
        val arr = link.optJSONArray("nodeUrls")
        val public = (0 until (arr?.length() ?: 0))
            .map { arr!!.optString(it).trim().trimEnd('/') }
            .firstOrNull { it.startsWith("http") && it != local.url }
            ?.let { NodeUrl(it, publicPath) }
        val hubId = link.optString("hubId").takeIf { it.isNotBlank() }
        val hubName = link.optString("hubName").takeIf { it.isNotBlank() } ?: requestName
        joinMutex.withLock {
            val previous = prefs.getString(PREF_HUB_ID, null)
            if (previous != null && previous != hubId) auth.store.revokeHub(previous)
            prefs.edit()
                .putBoolean(PREF_ENABLED, true)
                .putString(PREF_HUB_URL, local.url)
                .putString(PREF_TOKEN, token)
                .putEndpoints(HubEndpoints(local, public))
                .putString(PREF_HUB_ID, hubId)
                .putString(PREF_HUB_NAME, hubName)
                .remove(PREF_LAST_ERROR)
                .apply()
            lastFailed = null
            disconnect()
        }
        updateAdvertising()
        return mapOf("ok" to true)
    }

    /** A hub's trusted-client entry was revoked on the head unit; drop the link if it is ours. */
    fun onHubRevoked(hubId: String?) {
        val current = prefs.getString(PREF_HUB_ID, null)
        if (hubId == null || current == null || hubId == current) leave()
    }

    fun leave(): Map<String, Any?> {
        prefs.edit()
            .putBoolean(PREF_ENABLED, false)
            .remove(PREF_TOKEN)
            .remove(PREF_HUB_URL)
            .putEndpoints(HubEndpoints(null, null))
            .remove(PREF_HUB_ID)
            .remove(PREF_HUB_NAME)
            .remove(PREF_LAST_ERROR)
            .apply()
        auth.store.revokeHub(null)
        lastFailed = null
        disconnect()
        updateAdvertising()
        return mapOf("ok" to true) + status()
    }

    /** Manual pairing (code typed on the car) also lists the hub under trusted devices. */
    private fun trustHub(hubId: String?, hubName: String) {
        val previous = prefs.getString(PREF_HUB_ID, null)
        if (previous != null && previous != hubId) auth.store.revokeHub(previous)
        auth.store.addClient(OaaCarAuth.KIND_HUB, hubName, hubId)
    }

    private fun updateAdvertising() {
        val id = nodeId
        if (paired || id == null) advertiser.stop() else advertiser.start(id, displayName(), session.integrationId)
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
        val endpoints = endpoints()
        if (endpoints.isEmpty) return
        if (wsRef.get() != null) return
        val app = appInfo ?: buildAppInfo().also { appInfo = it }

        networkChanged.set(false)
        val local = endpoints.local
        val chosen = endpoints.choose(local != null && localAnswers(local), lastFailed) ?: return
        val node = endpoints[chosen]!!
        via = chosen
        val wsUrl = node.url.replace(Regex("^http"), "ws") + node.sessionPath +
            "?token=" + URLEncoder.encode(token, Charsets.UTF_8.name())
        val listener = Listener(token, node.url, app)
        val ws = wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        wsRef.set(ws)
        // Hold this loop iteration while the socket lives; onClosed/onFailure clear the ref.
        var nextLocalProbe = System.currentTimeMillis() + LOCAL_PROBE_MS
        while (wsRef.get() === ws) {
            delay(2_000)
            if (!listener.opened || local == null || endpoints.public == null) continue
            val changed = networkChanged.getAndSet(false)
            val switchTo = when (chosen) {
                HubEndpoints.Via.PUBLIC -> {
                    if (!changed && System.currentTimeMillis() < nextLocalProbe) continue
                    nextLocalProbe = System.currentTimeMillis() + LOCAL_PROBE_MS
                    HubEndpoints.Via.LOCAL.takeIf { localAnswers(local) }
                }
                HubEndpoints.Via.LOCAL -> HubEndpoints.Via.PUBLIC.takeIf { changed && !localAnswers(local) }
            } ?: continue
            if (wsRef.get() !== ws) break
            LogRingBuffer.append("Hub switching to ${switchTo.wire} URL")
            listener.release(ws)
            ws.close(1000, "switching to ${switchTo.wire}")
        }
        lastFailed = if (listener.opened) null else chosen
    }

    /** The hub's node face answers on [local] right now, and it is our hub. */
    private fun localAnswers(local: NodeUrl): Boolean = runCatching {
        val req = Request.Builder().url(local.url + OaaPaths.HEALTH).build()
        probeClient.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            resp.isSuccessful && json.optString("face") == "node" &&
                json.optString("hubId") == prefs.getString(PREF_HUB_ID, null)
        }
    }.getOrDefault(false)

    private inner class Listener(
        private val token: String,
        private val nodeUrl: String,
        private val app: JSONObject,
    ) : WebSocketListener() {
        @Volatile var opened = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            online = true
            setError(null)
            LogRingBuffer.append("Hub session connected")
            val hello = OaaFrames.versioned()
                .put("nodeId", nodeId.orEmpty())
                .put("integration", session.integrationId)
                .put("name", prefs.getString(PREF_NAME, "").orEmpty())
                .put("version", OaaBuild.VERSION)
                .put("app", app)
                .put("via", via?.wire)
            webSocket.send(OaaFrames.frame(OaaFrames.HELLO, hello))
            eventsJob?.cancel()
            eventsJob = scope.launch {
                uiEvents(session, dvrRef).collect { msg ->
                    webSocket.send(OaaFrames.eventFrame(gson.toJson(msg)))
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
                OaaFrames.PUBLIC_NODE -> if (OaaFrames.isCurrentVersion(payload)) applyPublicNode(payload!!)
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
            if (wsRef.get() !== webSocket) return
            setError(t.message ?: "ws failure")
            Log.w(TAG, "hub ws failure", t)
            dropped(webSocket)
        }

        /** Let the session loop move on now; the socket's own close callbacks become no-ops. */
        fun release(webSocket: WebSocket) = dropped(webSocket)

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
            .header(OaaHeaders.INTERNAL_RPC, auth.internalSecret)
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
        via = null
        eventsJob?.cancel()
        stopLogStream()
    }

    private fun setError(msg: String?) {
        val ed = prefs.edit()
        if (msg == null) ed.remove(PREF_LAST_ERROR) else ed.putString(PREF_LAST_ERROR, msg.take(200))
        ed.apply()
    }

    /**
     * The hub's current public node endpoint (sent on every connect and when it changes) replaces
     * the public URL. "none" is ignored, since dropping the only route a car away from home has
     * would strand it where it can never hear the next update.
     */
    private fun applyPublicNode(payload: JSONObject) {
        val next = NodeUrl.fromPublicNode(payload) ?: return
        val endpoints = endpoints()
        if (next == endpoints.public) return
        prefs.edit().putEndpoints(endpoints.copy(public = next)).apply()
        LogRingBuffer.append("Hub public URL: ${next.url}")
    }

    private fun endpoints() = HubEndpoints(
        local = NodeUrl.parse(prefs.getString(PREF_LOCAL_NODE, null)),
        public = NodeUrl.parse(prefs.getString(PREF_PUBLIC_NODE, null)),
    )

    private fun SharedPreferences.Editor.putEndpoints(e: HubEndpoints) = this
        .putString(PREF_LOCAL_NODE, e.local?.toJson()?.toString())
        .putString(PREF_PUBLIC_NODE, e.public?.toJson()?.toString())

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
        /** `{url, sessionPath}` of the node face on the hub's LAN. */
        private const val PREF_LOCAL_NODE = "hub_local_node"
        /** `{url, sessionPath}` of the node face from anywhere; follows the hub's `public_node` frame. */
        private const val PREF_PUBLIC_NODE = "hub_public_node"
        const val PREF_HUB_ID = "hub_id"
        const val PREF_HUB_NAME = "hub_name"
        const val PREF_NODE_ID = "hub_node_id"
        const val PREF_TOKEN = "hub_token"
        const val PREF_NAME = "hub_display_name"
        private const val PREF_LAST_ERROR = "hub_last_error"
        /** While on the public URL, how often to check whether the hub answers locally again. */
        private const val LOCAL_PROBE_MS = 60_000L
        private const val TAG = "OaaHubClient"
        private const val LOG_BACKLOG = 200
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
