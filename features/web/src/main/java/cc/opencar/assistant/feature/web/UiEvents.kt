package cc.opencar.assistant.feature.web

import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.api.VehicleEvent
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.protocol.OaaUiEvents
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample

/**
 * The `t`-shaped messages of `/api/events` (telemetry, entity deltas, catalog
 * invalidation), shared by the local socket and the hub session.
 *
 * Composite binding-key edges never become `entity` deltas — catalog
 * invalidation only ([EntityContract.UPDATE_CATALOG]).
 */
@OptIn(FlowPreview::class)
internal fun uiEvents(
    session: VehicleSession,
    dvr: DvrController?,
    telemetrySampleMs: Long = 0,
): Flow<Map<String, Any?>> {
    val telemetry = session.telemetry().distinctUntilChanged()
        .let { if (telemetrySampleMs > 0) it.sample(telemetrySampleMs) else it }
        .map { snap ->
            mapOf(
                "t" to OaaUiEvents.TELEMETRY,
                "telemetry" to telemetryPayload(snap),
                "dvr" to dvr?.status(),
            )
        }
    val entities = session.events().mapNotNull { ev ->
        if (ev !is VehicleEvent.EntityValueChanged) return@mapNotNull null
        val product = EntityRegistry.resolveBinding(ev.entityId)
        if (product != null && product.isComposite) {
            WebEventHub.emitCatalog("composite_attr")
            null
        } else {
            mapOf("t" to OaaUiEvents.ENTITY, "id" to ev.entityId, "value" to ev.value)
        }
    }
    return merge(telemetry, entities, WebEventHub.bus)
}
