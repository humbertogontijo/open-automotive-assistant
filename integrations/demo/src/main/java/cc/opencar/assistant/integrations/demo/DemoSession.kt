package cc.opencar.assistant.integrations.demo

import cc.opencar.assistant.api.AndroidVolumeGroup
import cc.opencar.assistant.api.CameraSource
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.PropertyValue
import cc.opencar.assistant.api.TelemetrySnapshot
import cc.opencar.assistant.api.ValueRange
import cc.opencar.assistant.api.VehicleEvent
import cc.opencar.assistant.api.VehicleProperty
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.integrations.aaos.PlatformConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [VehicleSession] seeded with plausible cabin / energy values.
 */
class DemoSession(
    private val platform: PlatformConfig,
    initialVariant: PlatformVariant,
) : VehicleSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val values = ConcurrentHashMap<String, PropertyValue>()

    private val _variant = MutableStateFlow(initialVariant)
    override val variant: StateFlow<PlatformVariant> = _variant.asStateFlow()
    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)

    override val integrationId: String = DemoIntegration.ID

    init {
        seedValues()
        _telemetry.value = seedTelemetry()
        scope.launch { _events.emit(VehicleEvent.Boot) }
    }

    override fun telemetry(): Flow<TelemetrySnapshot> = _telemetry.asStateFlow()

    override fun events(): Flow<VehicleEvent> = _events.asSharedFlow()

    override suspend fun get(property: VehicleProperty): PropertyValue? =
        values[property.key]

    override suspend fun set(property: VehicleProperty, value: PropertyValue): Result<Unit> {
        val binding = platform.bindings[property.key]
            ?: return Result.failure(IllegalArgumentException("unbound: ${property.key}"))
        if (binding.nativeId !in platform.writableAllowlist) {
            return Result.failure(SecurityException("not writable: ${property.key}"))
        }
        values[property.key] = value
        _telemetry.value = seedTelemetry()
        scope.launch {
            _events.emit(VehicleEvent.EntityValueChanged(property.key, value.display()))
        }
        return Result.success(Unit)
    }

    override fun catalog(): List<CatalogEntry> = platform.catalogEntries()

    override fun cameras(): List<CameraSource> = emptyList()

    override fun entityBindings(): Map<Long, String> =
        platform.bindings.entries.associate { (entityId, b) ->
            b.nativeId.toLong() to entityId
        }

    override fun hasBinding(property: VehicleProperty): Boolean =
        platform.bindings.containsKey(property.key)

    override fun valueRange(property: VehicleProperty): ValueRange? = platform.ranges[property.key]

    override fun androidVolumeGroups(): List<AndroidVolumeGroup> =
        platform.androidVolumeGroups()

    override fun close() {
        scope.cancel()
    }

    private fun seedValues() {
        values["PERF_VEHICLE_SPEED"] = PropertyValue.FloatVal(42f)
        values["GEAR_SELECTION"] = PropertyValue.IntVal(4) // Park-ish demo value
        values["EV_BATTERY_LEVEL"] = PropertyValue.FloatVal(78f)
        values["RANGE_REMAINING"] = PropertyValue.FloatVal(320f)
        values["HVAC_POWER_ON"] = PropertyValue.BoolVal(true)
        values["HVAC_TEMPERATURE_SET"] = PropertyValue.FloatVal(22f)
        values["HVAC_FAN_SPEED"] = PropertyValue.IntVal(3)
        values["HVAC_AC_ON"] = PropertyValue.BoolVal(true)
        values["HVAC_AUTO_ON"] = PropertyValue.BoolVal(false)
        values["HVAC_RECIRC_ON"] = PropertyValue.BoolVal(false)
    }

    private fun seedTelemetry(): TelemetrySnapshot = TelemetrySnapshot(
        gear = values["GEAR_SELECTION"]?.asInt(),
        speedKmh = values["PERF_VEHICLE_SPEED"]?.asFloat(),
        evBatteryPercent = values["EV_BATTERY_LEVEL"]?.asFloat(),
        rangeKm = values["RANGE_REMAINING"]?.asFloat(),
        hvacPower = (values["HVAC_POWER_ON"] as? PropertyValue.BoolVal)?.value,
        hvacTempC = values["HVAC_TEMPERATURE_SET"]?.asFloat(),
        hvacFan = values["HVAC_FAN_SPEED"]?.asInt(),
        ignitionState = 2,
        extras = mapOf("model" to "Open Automotive Assistant Demo"),
    )
}
