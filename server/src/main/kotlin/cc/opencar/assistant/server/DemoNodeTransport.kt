package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaUiEvents
import org.json.JSONArray
import org.json.JSONObject

/** In-process demo node for CI / OAA_DEMO_NODE=1: answers RPCs without a real car. */
open class DemoNodeTransport(
    override val nodeId: String,
    private val eventBus: EventBus,
) : NodeTransport {
    /** One entity per card type, so the web UI can be exercised without a car. */
    private val entities: JSONArray =
        JSONArray(checkNotNull(javaClass.getResource("/demo-catalog.json")).readText())
    private val hidden = mutableSetOf<String>()
    private val automations = mapOf(
        "shortcuts" to linkedMapOf<String, JSONObject>(),
        "routines" to linkedMapOf(),
        "scenes" to linkedMapOf(),
    )
    private val slots = JSONObject()
    private val overlay = JSONObject().put("overlayEnabled", true).put("style", "float_chip").put("canDrawOverlays", true)

    fun start() = publishCatalog("demo")

    private fun publishCatalog(reason: String) =
        eventBus.publish(nodeId, JSONObject().put("t", OaaUiEvents.CATALOG).put("reason", reason).toString())

    override suspend fun rpc(
        method: String,
        path: String,
        query: String?,
        contentType: String?,
        body: ByteArray?,
        timeoutMs: Long,
    ): OaaRpc.Response {
        val (status, text) = when {
            path == OaaPaths.STATUS -> 200 to JSONObject()
                .put("role", "local")
                .put("nodeId", nodeId)
                .put("demo", true)
                .put("capabilities", JSONArray().put("CAMERAS_DVR"))
                .put("dvr", demoDvrStatus())
                .toString()
            path == "/api/dvr/timeline" ->
                200 to JSONObject().put("recording", true).put("segments", JSONArray(demoSegments())).toString()
            path == "/api/dvr/play" -> 200 to demoPlay(query).toString()
            path == "/api/dvr/preview/start" || path == "/api/dvr/preview/stop" -> 200 to """{"ok":true}"""
            path == "/api/apps" -> 200 to """{"apps":[]}"""
            AUTOMATION_PATH.matches(path) -> automation(method, path, body)
            path == "/api/history" -> 200 to """{"entities":["sensor.battery","select.lane_assist"]}"""
            path.startsWith("/api/history/") ->
                200 to JSONObject().put("points", JSONArray(demoHistory(path.removePrefix("/api/history/")))).toString()
            method == "POST" && path.startsWith("/api/controls/") && !path.endsWith("/persist") -> {
                val id = java.net.URLDecoder.decode(path.removePrefix("/api/controls/"), Charsets.UTF_8)
                val value = formValue(body)
                if (value == null || !write(id, value)) {
                    404 to """{"ok":false,"error":"unknown control"}"""
                } else {
                    publishCatalog("demo-write")
                    200 to """{"ok":true}"""
                }
            }
            method == "POST" && path.startsWith("/api/entities/") && path.endsWith("/visibility") -> {
                val id = java.net.URLDecoder.decode(path.removePrefix("/api/entities/").removeSuffix("/visibility"), Charsets.UTF_8)
                val hide = body?.toString(Charsets.UTF_8)?.contains("hidden=1") == true
                synchronized(entities) { if (hide) hidden.add(id) else hidden.remove(id) }
                200 to """{"ok":true}"""
            }
            path == "/api/entities/hidden" -> 200 to JSONObject().put("entities", catalog(hiddenOnly = true)).toString()
            path.startsWith("/api/entities") || path.startsWith("/api/controls") -> 200 to catalog(hiddenOnly = false).toString()
            path == OaaPaths.I18N ->
                200 to """{"locale":"en","locales":["en"],"integration":"demo","strings":{},"valueMaps":{}}"""
            path == "/debug/export" ->
                return OaaRpc.Response(200, "application/zip", "attachment; filename=\"oaa-debug.zip\"", DEMO_ZIP)
            else -> 404 to """{"ok":false,"error":"demo stub"}"""
        }
        return OaaRpc.Response(status, "application/json", body = text.toByteArray())
    }

    /** In-memory shortcuts / routines / scenes so the editors can save, list and delete. */
    private fun automation(method: String, path: String, body: ByteArray?): Pair<Int, String> = synchronized(automations) {
        val parts = path.removePrefix("/api/").split("/").map { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        val kind = parts[0]
        val store = automations.getValue(kind)
        val id = parts.getOrNull(1)
        val ok = """{"ok":true}"""
        when {
            kind == "shortcuts" && id == null && method == "GET" -> 200 to JSONObject()
                .put("shortcuts", JSONArray(automations.getValue("shortcuts").values))
                .put("routines", JSONArray(automations.getValue("routines").values))
                .put("scenes", JSONArray(automations.getValue("scenes").values))
                .put("slots", slots)
                .put("overlay", overlay)
                .toString()
            kind == "shortcuts" && id == "slots" -> {
                val req = JSONObject(body?.toString(Charsets.UTF_8) ?: "{}")
                val slot = req.optInt("slot").toString()
                if (req.isNull("shortcutId")) slots.remove(slot) else slots.put(slot, req.getString("shortcutId"))
                200 to ok
            }
            kind == "shortcuts" && id == "overlay" -> {
                overlay.put("overlayEnabled", JSONObject(body?.toString(Charsets.UTF_8) ?: "{}").optBoolean("enabled", true))
                200 to ok
            }
            id == null && method == "POST" -> {
                val item = JSONObject(body?.toString(Charsets.UTF_8) ?: "{}")
                val newId = item.optString("id").ifEmpty { kind.dropLast(1) + "-" + (store.size + 1) }
                item.put("id", newId)
                store[newId] = item
                200 to JSONObject().put("ok", true).put("id", newId).toString()
            }
            id != null && method == "DELETE" -> {
                store.remove(id)
                200 to ok
            }
            id != null && parts.getOrNull(2) == "set" -> {
                store[id]?.put("active", JSONObject(body?.toString(Charsets.UTF_8) ?: "{}").optBoolean("active"))
                200 to ok
            }
            id != null && parts.getOrNull(2) == "run" -> if (id in store) 200 to ok else 404 to """{"ok":false,"error":"not found"}"""
            else -> 404 to """{"ok":false,"error":"demo stub"}"""
        }
    }

    private fun catalog(hiddenOnly: Boolean): JSONArray = synchronized(entities) {
        JSONArray((0 until entities.length()).map { entities.getJSONObject(it) }.filter { (it.optString("id") in hidden) == hiddenOnly })
    }

    private fun demoDvrStatus(): JSONObject = JSONObject()
        .put("mode", "dvr")
        .put("recording", true)
        .put("storageId", "app")
        .put(
            "storages",
            JSONArray().put(
                JSONObject().put("id", "app").put("labelKey", "cameras.storage.app").put("label", "App")
                    .put("freeBytes", 6L shl 30).put("totalBytes", 16L shl 30),
            ),
        )
        .put("policy", JSONObject().put("maxTotalMb", 2048).put("maxAgeDays", 7))
        .put("usageBytes", 900L shl 20)
        .put("usageCount", 12)

    /** Ten-minute segments: a few this morning, the last one still recording, two yesterday. */
    private fun demoSegments(): List<JSONObject> {
        val seg = 10 * 60_000L
        val now = System.currentTimeMillis()
        val live = now - now % seg
        val starts = listOf(live - 26 * 3_600_000L, live - 25 * 3_600_000L) +
            (6 downTo 1).map { live - it * seg - 3_600_000L } + listOf(live - seg, live)
        return starts.map { start ->
            val active = start == live
            JSONObject()
                .put("name", "oaa_dvr_$start.mp4")
                .put("startUtcMs", start)
                .put("endUtcMs", if (active) now else start + seg)
                .put("active", active)
        }
    }

    private fun demoPlay(query: String?): JSONObject {
        val at = query?.split('&')?.firstOrNull { it.startsWith("atMs=") }?.removePrefix("atMs=")?.toLongOrNull()
            ?: return JSONObject().put("ok", false).put("error", "atMs required")
        val closed = demoSegments().filterNot { it.getBoolean("active") }
        val seg = closed.firstOrNull { at < it.getLong("endUtcMs") } ?: return JSONObject().put("ok", true).put("live", true)
        val start = seg.getLong("startUtcMs")
        val end = seg.getLong("endUtcMs")
        return JSONObject()
            .put("ok", true)
            .put("name", seg.getString("name"))
            .put("startUtcMs", start)
            .put("endUtcMs", end)
            .put("durationMs", end - start)
            .put("offsetMs", (at - start).coerceAtLeast(0))
    }

    /** A day of samples: battery drains and recharges, lane assist cycles through its modes. */
    private fun demoHistory(id: String): List<JSONObject> {
        val now = System.currentTimeMillis()
        val step = 20 * 60_000L
        return (72 downTo 0).map { i ->
            val ts = now - i * step
            val value: Any = if (id == "select.lane_assist") listOf("off", "warn", "assist")[(i / 9) % 3]
            else (60 + 30 * kotlin.math.sin(i / 12.0)).toInt()
            JSONObject().put("ts", ts).put("value", value.toString())
        }
    }

    private fun formValue(body: ByteArray?): String? =
        body?.toString(Charsets.UTF_8)?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == "value" && it.size == 2 }
            ?.let { java.net.URLDecoder.decode(it[1], Charsets.UTF_8) }

    /**
     * Applies a control write the way a car would echo it back: `key:value;key:value` sets
     * attributes, anything else sets the value.
     */
    private fun write(id: String, raw: String): Boolean = synchronized(entities) {
        val e = (0 until entities.length()).map { entities.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
            ?: return false
        val attrs = e.optJSONObject("attributes") ?: JSONObject().also { e.put("attributes", it) }
        val pairs = raw.split(';').mapNotNull { p -> p.split(':', limit = 2).takeIf { it.size == 2 } }
        if (pairs.isEmpty()) {
            when {
                e.optString("domain") == "climate" && raw == "off" -> attrs.put("hvac_mode", "off")
                raw == "play" -> e.put("value", "playing")
                raw == "pause" -> e.put("value", "paused")
                e.optString("input") != "command" && raw !in setOf("previous", "next") -> e.put("value", raw)
            }
            if (e.optString("domain") == "light" && raw == "off") attrs.put("brightness", 0)
            if (e.optString("domain") == "cover" && attrs.has("current_position")) {
                raw.toIntOrNull()?.let { e.put("value", if (it > 0) "open" else "closed"); attrs.put("current_position", it) }
                    ?: attrs.put("current_position", if (raw == "open") 100 else 0)
            }
            return true
        }
        for ((k, v) in pairs) {
            attrs.put(k, v.toIntOrNull() ?: v.toDoubleOrNull() ?: v)
            when (k) {
                "hvac_mode" -> e.put("value", v)
                "brightness" -> e.put("value", if ((v.toDoubleOrNull() ?: 0.0) > 0) "on" else "off")
            }
        }
        true
    }

    companion object {
        /** Binary payload (not valid UTF-8) used to exercise base64 rpc bodies. */
        val DEMO_ZIP = byteArrayOf(0x50, 0x4b, 0x05, 0x06, 0xff.toByte(), 0xfe.toByte(), 0x00, 0x80.toByte())

        private val AUTOMATION_PATH = Regex("^/api/(shortcuts|routines|scenes)(/.*)?$")
    }
}
