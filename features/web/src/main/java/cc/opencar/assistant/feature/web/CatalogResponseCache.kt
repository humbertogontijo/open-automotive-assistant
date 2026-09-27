package cc.opencar.assistant.feature.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Short TTL coalescing for the catalog behind /api/entities, /api/controls and
 * /api/history. Overlapping builds of the full catalog (VHAL diagnose × N)
 * peg DefaultDispatcher and GC. [WebEventHub.emitCatalog] invalidates it.
 */
internal object CatalogResponseCache {
    private const val TTL_MS = 1_500L
    private val mutex = Mutex()

    @Volatile private var at = 0L
    @Volatile private var value: ControlCatalog.Built? = null

    fun invalidate() {
        value = null
        at = 0L
    }

    suspend fun get(build: suspend () -> ControlCatalog.Built): ControlCatalog.Built {
        value?.let { if (System.currentTimeMillis() - at < TTL_MS) return it }
        return mutex.withLock {
            value?.let { if (System.currentTimeMillis() - at < TTL_MS) return it }
            build().also {
                value = it
                at = System.currentTimeMillis()
            }
        }
    }
}
