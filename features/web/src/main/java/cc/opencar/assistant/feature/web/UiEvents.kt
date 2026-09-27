package cc.opencar.assistant.feature.web

import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.api.VehicleEvent
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.protocol.OaaUiEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample

private const val DVR_SUMMARY_MS = 5_000L

/**
 * The `t`-shaped messages of `/api/events` (telemetry, entity deltas, catalog
 * invalidation, DVR summary), shared by the local socket and the hub session.
 *
 * Composite binding-key edges never become `entity` deltas — catalog
 * invalidation only ([EntityContract.UPDATE_CATALOG]).
 */
@OptIn(FlowPreview::class)
internal fun uiEvents(
    session: VehicleSession,
    dvr: DvrController?,
    telemetrySampleMs: Long = 1_000,
): Flow<Map<String, Any?>> {
    val telemetry = session.telemetry().distinctUntilChanged()
        .sample(telemetrySampleMs)
        .map { snap -> mapOf("t" to OaaUiEvents.TELEMETRY, "telemetry" to telemetryPayload(snap)) }
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
    return merge(telemetry, entities, dvrSummaries(dvr), WebEventHub.bus)
}

/** DVR summary changes; the first value is skipped because clients read it from `/api/status`. */
private fun dvrSummaries(dvr: DvrController?): Flow<Map<String, Any?>> {
    if (dvr == null) return emptyFlow()
    return flow {
        while (true) {
            emit(dvr.summary())
            delay(DVR_SUMMARY_MS)
        }
    }.flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .drop(1)
        .map { mapOf("t" to OaaUiEvents.DVR, "dvr" to it) }
}
