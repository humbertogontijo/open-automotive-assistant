package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Remote app logs: viewers of `/debug/logs/stream` subscribe per car; the hub asks
 * the car to stream `log` frames while at least one viewer is attached.
 */
class LogRelay(private val registry: NodeRegistry) {
    private val viewers = ConcurrentHashMap<String, CopyOnWriteArraySet<SignalPeer>>()
    private val tokens = ConcurrentHashMap<String, String>()

    suspend fun subscribe(nodeId: String, viewer: SignalPeer, token: String?): Boolean {
        val node = registry.session(nodeId) ?: return false
        viewers.getOrPut(nodeId) { CopyOnWriteArraySet() }.add(viewer)
        if (token != null) tokens[nodeId] = token else tokens.remove(nodeId)
        return node.send(subscribeFrame(nodeId))
    }

    suspend fun unsubscribe(nodeId: String, viewer: SignalPeer) {
        val set = viewers[nodeId] ?: return
        set.remove(viewer)
        if (set.isEmpty()) {
            viewers.remove(nodeId, set)
            tokens.remove(nodeId)
            registry.session(nodeId)?.send(OaaFrames.frame(OaaFrames.LOG_UNSUBSCRIBE, JSONObject()))
        }
    }

    /** Car reconnected: resume streaming for viewers that stayed attached. */
    suspend fun onHello(nodeId: String) {
        if (viewers[nodeId].isNullOrEmpty()) return
        registry.session(nodeId)?.send(subscribeFrame(nodeId))
    }

    suspend fun onLog(nodeId: String, line: String) {
        viewers[nodeId]?.forEach { runCatching { it.send(line) } }
    }

    private fun subscribeFrame(nodeId: String): String {
        val payload = OaaFrames.versioned()
        tokens[nodeId]?.let { payload.put("token", it) }
        return OaaFrames.frame(OaaFrames.LOG_SUBSCRIBE, payload)
    }
}
