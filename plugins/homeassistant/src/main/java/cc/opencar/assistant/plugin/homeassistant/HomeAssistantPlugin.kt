package cc.opencar.assistant.plugin.homeassistant

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.plugin.OaaPlugin
import cc.opencar.assistant.api.plugin.PluginConfigField
import cc.opencar.assistant.api.plugin.PluginConfigFieldTypes
import cc.opencar.assistant.api.plugin.PluginConfigSchema
import cc.opencar.assistant.api.plugin.PluginHost
import cc.opencar.assistant.api.plugin.ShortcutActionHandler
import cc.opencar.assistant.api.plugin.ShortcutTriggerListener
import cc.opencar.assistant.api.plugin.ShortcutTriggerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Home Assistant bridge via REST + WebSocket (Nabu Casa / local / reverse proxy).
 * Shortcut action: `call_service`. Trigger: `entity_state`.
 */
class HomeAssistantPlugin : OaaPlugin {
    override val id: String = ID
    override val displayName: String = "Home Assistant"

    private val actions = HaActionHandler()
    private val triggers = HaTriggerSource()

    override val actionHandler: ShortcutActionHandler get() = actions
    override val triggerSource: ShortcutTriggerSource get() = triggers

    private var prefs: android.content.SharedPreferences? = null
    private var host: PluginHost? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val wsRef = AtomicReference<WebSocket?>(null)
    private val connected = AtomicBoolean(false)
    private val msgId = AtomicInteger(1)
    private var triggerListener: ShortcutTriggerListener? = null
    private var pluginStarted = false

