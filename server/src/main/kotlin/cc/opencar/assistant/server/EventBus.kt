package cc.opencar.assistant.server

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Node → hub UI fan-out of `/api/events` messages; one car per client. */
class EventBus {
    /** A UI socket with its own bounded queue, so one slow viewer never stalls the others. */
    class UiClient(val nodeId: String?, session: WebSocketSession) {
        private val queue = Channel<String>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)

        init {
            session.launch {
                for (text in queue) runCatching { session.send(Frame.Text(text)) }
            }
        }

        fun offer(text: String) {
            queue.trySend(text)
        }

        internal fun close() {
            queue.close()
        }
    }

    private val clientsByNode = ConcurrentHashMap<String, MutableSet<UiClient>>()

    fun attach(client: UiClient) {
        val id = client.nodeId ?: return
        clientsByNode.computeIfAbsent(id) { CopyOnWriteArraySet() }.add(client)
    }

    fun detach(client: UiClient) {
        client.close()
        val id = client.nodeId ?: return
        clientsByNode[id]?.remove(client)
    }

    /** [message] is a serialized event object, forwarded verbatim. */
    fun publish(nodeId: String, message: String) {
        clientsByNode[nodeId]?.forEach { it.offer(message) }
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
    }
}
