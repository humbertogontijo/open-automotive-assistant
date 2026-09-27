package cc.opencar.assistant.integrations.ihu629g

import android.content.Context
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.PropertyValue
import cc.opencar.assistant.api.TelemetrySnapshot
import cc.opencar.assistant.api.VehicleProperty
import cc.opencar.assistant.integrations.aaos.AaosSessionBase
import cc.opencar.assistant.integrations.aaos.AospVehicleIds
import cc.opencar.assistant.integrations.aaos.CarPropertyBackend
import cc.opencar.assistant.integrations.aaos.PlatformConfig
import cc.opencar.assistant.integrations.aaos.toPropertyValue

/**
 * EX2 / IHU629G session — VHAL via [CarPropertyBackend].
 * No VenusVehicleServer on this HU.
 */
class Ihu629gSession(
    context: Context,
    override val platform: PlatformConfig,
    initialVariant: PlatformVariant,
) : AaosSessionBase(context, CarPropertyBackend(context), initialVariant, TAG) {
    override val catalogEntries: List<CatalogEntry> = platform.catalogEntries()
    override val allowlist: Set<Int> = platform.writableAllowlist
    override val integrationId: String = Ihu629gIntegration.ID

    init {
        val propIds = platform.bindings.values.map { it.nativeId }.distinct().toIntArray()
        start(backend.observe(propIds.takeIf { it.isNotEmpty() }))
    }

    override fun decode(property: VehicleProperty, raw: Any?): PropertyValue? {
        if (raw == null) return null
        when (property.key) {
            "HVAC_TEMPERATURE_SET" -> {
                val f = (raw as? Number)?.toFloat() ?: return toPropertyValue(raw)
                return PropertyValue.FloatVal(Ihu629gCodecs.tempRawToC(f))
            }
            "charge_plug" -> {
                val i = (raw as? Number)?.toInt() ?: return toPropertyValue(raw)
                return PropertyValue.IntVal(if (Ihu629gCodecs.plugConnected(i) == true) 1 else 0)
            }
            "parking_comfort" -> {
                val i = (raw as? Number)?.toInt() ?: return toPropertyValue(raw)
                return PropertyValue.IntVal(if (Ihu629gCodecs.parkModeIsOn(i)) 1 else 0)
            }
        }
        return toPropertyValue(raw)
    }

    override fun encode(property: VehicleProperty, value: PropertyValue): PropertyValue {
        when (property.key) {
            "HVAC_TEMPERATURE_SET" -> {
                val c = when (value) {
                    is PropertyValue.FloatVal -> value.value
                    is PropertyValue.IntVal -> value.value.toFloat()
                    else -> return value
                }
                return PropertyValue.FloatVal(Ihu629gCodecs.tempCToRaw(c))
            }
            "parking_comfort" -> {
                val on = when (value) {
                    is PropertyValue.BoolVal -> value.value
                    is PropertyValue.IntVal -> value.value != 0
                    else -> return value
                }
                return PropertyValue.IntVal(Ihu629gCodecs.parkModeOn(on))
            }
        }
        return value
    }

    override fun readSnapshot(): TelemetrySnapshot {
        fun intOf(key: String): Int? {
            val b = platform.bindings[key] ?: return null
            return (backend.read(b.nativeId, b.areaId) as? Number)?.toInt()
        }
        fun floatOf(key: String): Float? {
            val b = platform.bindings[key] ?: return null
            val raw = backend.read(b.nativeId, b.areaId) as? Number ?: return null
            return when (key) {
                "HVAC_TEMPERATURE_SET" -> Ihu629gCodecs.tempRawToC(raw.toFloat())
                "PERF_VEHICLE_SPEED" -> AospVehicleIds.speedMsToKmh(raw.toFloat())
                else -> raw.toFloat()
            }
        }
        val mode = intOf("drive_mode")
        val plug = intOf("charge_plug")
        return TelemetrySnapshot(
            gear = intOf("CURRENT_GEAR"),
            speedKmh = floatOf("PERF_VEHICLE_SPEED"),
            evBatteryPercent = floatOf("ev_battery_percent"),
            rangeKm = floatOf("range_km"),
            driveMode = mode?.let { platform.driveModeEnum[it] ?: "mode:$it" },
            regenLevel = intOf("regen"),
            ignitionState = intOf("IGNITION_STATE"),
            hvacPower = intOf("HVAC_POWER_ON")?.let { it != 0 && it != 2 },
            hvacTempC = floatOf("HVAC_TEMPERATURE_SET"),
            hvacFan = intOf("HVAC_FAN_SPEED"),
            chargeCurrentA = floatOf("charge_current"),
            chargePlugConnected = Ihu629gCodecs.plugConnected(plug),
            extras = buildMap {
                put("bridge", if (backend.available) "ok" else "unavailable")
                put("backend", "vhal")
                put("accessMode", backend.mode.wireName)
                put("family", "flyme")
                if (mode != null) put("driveModeRaw", mode.toString())
            },
        )
    }

    companion object {
        private const val TAG = "Ihu629gSession"
    }
}
