package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

/** Media plane signaling (ADR-0003): SDP/ICE relayed to the car; media never touches the hub. */
internal fun Routing.webRtcRoutes(hub: HubContext) = with(hub) {
    get(OaaPaths.WEBRTC_ICE) {
        val user = call.requireUser() ?: return@get
        call.respond(
            mapOf(
                "v" to OaaFrames.VERSION,
                "iceServers" to ice.iceServers(user.id),
                "ttlSec" to ice.ttlSec,
                "turn" to ice.turnEnabled,
            ),
        )
    }

    webSocket(OaaPaths.WEBRTC_SIGNAL) {
        if (call.currentUser() == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth required"))
            return@webSocket
        }
        val nodeId = call.nodeIdOrNull()
        if (nodeId == null || registry.get(nodeId) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown node"))
            return@webSocket
        }
        val peer = SignalPeer { text -> send(Frame.Text(text)) }
        try {
            val hello = OaaFrames.versioned()
                .put("role", OaaRoles.HUB)
                .put("nodeId", nodeId)
                .put("online", registry.isOnline(nodeId))
            send(Frame.Text(OaaFrames.frame(OaaFrames.HELLO, hello)))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (answerPing(text)) continue
                signalRelay.onUiFrame(nodeId, peer, text)
            }
        } finally {
            signalRelay.onUiClosed(peer)
        }
    }
}
