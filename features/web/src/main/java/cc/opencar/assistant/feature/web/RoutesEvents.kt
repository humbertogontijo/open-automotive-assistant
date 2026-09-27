package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaUiEvents
import com.google.gson.Gson
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Event-driven UI channel: telemetry + entity deltas + catalog invalidate.
 * Client still bootstraps once via HTTP [refresh]; this replaces soft-poll.
 */
internal fun Routing.registerEventRoutes(deps: OaaWebDeps) {
    val gson = Gson()

    webSocket(OaaPaths.EVENTS) {
        try {
            send(Frame.Text(gson.toJson(mapOf("t" to OaaUiEvents.HELLO))))
            val eventsJob = launch {
                uiEvents(deps.session, deps.dvr).collect { send(Frame.Text(gson.toJson(it))) }
            }
            val pingJob = launch {
                while (isActive) {
                    delay(25_000)
                    send(Frame.Text(gson.toJson(mapOf("t" to OaaUiEvents.PING))))
                }
            }
            // Hold the socket open until the client disconnects.
            while (isActive) {
                incoming.receiveCatching().getOrNull() ?: break
            }
            eventsJob.cancel()
            pingJob.cancel()
        } catch (_: ClosedSendChannelException) {
            // client gone
        } catch (_: Throwable) {
            // best-effort
        }
    }
}
