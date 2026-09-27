package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaWebRtc
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A connected car as the hub sees it: proxied HTTP plus a frame push channel. */
interface NodeTransport {
    val nodeId: String

    suspend fun rpc(
        method: String,
        path: String,
        query: String? = null,
        contentType: String? = null,
        body: ByteArray? = null,
        timeoutMs: Long = OaaRpc.TIMEOUT_MS,
    ): OaaRpc.Response

    /** Push a frame to the car. False when the car cannot take it (offline, unsupported). */
    suspend fun send(frame: String): Boolean = false

    /** Drop the connection (replaced, removed, revoked). */
    fun close() {}
}

/** Frames a car pushes that the hub routes beyond rpc results and UI events. */
interface NodeListener {
    suspend fun onSignal(nodeId: String, text: String) {}
    suspend fun onHello(nodeId: String, payload: JSONObject) {}
    suspend fun onOtaStatus(nodeId: String, payload: JSONObject) {}
    suspend fun onLog(nodeId: String, line: String) {}
}

/** Live WebSocket to a paired car node. */
class NodeSession(
    override val nodeId: String,
    private val socket: WebSocketSession,
    private val eventBus: EventBus,
    private val listener: NodeListener,
) : NodeTransport {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<OaaRpc.Response>>()
    private val rpcSeq = AtomicLong(0)
    private val sendMutex = Mutex()

    suspend fun handleFrame(text: String) {
        OaaFrames.eventPayload(text)?.let {
            eventBus.publish(nodeId, it)
            return
        }
        val json = OaaFrames.parse(text) ?: return
        val type = json.optString("type")
        if (type in OaaWebRtc.SIGNAL_TYPES) {
            listener.onSignal(nodeId, text)
            return
        }
        val payload = json.optJSONObject("payload")
        when (type) {
            OaaFrames.RPC_RESULT -> {
                val p = payload ?: return
                pending.remove(p.optString("id"))?.complete(OaaRpc.decodeResponse(p))
            }
            OaaFrames.EVENT -> payload?.let { eventBus.publish(nodeId, it.toString()) }
            OaaFrames.HELLO -> payload?.let { listener.onHello(nodeId, it) }
            OaaFrames.OTA_STATUS -> payload?.let { listener.onOtaStatus(nodeId, it) }
            OaaFrames.LOG -> payload?.optString("line")?.let { listener.onLog(nodeId, it) }
            OaaFrames.PING -> send(OaaFrames.frame(OaaFrames.PONG))
            else -> Unit
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
        val id = rpcSeq.incrementAndGet().toString()
        val deferred = CompletableDeferred<OaaRpc.Response>()
        pending[id] = deferred
        try {
            val frame = OaaRpc.encodeRequest(OaaRpc.Request(id, method, path, query, contentType, body))
            sendMutex.withLock { socket.send(Frame.Text(frame)) }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun send(frame: String): Boolean = try {
        sendMutex.withLock { socket.send(Frame.Text(frame)) }
        true
    } catch (_: Exception) {
        false
    }

    override fun close() {
        pending.values.forEach { it.complete(OaaRpc.error(503, "node offline")) }
        pending.clear()
        socket.launch { runCatching { socket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "session closed")) } }
    }
}
