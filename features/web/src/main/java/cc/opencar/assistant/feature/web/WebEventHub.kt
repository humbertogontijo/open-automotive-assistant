package cc.opencar.assistant.feature.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fan-out bus for `/api/events` WebSocket clients (catalog invalidation +
 * optional targeted entity deltas after writes).
 *
 * Composites use catalog invalidation only — never patch product `value` with
 * binding attr-raw (see [EntityContract.UPDATE_CATALOG]).
 */
internal object WebEventHub {
    private const val CATALOG_COALESCE_MS = 300L

    private val _bus = MutableSharedFlow<Map<String, Any?>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val bus: SharedFlow<Map<String, Any?>> = _bus.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalogPending = AtomicBoolean(false)
    @Volatile private var catalogReason = ""

    /**
     * Drops the cached catalog and tells clients to refetch. A burst (many VHAL props
     * behind one composite) becomes one trailing event, sent after the last change.
     */
    fun emitCatalog(reason: String) {
        CatalogResponseCache.invalidate()
        catalogReason = reason
        if (!catalogPending.compareAndSet(false, true)) return
        scope.launch {
            delay(CATALOG_COALESCE_MS)
            catalogPending.set(false)
            CatalogResponseCache.invalidate()
            _bus.tryEmit(mapOf("t" to "catalog", "reason" to catalogReason))
        }
    }

    fun emitEntity(id: String, value: Any?, status: String? = null) {
        val msg = mutableMapOf<String, Any?>("t" to "entity", "id" to id, "value" to value)
        if (status != null) msg["status"] = status
        _bus.tryEmit(msg)
    }
}
