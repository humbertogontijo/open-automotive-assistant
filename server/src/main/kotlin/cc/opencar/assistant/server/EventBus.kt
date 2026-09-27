package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaUiEvents
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Node → hub UI fan-out of `/api/events` messages; one car per client. */
class EventBus {
    class UiClient(val nodeId: String?, private val session: WebSocketSession) {
        private val mutex = Mutex()

        suspend fun send(text: String) {
            runCatching { mutex.withLock { session.send(Frame.Text(text)) } }
        }
    }

    private val clients = CopyOnWriteArrayList<UiClient>()
    /** Last telemetry per node so a new viewer paints immediately. */
    private val lastTelemetry = ConcurrentHashMap<String, String>()

    fun attach(client: UiClient) {
        clients.add(client)
    }

    fun detach(client: UiClient) {
        clients.remove(client)
    }

    suspend fun publish(nodeId: String, message: JSONObject) {
        val text = message.toString()
        if (message.optString("t") == OaaUiEvents.TELEMETRY) lastTelemetry[nodeId] = text
        for (c in clients) {
            if (c.nodeId == nodeId) c.send(text)
        }
    }

    suspend fun replay(client: UiClient) {
        val id = client.nodeId ?: return
        lastTelemetry[id]?.let { client.send(it) }
    }
}
