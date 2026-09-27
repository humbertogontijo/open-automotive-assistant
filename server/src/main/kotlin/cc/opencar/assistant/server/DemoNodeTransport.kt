package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaUiEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** In-process demo node for CI / OAA_DEMO_NODE=1: answers RPCs without a real car. */
class DemoNodeTransport(
    override val nodeId: String,
    private val eventBus: EventBus,
) : NodeTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            eventBus.publish(nodeId, JSONObject().put("t", OaaUiEvents.CATALOG).put("reason", "demo"))
            var n = 0
            while (isActive) {
                delay(5_000)
                n++
                eventBus.publish(
                    nodeId,
                    JSONObject().put("t", OaaUiEvents.TELEMETRY).put("telemetry", JSONObject().put("tick", n)),
                )
            }
        }
    }

    override suspend fun rpc(
        method: String,
        path: String,
        query: String?,
        contentType: String?,
        body: ByteArray?,
        timeoutMs: Long,
    ): OaaRpc.Response {
        val (status, text) = when {
            path == OaaPaths.STATUS ->
                200 to """{"role":"local","nodeId":"$nodeId","demo":true,"capabilities":[]}"""
            path.startsWith("/api/entities") || path.startsWith("/api/controls") ->
                200 to """[{"id":"climate.demo","domain":"climate","label":"Demo Climate","value":"off"}]"""
            path == OaaPaths.I18N ->
                200 to """{"locale":"en","locales":["en"],"integration":"demo","strings":{},"valueMaps":{}}"""
            path == "/api/echo" ->
                200 to JSONObject()
                    .put("method", method)
                    .put("query", query ?: "")
                    .put("contentType", contentType ?: "")
                    .put("body", body?.toString(Charsets.UTF_8) ?: "")
                    .toString()
            path == "/debug/export" ->
                return OaaRpc.Response(200, "application/zip", "attachment; filename=\"oaa-debug.zip\"", DEMO_ZIP)
            path == "/api/big" -> return OaaRpc.Response(200, "application/octet-stream", body = ByteArray(OaaRpc.MAX_BODY_BYTES + 1))
            else -> 404 to """{"ok":false,"error":"demo stub"}"""
        }
        return OaaRpc.Response(status, "application/json", body = text.toByteArray())
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        /** Binary payload (not valid UTF-8) used to exercise base64 rpc bodies. */
        val DEMO_ZIP = byteArrayOf(0x50, 0x4b, 0x05, 0x06, 0xff.toByte(), 0xfe.toByte(), 0x00, 0x80.toByte())
    }
}