    var baseUrl: String
        get() = prefs?.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_BASE_URL, value.trim().trimEnd('/'))?.apply()
        }

    var token: String
        get() = prefs?.getString(KEY_TOKEN, "")?.orEmpty() ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_TOKEN, value.trim())?.apply()
        }

    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    val isConnected: Boolean get() = connected.get()

    override suspend fun start(host: PluginHost) {
        this.host = host
        prefs = host.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pluginStarted = true
        maybeConnect()
    }

    override fun stop() {
        pluginStarted = false
        disconnectWebSocket()
        triggerListener = null
        host = null
    }

    /** Apply config from web API and reconnect if needed. */
    fun applyConfig(baseUrl: String?, token: String?, enabled: Boolean?) {
        if (baseUrl != null) this.baseUrl = baseUrl
        if (token != null && token.isNotBlank()) this.token = token
        if (enabled != null) this.enabled = enabled
        disconnectWebSocket()
        maybeConnect()
    }

    override fun configSchema(): PluginConfigSchema = PluginConfigSchema(
        fields = listOf(
            PluginConfigField(
                key = "baseUrl",
                type = PluginConfigFieldTypes.TEXT,
                label = "Home Assistant URL",
                optional = false,
                placeholder = "https://xxxx.ui.nabu.casa",
                description = "Local address, reverse proxy, or Nabu Casa URL (no trailing slash).",
            ),
            PluginConfigField(
                key = "token",
                type = PluginConfigFieldTypes.PASSWORD,
                label = "Long-lived access token",
                optional = true,
                placeholder = "Paste token from HA → Profile → Security",
                description = "Create a long-lived access token in Home Assistant. If a token is already saved, leave this empty to keep it.",
            ),
            PluginConfigField(
                key = "enabled",
                type = PluginConfigFieldTypes.BOOL,
                label = "Enable after save",
                optional = true,
            ),
        ),
    )

    override fun configSnapshot(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "connected" to isConnected,
        "baseUrl" to baseUrl,
        "tokenSet" to token.isNotBlank(),
        "tokenHint" to tokenHint(token),
        "configured" to isConfigured,
    )

    override fun applyConfig(values: Map<String, Any?>) {
        val baseUrl = values["baseUrl"]?.toString()
        val token = values["token"]?.toString()
        val enabled = when (val v = values["enabled"]) {
            null -> null
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.lowercase()) {
                "1", "true", "on" -> true
                "0", "false", "off" -> false
                else -> null
            }
            else -> null
        }
        applyConfig(baseUrl = baseUrl, token = token, enabled = enabled)
    }

    override fun status(): Map<String, Any?> = mapOf(
        "id" to id,
        "displayName" to displayName,
        "enabled" to enabled,
        "connected" to isConnected,
        "baseUrl" to baseUrl,
        "hasToken" to token.isNotBlank(),
        "configured" to isConfigured,
        "description" to "Control Home Assistant entities from shortcuts (REST + WebSocket).",
        "actions" to listOf(ACTION_CALL_SERVICE),
        "triggers" to listOf(TRIGGER_ENTITY_STATE),
        "actionParams" to mapOf(
            ACTION_CALL_SERVICE to listOf("domain", "service", "entity_id", "data"),
        ),
        "triggerParams" to mapOf(
            TRIGGER_ENTITY_STATE to listOf("entity_id", "from", "to"),
        ),
    )

    private val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    private fun maybeConnect() {
        if (pluginStarted && enabled && baseUrl.isNotBlank() && token.isNotBlank()) {
            connectWebSocket()
        }
    }

    private inner class HaActionHandler : ShortcutActionHandler {
        override val pluginId: String get() = id
        override val actionTypes: Set<String> = setOf(ACTION_CALL_SERVICE)

        override suspend fun run(type: String, params: Map<String, Any?>): Result<Unit> {
            if (type != ACTION_CALL_SERVICE) {
                return Result.failure(IllegalArgumentException("unsupported action: $type"))
            }
            if (!enabled) return Result.failure(IllegalStateException("Home Assistant disabled"))
            val domain = params["domain"]?.toString()?.trim().orEmpty()
            val service = params["service"]?.toString()?.trim().orEmpty()
            if (domain.isEmpty() || service.isEmpty()) {
                return Result.failure(IllegalArgumentException("domain and service required"))
            }
            val entityId = params["entity_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            return callService(domain, service, entityId, params["data"])
        }
    }

    private inner class HaTriggerSource : ShortcutTriggerSource {
        override val pluginId: String get() = id
        override val triggerTypes: Set<String> = setOf(TRIGGER_ENTITY_STATE)

        override fun start(listener: ShortcutTriggerListener) {
            triggerListener = listener
            maybeConnect()
        }

        override fun stop() {
            triggerListener = null
        }
    }

    companion object {
        const val ID = "homeassistant"
        const val ACTION_CALL_SERVICE = "call_service"
        const val TRIGGER_ENTITY_STATE = "entity_state"
        private const val PREFS = "oaa_plugin_homeassistant"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"
        private const val TAG = "HaPlugin"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun tokenHint(token: String): String {
            if (token.isBlank()) return ""
            if (token.length <= 8) return "••••"
            return token.take(4) + "…" + token.takeLast(4)
        }
    }

    private fun restBase(): String = baseUrl.trim().trimEnd('/')

    private fun authRequest(path: String): Request.Builder {
        val url = restBase() + path
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        data: Any?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (restBase().isBlank() || token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Home Assistant not configured"))
            }
            val bodyJson = JSONObject()
            if (!entityId.isNullOrBlank()) bodyJson.put("entity_id", entityId)
            mergeData(bodyJson, data)
            val req = authRequest("/api/services/$domain/$service")
                .post(bodyJson.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(200) ?: resp.message
                    return@withContext Result.failure(IllegalStateException("HA ${resp.code}: $err"))
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "call_service failed: ${t.message}")
            Result.failure(t)
        }
    }

    suspend fun getState(entityId: String): Result<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val req = authRequest("/api/states/${entityId.trim()}").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HA ${resp.code}: ${resp.message}"),
                    )
                }
                val text = resp.body?.string() ?: return@withContext Result.failure(
                    IllegalStateException("empty body"),
                )
                Result.success(jsonToMap(JSONObject(text)))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun connectWebSocket() {
        disconnectWebSocket()
        val httpBase = restBase()
        if (httpBase.isBlank() || token.isBlank()) return
        val wsUrl = when {
            httpBase.startsWith("https://") ->
                "wss://" + httpBase.removePrefix("https://") + "/api/websocket"
            httpBase.startsWith("http://") ->
                "ws://" + httpBase.removePrefix("http://") + "/api/websocket"
            else -> {
                Log.w(TAG, "baseUrl must start with http:// or https://")
                return
            }
        }
        val req = Request.Builder().url(wsUrl).build()
        val socket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "websocket open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                Log.i(TAG, "websocket closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                Log.w(TAG, "websocket failure: ${t.message}")
            }
        })
        wsRef.set(socket)
    }

    private fun disconnectWebSocket() {
        connected.set(false)
        wsRef.getAndSet(null)?.close(1000, "stop")
    }

    private fun handleWsMessage(webSocket: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "auth_required" -> {
                    webSocket.send(
                        JSONObject()
                            .put("type", "auth")
                            .put("access_token", token)
                            .toString(),
                    )
                }
                "auth_ok" -> {
                    connected.set(true)
                    Log.i(TAG, "websocket authenticated")
                    val sid = msgId.getAndIncrement()
                    webSocket.send(
                        JSONObject()
                            .put("id", sid)
                            .put("type", "subscribe_events")
                            .put("event_type", "state_changed")
                            .toString(),
                    )
                }
                "auth_invalid" -> {
                    connected.set(false)
                    Log.w(TAG, "websocket auth invalid")
                    webSocket.close(1008, "auth_invalid")
                }
                "event" -> {
                    val event = msg.optJSONObject("event") ?: return
                    if (event.optString("event_type") != "state_changed") return
                    val data = event.optJSONObject("data") ?: return
                    val entityId = data.optString("entity_id")
                    if (entityId.isBlank()) return
                    val oldState = data.optJSONObject("old_state")?.optString("state")
                    val newState = data.optJSONObject("new_state")?.optString("state")
                    if (oldState == null || newState == null) return
                    if (oldState == newState) return
                    triggerListener?.onTrigger(
                        id,
                        TRIGGER_ENTITY_STATE,
                        mapOf(
                            "entity_id" to entityId,
                            "from" to oldState,
                            "to" to newState,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ws message parse: ${t.message}")
        }
    }

    private fun mergeData(into: JSONObject, data: Any?) {
        when (data) {
            null -> Unit
            is Map<*, *> -> {
                for ((k, v) in data) {
                    val key = k as? String ?: continue
                    if (key == "entity_id" && into.has("entity_id")) continue
                    into.put(key, toJsonValue(v))
                }
            }
            is String -> {
                val trimmed = data.trim()
                if (trimmed.isEmpty()) return
                try {
                    val obj = JSONObject(trimmed)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "entity_id" && into.has("entity_id")) continue
                        into.put(key, obj.get(key))
                    }
                } catch (_: Throwable) {
                    // ignore invalid JSON string
                }
            }
        }
    }

    private fun toJsonValue(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Number, is Boolean, is String -> v
        is Map<*, *> -> JSONObject().also { o ->
            for ((k, vv) in v) {
                val key = k as? String ?: continue
                o.put(key, toJsonValue(vv))
            }
        }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
        else -> v.toString()
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = when (val v = obj.get(k)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonToMap(v)
                is JSONArray -> (0 until v.length()).map { idx ->
                    when (val item = v.get(idx)) {
                        is JSONObject -> jsonToMap(item)
                        JSONObject.NULL -> null
                        else -> item
                    }
                }
                else -> v
            }
        }
        return out
    }
}
