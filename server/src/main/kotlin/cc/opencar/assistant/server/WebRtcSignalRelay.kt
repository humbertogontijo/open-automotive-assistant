package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaFrames.hangup
import cc.opencar.assistant.protocol.OaaWebRtc
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Viewer end of a signaling binding (the SPA WebSocket in production). */
fun interface SignalPeer {
    suspend fun send(text: String)
}

/**
 * Signaling-only relay between hub SPA sockets and car node sessions (ADR-0003).
 * Binds viewer ↔ node by `sessionId`; one session per node (a new offer replaces
 * the prior one). SDP/ICE pass through untouched — media never touches the hub.
 */
class WebRtcSignalRelay(
    private val registry: NodeRegistry,
    private val ice: IceConfig,
) {
    private class Binding(
        val sessionId: String,
        val nodeId: String,
        val ui: SignalPeer,
    ) {
        val uiIce = AtomicInteger(0)
        val nodeIce = AtomicInteger(0)
    }

    private val lock = Any()
    private val bySession = HashMap<String, Binding>()
    private val byNode = HashMap<String, String>()

    internal fun activeSessions(): Int = synchronized(lock) { bySession.size }

    internal fun sessionFor(nodeId: String): String? = synchronized(lock) { byNode[nodeId] }

    suspend fun onUiFrame(nodeId: String, ui: SignalPeer, text: String) {
        if (text.length > MAX_FRAME_CHARS) {
            ui.safeSend(hangup(null, OaaWebRtc.REASON_INVALID))
            return
        }
        val json = OaaFrames.parse(text) ?: return
        val type = json.optString("type")
        if (type !in OaaWebRtc.SIGNAL_TYPES) return
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val sessionId = payload.optString("sessionId").takeIf { it.isNotEmpty() }
        if (!OaaFrames.isCurrentVersion(payload)) {
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_VERSION))
            return
        }
        if (!OaaWebRtc.isValidSessionId(sessionId)) {
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_INVALID))
            return
        }
        sessionId!!
        when (type) {
            OaaWebRtc.OFFER -> handleOffer(nodeId, ui, sessionId, payload)
            OaaWebRtc.ICE -> {
                val b = boundTo(sessionId, nodeId, ui) ?: return
                if (b.uiIce.incrementAndGet() > OaaWebRtc.MAX_ICE_PER_SESSION) return
                forwardToNode(nodeId, OaaWebRtc.ICE, payload)
            }
            OaaWebRtc.HANGUP -> {
                boundTo(sessionId, nodeId, ui) ?: return
                unbind(sessionId)
                forwardToNode(nodeId, OaaWebRtc.HANGUP, payload)
            }
            else -> Unit
        }
    }

    private suspend fun handleOffer(nodeId: String, ui: SignalPeer, sessionId: String, payload: JSONObject) {
        val sdp = payload.optString("sdp")
        if (sdp.isEmpty()) {
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_INVALID))
            return
        }
        val node = registry.session(nodeId)
        if (node == null) {
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_OFFLINE))
            return
        }
        var replaced: Binding? = null
        val accepted = synchronized(lock) {
            val existing = bySession[sessionId]
            if (existing != null && (existing.nodeId != nodeId || existing.ui !== ui)) {
                return@synchronized false
            }
            val prior = byNode[nodeId]?.let { bySession[it] }
            if (prior != null && prior.sessionId != sessionId) {
                removeLocked(prior.sessionId)
                replaced = prior
            }
            bySession[sessionId] = Binding(sessionId, nodeId, ui)
            byNode[nodeId] = sessionId
            true
        }
        if (!accepted) {
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_INVALID))
            return
        }
        replaced?.let { prior ->
            prior.ui.safeSend(hangup(prior.sessionId, OaaWebRtc.REASON_REPLACED))
            node.send(hangup(prior.sessionId, OaaWebRtc.REASON_REPLACED))
        }
        val forwarded = JSONObject(payload.toString())
            .put("iceServers", iceServersJson("node:$nodeId"))
        val ok = runCatching { node.send(OaaFrames.frame(OaaWebRtc.OFFER, forwarded)) }.getOrDefault(false)
        if (!ok) {
            unbind(sessionId)
            ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_UNSUPPORTED))
        }
    }

    suspend fun onNodeFrame(nodeId: String, text: String) {
        val json = OaaFrames.parse(text) ?: return
        val type = json.optString("type")
        if (type !in OaaWebRtc.SIGNAL_TYPES) return
        val payload = json.optJSONObject("payload") ?: return
        val sessionId = payload.optString("sessionId").takeIf { it.isNotEmpty() } ?: return
        val binding = synchronized(lock) {
            bySession[sessionId]?.takeIf { it.nodeId == nodeId }
        } ?: return
        if (!OaaFrames.isCurrentVersion(payload)) {
            unbind(sessionId)
            registry.session(nodeId)?.send(hangup(sessionId, OaaWebRtc.REASON_VERSION))
            binding.ui.safeSend(hangup(sessionId, OaaWebRtc.REASON_VERSION))
            return
        }
        when (type) {
            OaaWebRtc.ANSWER -> binding.ui.safeSend(OaaFrames.frame(type, payload))
            OaaWebRtc.ICE -> {
                if (binding.nodeIce.incrementAndGet() > OaaWebRtc.MAX_ICE_PER_SESSION) return
                binding.ui.safeSend(OaaFrames.frame(type, payload))
            }
            OaaWebRtc.HANGUP -> {
                unbind(sessionId)
                binding.ui.safeSend(OaaFrames.frame(type, payload))
            }
            else -> Unit
        }
    }

    /** Viewer socket closed: tear down its sessions on the car. */
    suspend fun onUiClosed(ui: SignalPeer) {
        val gone = synchronized(lock) {
            val mine = bySession.values.filter { it.ui === ui }
            mine.forEach { removeLocked(it.sessionId) }
            mine
        }
        gone.forEach { b ->
            registry.session(b.nodeId)?.let { node ->
                runCatching { node.send(hangup(b.sessionId, OaaWebRtc.REASON_BYE)) }
            }
        }
    }

    /** Car went offline (no replacement session): tell the viewer. */
    suspend fun onNodeDetached(nodeId: String) {
        val b = synchronized(lock) {
            val sid = byNode[nodeId] ?: return
            bySession[sid].also { removeLocked(sid) }
        } ?: return
        b.ui.safeSend(hangup(b.sessionId, OaaWebRtc.REASON_OFFLINE))
    }

    private fun iceServersJson(userId: String): JSONArray {
        val arr = JSONArray()
        ice.iceServers(userId).forEach { arr.put(JSONObject(it)) }
        return arr
    }

    private fun boundTo(sessionId: String, nodeId: String, ui: SignalPeer): Binding? =
        synchronized(lock) {
            bySession[sessionId]?.takeIf { it.nodeId == nodeId && it.ui === ui }
        }

    private fun unbind(sessionId: String) {
        synchronized(lock) { removeLocked(sessionId) }
    }

    private fun removeLocked(sessionId: String) {
        val b = bySession.remove(sessionId) ?: return
        if (byNode[b.nodeId] == sessionId) byNode.remove(b.nodeId)
    }

    private suspend fun forwardToNode(nodeId: String, type: String, payload: JSONObject) {
        registry.session(nodeId)?.let { runCatching { it.send(OaaFrames.frame(type, payload)) } }
    }

    private suspend fun SignalPeer.safeSend(text: String) {
        runCatching { send(text) }
    }

    companion object {
        /** SDP offers are a few KB; anything much larger is abuse. */
        const val MAX_FRAME_CHARS = 64 * 1024
    }
}
