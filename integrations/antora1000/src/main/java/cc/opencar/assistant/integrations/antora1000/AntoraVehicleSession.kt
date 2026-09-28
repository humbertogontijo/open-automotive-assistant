package cc.opencar.assistant.integrations.antora1000

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.PropertyValue
import cc.opencar.assistant.api.ReadOutcome
import cc.opencar.assistant.api.TelemetrySnapshot
import cc.opencar.assistant.api.VehicleEvent
import cc.opencar.assistant.api.VehicleProperty
import cc.opencar.assistant.integrations.aaos.AaosSessionBase
import cc.opencar.assistant.integrations.aaos.AospVehicleIds
import cc.opencar.assistant.integrations.aaos.PlatformConfig
import cc.opencar.assistant.integrations.aaos.PropertyAccessMode
import cc.opencar.assistant.integrations.aaos.PropertyUpdate
import cc.opencar.assistant.integrations.aaos.toPropertyValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class AntoraVehicleSession(
    context: Context,
    initialVariant: PlatformVariant,
) : AaosSessionBase(context, AntoraBackendFactory.create(context), initialVariant, TAG) {
    private val basePlatform: PlatformConfig = AntoraCatalog.platformConfig(context)
    override var platform: PlatformConfig = basePlatform.forSelection(initialVariant.skuId, initialVariant.id)
        private set
    override val catalogEntries: List<CatalogEntry> = basePlatform.catalogEntries()
    override val allowlist: Set<Int> = basePlatform.writableAllowlist
    override val integrationId: String = Antora1000Integration.ID

    private val propsByKey: Map<String, PlatformConfig.PropertyDef> = basePlatform.properties.associateBy { it.key }

    /** SWC hard keys from platform.json (`WHEEL_HARD_KEY_*`). */
    private val wheelHardKeys: Map<String, Int> = basePlatform.properties
        .filter { it.key.startsWith("WHEEL_HARD_KEY_") }
        .associate { p -> p.key.removePrefix("WHEEL_HARD_KEY_").lowercase() to p.id }

    private val naviTbtId: Int? = propsByKey[NaviSensors.TBT_SOURCE]?.id
    private val naviEtaId: Int? = propsByKey[NaviSensors.ETA_SOURCE]?.id
    private val naviLastEmitted = HashMap<String, String?>()

    val accessMode: PropertyAccessMode get() = backend.mode

    init {
        val propIds = platform.bindings.values.map { it.nativeId }.distinct().toIntArray()
        start(
            when (backend.mode) {
                PropertyAccessMode.GRPC -> backend.observe(null)
                PropertyAccessMode.CAR_PROPERTY -> backend.observe(propIds.takeIf { it.isNotEmpty() })
            },
        )
    }

    fun updateVariant(variant: PlatformVariant) {
        platform = basePlatform.forSelection(variant.skuId, variant.id)
        refreshLookups()
        setVariant(variant)
    }

    /**
     * Power liftgate only. Opening is refused above 5 km/h; closing is always allowed.
     * A missing speed reading does not block the write.
     */
    override suspend fun set(property: VehicleProperty, value: PropertyValue): Result<Unit> {
        if (property.key == "DOOR_MOVE" && value.asInt()?.let { it != 0 } == true) {
            val speed = readCatalog("PERF_VEHICLE_SPEED")?.asFloat()?.let { AospVehicleIds.speedMsToKmh(it) }
            if (speed != null && speed > TRUNK_OPEN_MAX_KMH) {
                Log.w(TAG, "refusing trunk open at ${speed} km/h")
                return Result.failure(IllegalStateException("Refusing to open the trunk above 5 km/h"))
            }
        }
        return super.set(property, value)
    }

    override fun extraTelemetryPropIds(): Collection<Int> = EXTRA_TELEMETRY_KEYS.mapNotNull { catalogNativeId(it) }

    override fun diagnoseAreas(property: VehicleProperty, boundArea: Int, areaId: Int?): List<Int> {
        if (areaId != null) return listOf(areaId)
        val fromCatalog = catalogEntries.firstOrNull {
            it.property.key == property.key || it.name == property.key ||
                it.property.nativeId == property.nativeId
        }?.areaIds
        return (fromCatalog ?: listOf(boundArea)).distinct()
    }

    override fun resolveUnbound(property: VehicleProperty): Pair<Int, Int>? {
        property.nativeId?.toInt()?.let { return it to property.defaultAreaId }
        val fromCatalog = catalogEntries.firstOrNull {
            it.property.key == property.key || it.name == property.key
        } ?: return null
        val id = fromCatalog.property.nativeId?.toInt() ?: return null
        val preferred = property.defaultAreaId
        return id to if (preferred != 0) preferred else fromCatalog.areaIds.firstOrNull() ?: 0
    }

    override fun onObserve(observe: Flow<PropertyUpdate>) {
        scope.launch { observeWheelKeys(observe) }
        scope.launch { observeNavi(observe) }
    }

    override fun onPollMode() {
        scope.launch { pollWheelKeys() }
        scope.launch { pollNavi() }
    }

    override fun hasBinding(property: VehicleProperty): Boolean {
        val source = NaviSensors.SOURCES[property.key] ?: return super.hasBinding(property)
        return propsByKey.containsKey(source)
    }

    override suspend fun get(property: VehicleProperty): PropertyValue? {
        if (property.key !in NaviSensors.SOURCES) return super.get(property)
        return naviValues()[property.key]
    }

    override suspend fun diagnose(property: VehicleProperty, areaId: Int?): ReadOutcome {
        if (property.key !in NaviSensors.SOURCES) return super.diagnose(property, areaId)
        if (!hasBinding(property) || !backend.available) return ReadOutcome.Unavailable(0)
        val value = naviValues()[property.key] ?: return ReadOutcome.Unavailable(0)
        return ReadOutcome.Ok(value, 0)
    }

    private fun naviValues(): Map<String, PropertyValue?> {
        val tbt = (naviTbtId?.let { backend.read(it, 0) } as? ByteArray)?.let(NaviProto::decodeTbt)
        val eta = (naviEtaId?.let { backend.read(it, 0) } as? ByteArray)?.let(NaviProto::decodeEta)
        return NaviSensors.values(tbt, eta)
    }

    private suspend fun observeNavi(observe: Flow<PropertyUpdate>) {
        val ids = setOfNotNull(naviTbtId, naviEtaId)
        if (ids.isEmpty()) return
        publishNavi()
        observe.filter { it.propId in ids }.collect { publishNavi() }
    }

    private suspend fun pollNavi() {
        if (naviTbtId == null && naviEtaId == null) return
        while (coroutineContext.isActive) {
            publishNavi()
            pollDelay(AaosSessionBase.POLL_MS)
        }
    }

    /** Emits `navi_*` entity changes; unset keys start as null so the first non-null value is sent. */
    @Synchronized
    private fun publishNavi() {
        for ((key, value) in naviValues()) {
            val display = value?.display()
            if (naviLastEmitted[key] == display) continue
            naviLastEmitted[key] = display
            eventFlow.tryEmit(VehicleEvent.EntityValueChanged(key, display))
        }
    }

    private fun catalogNativeId(key: String): Int? = platform.bindings[key]?.nativeId ?: propsByKey[key]?.id

    private fun catalogArea(key: String): Int =
        platform.bindings[key]?.areaId ?: propsByKey[key]?.areas?.firstOrNull() ?: 0

    private fun readCatalog(key: String): PropertyValue? {
        val id = catalogNativeId(key) ?: return null
        return toPropertyValue(backend.read(id, catalogArea(key)))
    }

    /**
     * SWC hard-key edges from the property observe stream (reactive path).
     * Short press = release before [WHEEL_LONG_PRESS_MS]; long-press = timer while held.
     * If these props never appear on the stream, shortcuts will stay silent — intentional.
     */
    private suspend fun observeWheelKeys(observe: Flow<PropertyUpdate>) {
        val propToKey = wheelHardKeys.entries.associate { (k, id) -> id to k }
        val edges = WheelEdges()
        Log.i(TAG, "wheel keys: observe (push) mode props=${propToKey.size}")
        for ((key, propId) in wheelHardKeys) {
            edges.last[key] = wheelLevel(backend.read(propId, 0))
        }
        observe.filter { it.propId in propToKey }.collect { update ->
            val key = propToKey[update.propId] ?: return@collect
            val value = wheelLevel(update.value) ?: return@collect
            edges.apply(key, value)
        }
    }

    /** Poll SWC hard-key VHAL props when observe is unavailable (CarProperty fallback). */
    private suspend fun pollWheelKeys() {
        val edges = WheelEdges()
        Log.i(TAG, "wheel keys: poll mode (${WHEEL_POLL_MS}ms)")
        while (coroutineContext.isActive) {
            for ((key, propId) in wheelHardKeys) {
                wheelLevel(backend.read(propId, 0))?.let { edges.apply(key, it) }
            }
            pollDelay(WHEEL_POLL_MS)
        }
    }

    private inner class WheelEdges {
        val last = mutableMapOf<String, Int?>()
        private val longFired = mutableSetOf<String>()
        private val longJobs = mutableMapOf<String, Job>()

        fun apply(key: String, value: Int) {
            val prev = last[key]
            if (value != 0) {
                if (prev == null || prev == 0) {
                    longFired.remove(key)
                    longJobs.remove(key)?.cancel()
                    longJobs[key] = scope.launch {
                        delay(WHEEL_LONG_PRESS_MS)
                        if (key !in longFired) {
                            longFired.add(key)
                            Log.i(TAG, "wheel long-press key=$key")
                            eventFlow.tryEmit(VehicleEvent.WheelKeyLongPressed(key))
                        }
                    }
                }
            } else if (prev != null && prev != 0) {
                longJobs.remove(key)?.cancel()
                if (key !in longFired) {
                    Log.i(TAG, "wheel press key=$key")
                    eventFlow.tryEmit(VehicleEvent.WheelKeyPressed(key))
                }
                longFired.remove(key)
            }
            last[key] = value
        }
    }

    private fun wheelLevel(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is Boolean -> if (raw) 1 else 0
        else -> null
    }

    override fun readSnapshot(): TelemetrySnapshot {
        fun intProp(key: String): Int? {
            val b = platform.bindings[key] ?: return null
            return toPropertyValue(backend.read(b.nativeId, b.areaId))?.asInt()
        }

        fun floatProp(key: String): Float? {
            val b = platform.bindings[key] ?: return null
            return toPropertyValue(backend.read(b.nativeId, b.areaId))?.asFloat()
        }

        val speedMs = floatProp("PERF_VEHICLE_SPEED")
        val speedKmh = speedMs?.let { AospVehicleIds.speedMsToKmh(it) }
        // Prefer vendor display % (matches cluster). Fall back to Wh-level / capacity,
        // then hybrid SOC (charge-target band on EM-i — often ≠ dashboard %).
        val evPercentDirect = floatProp("TYPE_EV_BATTERY_PERCENTAGE")
        val evRaw = floatProp("EV_BATTERY_LEVEL")
        val battCap = readCatalog("INFO_EV_BATTERY_CAPACITY")?.asFloat()
        val evPercentFromWh = if (evRaw != null && battCap != null && battCap > 0f) {
            (evRaw / battCap) * 100f
        } else {
            null
        }
        val hybridSoc = floatProp("HYBRID_FUNC_BATTERY_SOC")
        val evPercent = evPercentDirect ?: evPercentFromWh ?: hybridSoc
        val rangeM = floatProp("RANGE_REMAINING")
        val driveModeRaw = intProp("DM_FUNC_DRIVE_MODE_SELECT")
        val pure = readCatalog("DRIVE_MODE_SELECTION_PURE")?.asInt()
        val hybrid = readCatalog("DRIVE_MODE_SELECTION_HYBRID")?.asInt()
        val power = readCatalog("DRIVE_MODE_SELECTION_POWER")?.asInt()
        val driveLabel = driveModeRaw?.let { platform.driveModeEnum[it] ?: "mode:$it" }
        val energyLabel = when {
            pure == 1 -> "opt.drive_mode.1"
            hybrid == 1 -> "opt.drive_mode.2"
            power == 1 -> "opt.drive_mode.3"
            else -> null
        }
        val plug = intProp("CHARGE_FUNC_CHARGING_PLUG_STATE")
        val hvacPower = readCatalog("HVAC_POWER_ON")?.asInt()?.let { it != 0 }
        val model = readCatalog("INFO_MODEL")?.display()
        val parkingLabel = when (val parkingBrake = readCatalog("PARKING_BRAKE_ON")) {
            is PropertyValue.BoolVal -> if (parkingBrake.value) "on" else "off"
            is PropertyValue.IntVal -> if (parkingBrake.value != 0) "on" else "off"
            else -> parkingBrake?.display()
        }
        val rangeKm = when {
            rangeM == null -> null
            rangeM >= 10_000f -> rangeM / 1000f
            else -> rangeM
        }

        return TelemetrySnapshot(
            gear = intProp("GEAR_SELECTION"),
            speedKmh = speedKmh,
            evBatteryPercent = evPercent,
            fuelCapacityMl = floatProp("INFO_FUEL_CAPACITY"),
            fuelPercent = floatProp("TYPE_FUEL_PERCENTAGE"),
            rangeKm = rangeKm,
            rangeEvKm = floatProp("SENSOR_TYPE_ENDURANCE_MILEAGE_EV"),
            rangeFuelKm = floatProp("SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL"),
            odometerKm = floatProp("PERF_ODOMETER"),
            hvacPower = hvacPower,
            hvacTempC = floatProp("HVAC_TEMPERATURE_SET"),
            hvacFan = intProp("HVAC_FAN_SPEED"),
            tempAmbientC = floatProp("SENSOR_TYPE_TEMPERATURE_AMBIENT"),
            tempIndoorC = floatProp("SENSOR_TYPE_TEMPERATURE_INDOOR"),
            batteryTempC = floatProp("SENSOR_TYPE_EV_BATTERY_TEMP"),
            hybridSocPercent = hybridSoc,
            chargeCurrentA = floatProp("CHARGE_FUNC_CHARGING_CURRENT"),
            chargePlugConnected = plug?.let { it != 0 },
            chargeEstimatedTimeMin = floatProp("CHARGE_FUNC_CHARGING_ESTIMATED_TIME")?.takeIf { it >= 0f },
            chargeEnergyKwh = floatProp("CHARGE_FUNC_CHARGING_ENERGY")?.takeIf { it >= 0f },
            chargeWorkCurrentA = floatProp("CHARGE_FUNC_CHARGING_WORK_CURRENT")?.takeIf { it >= 0f },
            chargeWorkVoltageV = floatProp("CHARGE_FUNC_CHARGING_WORK_VOLTAGE")?.takeIf { it >= 0f },
            dischargeSocPercent = floatProp("CHARGE_FUNC_DISCHARGING_SOC"),
            avgEnergyKwh100km = floatProp("TRIP_DI_AVG_ELC_CONSUMPTION"),
            avgFuelL100km = floatProp("TRIP_DI_AVG_FUEL_CONSUMPTION"),
            energyFlowDriving = floatProp("TRIP_ED_DRIVING_ENERGY_FLOW"),
            energyFlowBattery = floatProp("TRIP_ED_BATTERY_ENERGY_FLOW"),
            energyFlowClimate = floatProp("TRIP_ED_CLIMATE_ENERGY_FLOW"),
            maintenanceMileageKm = floatProp("TYPE_MAINTENANCE_MILEAGE"),
            sinceMaintenanceKm = floatProp("TYPE_SINCE_MAINTENANCE_TOTAL_MILEAGE"),
            driveMode = driveLabel,
            regenLevel = intProp("SETTING_FUNC_ENERGY_REGENERATION"),
            ignitionState = intProp("IGNITION_STATE"),
            extras = buildMap {
                put("bridge", if (backend.available) "ok" else "unavailable")
                put("accessMode", backend.mode.wireName)
                put("catalogSize", catalogEntries.size.toString())
                if (driveModeRaw != null) put("driveModeRaw", driveModeRaw.toString())
                if (model != null) put("model", model)
                if (parkingLabel != null) put("parkingBrake", parkingLabel)
                if (energyLabel != null) put("energyMode", energyLabel)
                put("energyPure", (pure == 1).toString())
                put("energyHybrid", (hybrid == 1).toString())
                put("energyPower", (power == 1).toString())
                battCap?.let { put("evBatteryCapacityWh", it.toString()) }
            },
        )
    }

    companion object {
        private const val TAG = "AntoraSession"
        private const val WHEEL_POLL_MS = 100L
        private const val WHEEL_LONG_PRESS_MS = 700L
        private const val TRUNK_OPEN_MAX_KMH = 5f

        /** Catalog keys read in [readSnapshot] that may sit outside the SKU allowlist. */
        private val EXTRA_TELEMETRY_KEYS = listOf(
            "INFO_EV_BATTERY_CAPACITY",
            "DRIVE_MODE_SELECTION_PURE",
            "DRIVE_MODE_SELECTION_HYBRID",
            "DRIVE_MODE_SELECTION_POWER",
            "HVAC_POWER_ON",
            "INFO_MODEL",
            "PARKING_BRAKE_ON",
        )
    }
}
