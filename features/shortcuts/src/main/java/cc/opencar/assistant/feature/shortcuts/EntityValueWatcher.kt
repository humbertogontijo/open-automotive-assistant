package cc.opencar.assistant.feature.shortcuts

import android.util.Log
import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.api.VehicleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Watches bound entity values for [ShortcutTrigger.EntityState] / conditions.
 *
 * When [events] is available (reactive session), seeds once then relies on
 * [VehicleEvent.EntityValueChanged] only — no backup poll that would hide
 * missing stream edges. Without a session event flow, falls back to polling.
 */
class EntityValueWatcher(
    private val store: ShortcutStore,
    private val readEntity: suspend (String) -> String?,
    private val onChanged: (entityId: String, value: String?) -> Unit,
    private val events: Flow<VehicleEvent>? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private var job: Job? = null
    private val last = mutableMapOf<String, String?>()
    @Volatile private var watched: Set<String> = emptySet()

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                combine(store.shortcuts, store.routines, ::watchedIds).collect { watched = it }
            }
            val eventsFlow = events
            // Baseline so the first edge is a real change, not a boot false-positive.
            pollOnce()
            if (eventsFlow != null) {
                Log.i(TAG, "entity watch: observe (push) — no continuous poll")
                eventsFlow.collect { ev ->
                    if (ev !is VehicleEvent.EntityValueChanged) return@collect
                    onSessionEntity(ev.entityId, ev.value)
                }
            } else {
                Log.i(TAG, "entity watch: poll mode (${POLL_MS}ms) — no session events")
                while (coroutineContext.isActive) {
                    pollOnce()
                    delay(POLL_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollOnce() {
        val ids = watchedIds(store.list(), store.listRoutines())
        watched = ids
        for (id in ids) {
            val value = runCatching { readEntity(id) }.getOrNull()
            applyChange(id, value)
        }
    }

    /**
     * Session events use platform binding keys (`hvac_power`). For composites,
     * re-read the product id so watchers see HVAC mode, not attr-raw.
     */
    private suspend fun onSessionEntity(entityId: String, value: String?) {
        val ids = watched
        val product = EntityRegistry.resolveBinding(entityId)
        if (product != null && product.isComposite) {
            if (product.id in ids) {
                applyChange(product.id, runCatching { readEntity(product.id) }.getOrNull())
            }
            if (entityId in ids) applyChange(entityId, value)
            return
        }
        if (ids.isEmpty() || entityId in ids) {
            applyChange(entityId, value)
        }
    }

    private fun applyChange(entityId: String, value: String?) {
        if (!last.containsKey(entityId)) {
            last[entityId] = value
            return
        }
        if (last[entityId] != value) {
            last[entityId] = value
            Log.i(TAG, "entity $entityId -> $value")
            onChanged(entityId, value)
        }
    }

    private fun watchedIds(shortcuts: List<Shortcut>, routines: List<Routine>): Set<String> {
        val ids = linkedSetOf<String>()
        for (s in shortcuts.filter { it.enabled }) {
            for (t in s.triggers) {
                if (t is ShortcutTrigger.EntityState) ids += t.entityId
            }
            for (c in s.conditions) {
                if (c is ShortcutCondition.EntityEquals) ids += c.entityId
            }
        }
        for (r in routines.filter { it.enabled }) {
            for (c in r.conditions) {
                if (c is ShortcutCondition.EntityEquals) ids += c.entityId
            }
        }
        return ids
    }

    companion object {
        private const val TAG = "EntityValueWatcher"
        private const val POLL_MS = 2_000L
    }
}
