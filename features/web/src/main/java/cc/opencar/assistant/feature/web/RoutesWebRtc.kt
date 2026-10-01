package cc.opencar.assistant.feature.web

import cc.opencar.assistant.feature.web.webrtc.CarWebRtc
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import cc.opencar.assistant.protocol.OaaWebRtc
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Local WebRTC signaling (ADR-0003): the same frames as the hub relay, answered by
 * the car itself, so a browser on the LAN or the head unit gets the same media
 * plane as a remote one. No TURN on the LAN.
 */
internal fun Routing.registerWebRtcRoutes(deps: OaaWebDeps) {
    get(OaaPaths.WEBRTC_ICE) {
        call.respond(
            mapOf(
                "v" to OaaFrames.VERSION,
                "iceServers" to emptyList<Any>(),
                "ttlSec" to 0,
                "turn" to false,
            ),
        )
    }

    webSocket(OaaPaths.WEBRTC_SIGNAL) {
        val rtc = deps.webrtc
        // Car replies come from WebRTC threads; one writer keeps them ordered on the socket.
        val out = Channel<String>(OUTBOX)
        val sink = CarWebRtc.SignalSink { text -> out.trySend(text).isSuccess }
        val writer = launch {
            for (text in out) send(Frame.Text(text))
        }
        try {
            val hello = OaaFrames.versioned()
                .put("role", OaaRoles.LOCAL)
                .put("online", true)
            out.trySend(OaaFrames.frame(OaaFrames.HELLO, hello))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text.length > MAX_SIGNAL_CHARS) continue
                val json = OaaFrames.parse(text) ?: continue
                when (val type = json.optString("type")) {
                    OaaFrames.PING -> out.trySend(OaaFrames.frame(OaaFrames.PONG))
                    in OaaWebRtc.SIGNAL_TYPES -> if (type != OaaWebRtc.ANSWER) {
                        rtc.onSignal(json, CarWebRtc.Origin.LOCAL, sink)
                    }
                }
            }
        } finally {
            rtc.onSinkClosed(sink)
            out.close()
            writer.cancel()
        }
    }
}

private const val OUTBOX = 256
/** SDP offers are a few KB; matches the hub relay's cap. */
private const val MAX_SIGNAL_CHARS = 64 * 1024
