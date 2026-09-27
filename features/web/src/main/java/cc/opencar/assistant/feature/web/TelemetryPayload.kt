package cc.opencar.assistant.feature.web

import cc.opencar.assistant.api.TelemetrySnapshot

/** Shared shape for `/api/status` telemetry and `/api/events` telemetry frames. */
internal fun telemetryPayload(snap: TelemetrySnapshot): Map<String, Any?> = mapOf(
    "gear" to snap.gear,
    "speedKmh" to snap.speedKmh,
    "evBatteryPercent" to snap.evBatteryPercent,
    "hybridSocPercent" to snap.hybridSocPercent,
    "rangeKm" to snap.rangeKm,
    "driveMode" to snap.driveMode,
    "regenLevel" to snap.regenLevel,
    "hvacPower" to snap.hvacPower,
    "hvacTempC" to snap.hvacTempC,
    "hvacFan" to snap.hvacFan,
    "chargeCurrentA" to snap.chargeCurrentA,
    "chargePlugConnected" to snap.chargePlugConnected,
    "ignitionState" to snap.ignitionState,
    "extras" to snap.extras,
)
