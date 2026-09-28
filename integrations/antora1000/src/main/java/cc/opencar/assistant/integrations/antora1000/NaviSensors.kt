package cc.opencar.assistant.integrations.antora1000

import cc.opencar.assistant.api.PropertyValue

/**
 * Read-only `navi_*` sensors derived from the `VENDOR_NAVI_*` BYTES props, which a navigation
 * publisher (e.g. Waze on HUD via hudcontrolhost) writes to VenusVehicleServer. The publisher
 * writes an idle record (status 0) when the route ends; the ETA string survives that record,
 * so every value except [ACTIVE] is withheld while idle.
 */
internal object NaviSensors {
    const val TBT_SOURCE = "VENDOR_NAVI_TBT_INFO"
    const val ETA_SOURCE = "VENDOR_NAVI_ETA_INFO"

    const val ACTIVE = "navi_active"
    const val ETA_MIN = "navi_eta_min"
    const val ETA_DISTANCE = "navi_eta_distance"
    const val NEXT_TURN_DISTANCE = "navi_next_turn_distance"
    const val ROAD_NAME = "navi_road_name"

    /** Derived key → source prop key. */
    val SOURCES: Map<String, String> = mapOf(
        ACTIVE to TBT_SOURCE,
        NEXT_TURN_DISTANCE to TBT_SOURCE,
        ROAD_NAME to TBT_SOURCE,
        ETA_MIN to ETA_SOURCE,
        ETA_DISTANCE to ETA_SOURCE,
    )

    fun values(tbt: NaviProto.Tbt?, eta: NaviProto.Eta?): Map<String, PropertyValue?> {
        val active = tbt?.active == true
        return mapOf(
            ACTIVE to tbt?.let { PropertyValue.IntVal(if (active) 1 else 0) },
            NEXT_TURN_DISTANCE to tbt?.takeIf { active }?.distanceMeters?.let { PropertyValue.IntVal(it) },
            ROAD_NAME to tbt?.takeIf { active }?.roadName?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { PropertyValue.StringVal(it) },
            ETA_MIN to eta?.takeIf { active }?.timeMin?.let { PropertyValue.IntVal(it) },
            ETA_DISTANCE to eta?.takeIf { active }?.distanceMeters
                ?.let { PropertyValue.FloatVal(Math.round(it / 100f) / 10f) },
        )
    }
}
