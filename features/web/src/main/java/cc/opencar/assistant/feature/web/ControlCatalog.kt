package cc.opencar.assistant.feature.web

import android.content.Context
import cc.opencar.assistant.api.CatalogEntityFactory
import cc.opencar.assistant.api.DeviceClass
import cc.opencar.assistant.api.DomainDefaults
import cc.opencar.assistant.api.EntityContract
import cc.opencar.assistant.api.EntityDef
import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.api.EntityType
import cc.opencar.assistant.api.PropertyValue
import cc.opencar.assistant.api.ReadOutcome
import cc.opencar.assistant.api.RgbColor
import cc.opencar.assistant.api.UnitOfMeasurement
import cc.opencar.assistant.api.ValueRange
import cc.opencar.assistant.api.VehicleProperty
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.memory.SettingsMemoryController
import cc.opencar.assistant.support.I18nBundle
import cc.opencar.assistant.support.LastKnownStore
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Product entity API facade over [EntityRegistry].
 * Builds `/api/entities` maps and handles set/read (including composites).
 */
object ControlCatalog {
    val ALL: List<EntityDef> get() = EntityRegistry.ALL

    /** Id prefix of the telemetry rows built by [telemetrySensors]. */
    private const val SENSOR_PREFIX = "sensor."

    /** Curated registry + auto entities for unbound catalog property keys. */
    fun defsFor(session: VehicleSession): List<EntityDef> =
        ALL + CatalogEntityFactory.fromCatalog(session.catalog())

    fun i18n(context: Context, session: VehicleSession): I18nBundle =
        I18nBundle.load(context, session.integrationId)

    suspend fun snapshot(
        session: VehicleSession,
        context: Context? = null,
        memory: SettingsMemoryController? = null,
    ): List<Map<String, Any?>> {
        val store = context?.let { LastKnownStore(it) }
        val i18n = context?.let { i18n(it, session) }
        val persist = memory?.persistSnapshot().orEmpty()
        val lockedKeys = session.catalog().filter { it.writeLocked }.mapTo(HashSet()) { it.property.key }
        return defsFor(session).mapNotNull { def ->
            row(session, def, store, i18n, def.bindingKey in lockedKeys)?.let { enrichPersist(it, def, persist[def.id]) }
        }
    }

    /** One vehicle entity row as [snapshot] builds it (no persist pins), or null when unknown or unbound. */
    suspend fun entityRow(session: VehicleSession, id: String, context: Context? = null): Map<String, Any?>? {
        val def = resolveDef(session, id) ?: return null
        val locked = session.catalog().any { it.writeLocked && it.property.key == def.bindingKey }
        return row(session, def, context?.let { LastKnownStore(it) }, context?.let { i18n(it, session) }, locked)
    }

    private suspend fun row(
        session: VehicleSession,
        def: EntityDef,
        store: LastKnownStore?,
        i18n: I18nBundle?,
        writeLocked: Boolean,
    ): Map<String, Any?>? = when {
        def.domain == EntityType.CAMERA -> null
        def.domain == EntityType.CLIMATE && def.isComposite -> climateMap(session, def, store)
        def.isComposite -> compositeMap(session, def, store, i18n)
        def.domain == EntityType.COVER -> coverMap(session, def, store, i18n)
        else -> def.property()?.let { prop ->
            defToMap(def, session.diagnose(prop), store, i18n, session, writeLocked)
        }
    }

    /** `/api/controls` rows and the full `/api/entities` list, from one snapshot. */
    class Built(val controls: List<Map<String, Any?>>, val entities: List<Map<String, Any?>>)

    suspend fun build(
        session: VehicleSession,
        context: Context? = null,
        memory: SettingsMemoryController? = null,
        android: AndroidSettingsController? = null,
        location: LocationTrackerController? = null,
        dvr: DvrController? = null,
    ): Built {
        val controls = snapshot(session, context, memory)
        val persist = memory?.persistSnapshot().orEmpty()
        val androidEntities = android?.entityMaps(persist).orEmpty()
        val locationEntities = location?.entityMaps().orEmpty()
        val cameraEntities = cameraEntityMaps(dvr)
        return Built(controls, telemetrySensors(session) + controls + androidEntities + locationEntities + cameraEntities)
    }

    /** `sensor.*` rows read off the telemetry snapshot (model, VIN, speed, SoC, …). */
    private suspend fun telemetrySensors(session: VehicleSession): List<Map<String, Any?>> {
        val t = session.telemetry().first()
        val vinValue = when (val out = session.diagnose(EntityRegistry.property("INFO_VIN"))) {
            is ReadOutcome.Ok -> out.value?.display()
            else -> null
        }
        return listOf(
            sensor("model", "vehicle", t.extras["model"], icon = "sensor"),
            sensor("vin", "vehicle", vinValue, icon = "sensor"),
            sensor(
                "gear", "home", t.gear?.toString(),
                icon = "drive", history = true, valueMapId = "gear",
            ),
            sensor(
                "speed", "home",
                t.speedKmh?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.SPEED,
                unitOfMeasurement = UnitOfMeasurement.KM_PER_HOUR,
                icon = "sensor", history = true,
            ),
            sensor(
                "soc", "home",
                (t.evBatteryPercent ?: t.hybridSocPercent)?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.BATTERY,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "battery", history = true,
            ),
            sensor(
                "fuel", "home",
                t.fuelPercent?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.FUEL,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "energy", history = true,
            ),
            sensor(
                "range", "home",
                t.rangeKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "energy", history = true,
            ),
            sensor(
                "range_ev", "home",
                t.rangeEvKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "battery", history = true,
            ),
            sensor(
                "range_fuel", "home",
                t.rangeFuelKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "energy", history = true,
            ),
            sensor(
                "odometer", "vehicle",
                t.odometerKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "sensor", history = true,
            ),
            sensor(
                "ignition", "home", t.ignitionState?.toString(),
                icon = "drive", valueMapId = "ignition",
            ),
            sensor(
                "parking_brake", "home", t.extras["parkingBrake"],
                icon = "brake", binary = true, valueMapId = "parking_brake",
            ),
            sensor(
                "temp_ambient", "controls",
                t.tempAmbientC?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.TEMPERATURE,
                unitOfMeasurement = UnitOfMeasurement.CELSIUS,
                icon = "temp", history = true,
            ),
            sensor(
                "battery_temp", "energy",
                t.batteryTempC?.let { "%.1f".format(it) },
                deviceClass = DeviceClass.TEMPERATURE,
                unitOfMeasurement = UnitOfMeasurement.CELSIUS,
                icon = "battery", history = true,
            ),
            sensor(
                "charge_plug", "energy",
                t.chargePlugConnected?.let { if (it) "1" else "0" },
                icon = "charge", history = true, binary = true, valueMapId = "charge_plug",
            ),
            sensor(
                "hybrid_soc", "energy",
                t.hybridSocPercent?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.BATTERY,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "battery", history = true,
            ),
            sensor(
                "charge_eta", "energy",
                t.chargeEstimatedTimeMin?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DURATION,
                unitOfMeasurement = UnitOfMeasurement.MINUTE,
                icon = "charge", history = true,
            ),
            sensor(
                "charge_energy", "energy",
                t.chargeEnergyKwh?.let { "%.1f".format(it) },
                deviceClass = DeviceClass.ENERGY,
                unitOfMeasurement = UnitOfMeasurement.KILOWATT_HOUR,
                icon = "charge", history = true,
            ),
            sensor(
                "charge_work_a", "energy",
                t.chargeWorkCurrentA?.let { "%.1f".format(it) },
                deviceClass = DeviceClass.CURRENT,
                unitOfMeasurement = UnitOfMeasurement.AMPERE,
                icon = "charge",
            ),
            sensor(
                "charge_work_v", "energy",
                t.chargeWorkVoltageV?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.VOLTAGE,
                unitOfMeasurement = UnitOfMeasurement.VOLT,
                icon = "charge",
            ),
            sensor(
                "avg_energy", "energy",
                t.avgEnergyKwh100km?.let { "%.1f".format(it) },
                deviceClass = DeviceClass.ENERGY,
                unitOfMeasurement = UnitOfMeasurement.KWH_PER_100KM,
                icon = "energy", history = true,
            ),
            sensor(
                "avg_fuel", "energy",
                t.avgFuelL100km?.let { "%.1f".format(it) },
                deviceClass = DeviceClass.FUEL,
                unitOfMeasurement = UnitOfMeasurement.LITER_PER_100KM,
                icon = "energy", history = true,
            ),
            sensor(
                "energy_flow_driving", "energy",
                t.energyFlowDriving?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.POWER,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "drive",
            ),
            sensor(
                "energy_flow_battery", "energy",
                t.energyFlowBattery?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.POWER,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "battery",
            ),
            sensor(
                "energy_flow_climate", "energy",
                t.energyFlowClimate?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.POWER,
                unitOfMeasurement = UnitOfMeasurement.PERCENT,
                icon = "climate",
            ),
            sensor(
                "maintenance", "vehicle",
                t.maintenanceMileageKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "sensor",
            ),
            sensor(
                "since_maintenance", "vehicle",
                t.sinceMaintenanceKm?.let { "%.0f".format(it) },
                deviceClass = DeviceClass.DISTANCE,
                unitOfMeasurement = UnitOfMeasurement.KILOMETER,
                icon = "sensor",
            ),
        )
    }

    private fun cameraEntityMaps(dvr: DvrController?): List<Map<String, Any?>> {
        if (dvr == null) return emptyList()
        val open = dvr.runningCameraIds().toSet()
        return dvr.cameras().mapNotNull { src ->
            val role = src.role ?: return@mapNotNull null
            val def = EntityRegistry.byId(src.id) ?: EntityRegistry.CAMERAS.firstOrNull {
                it.id == "camera.$role"
            } ?: return@mapNotNull null
            val state = if (src.cameraId in open) "streaming" else "idle"
            val attrs = linkedMapOf<String, Any?>(
                "role" to role,
                "camera_id" to src.cameraId,
            )
            EntityContract.enrich(
                mapOf(
                    "id" to def.id,
                    "group" to def.group,
                EntityContract.FIELD_SECTION to def.resolvedSection(),
                    "domain" to EntityType.CAMERA.id,
                    "labelKey" to def.resolvedLabelKey(),
                    "hintKey" to def.resolvedHintKey(),
                    "input" to "camera",
                    "icon" to def.resolvedIcon(),
                    "writable" to false,
                    "value" to state,
                    "valueMapId" to "camera.state",
                    "status" to "ok",
                    "needsPrivilege" to false,
                    "stale" to false,
                    "history" to false,
                    "attributes" to attrs,
                ),
            )
        }
    }

    private fun resolveDef(session: VehicleSession, id: String): EntityDef? {
        EntityRegistry.byId(id)?.let { return it }
        val claimed = CatalogEntityFactory.claimedBindingKeys()
        if (id in claimed) return null
        val entry = session.catalog().firstOrNull { e ->
            e.property.key == id || e.name == id
        } ?: return null
        return CatalogEntityFactory.fromCatalog(listOf(entry), claimed).firstOrNull()
    }

    /** Current display value for shortcut conditions / entity_state watching. */
    suspend fun currentValue(session: VehicleSession, id: String): String? {
        if (id.startsWith(SENSOR_PREFIX)) {
            return telemetrySensors(session).firstOrNull { it["id"] == id }?.get("value") as? String
        }
        val def = resolveDef(session, id) ?: return null
        if (def.domain == EntityType.CAMERA) {
            // Live state is owned by DVR; callers with a DvrController should use entity maps.
            return null
        }
        if (def.isComposite) {
            val map = if (def.domain == EntityType.CLIMATE) {
                climateMap(session, def, null)
            } else {
                compositeMap(session, def, null, null)
            } ?: return null
            return map["value"] as? String
        }
        if (def.domain == EntityType.COVER) {
            return coverMap(session, def, null, null)?.get("value") as? String
        }
        val prop = def.property() ?: return null
        return when (val out = session.diagnose(prop)) {
            is ReadOutcome.Ok -> out.value?.display()
            else -> null
        }
    }

    suspend fun set(session: VehicleSession, id: String, raw: String, context: Context? = null): Result<Unit> {
        val store = context?.let { LastKnownStore(it) }
        val resolved = resolveDef(session, id)
            ?: return Result.failure(IllegalArgumentException("unknown control"))

        if (resolved.isComposite) {
            return if (resolved.domain == EntityType.CLIMATE) {
                setClimate(session, resolved, raw, store)
            } else {
                setComposite(session, resolved, raw, store)
            }
        }

        val def = resolved
        if (!def.writable) {
            return Result.failure(IllegalArgumentException("control is read-only"))
        }
        if (def.domain == EntityType.COVER) {
            return setCover(session, def, raw, store)
        }
        if (def.domain == EntityType.LOCK) {
            return setLock(session, def, raw, store)
        }
        val prop = def.property()
            ?: return Result.failure(IllegalArgumentException("control has no binding"))
        val pv: PropertyValue = when (def.input) {
            "bool" -> {
                val on = raw == "1" || raw.equals("true", true) || raw == "on"
                PropertyValue.IntVal(if (on) 1 else 0)
            }
            "float" -> PropertyValue.FloatVal(raw.toFloat())
            else -> PropertyValue.IntVal(raw.toInt())
        }
        val result = session.set(prop, pv)
        if (result.isSuccess && def.lastKnown) store?.put(def.id, raw)
        return result
    }

    private suspend fun setLock(
        session: VehicleSession,
        def: EntityDef,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val prop = def.property()
            ?: return Result.failure(IllegalArgumentException("lock has no binding"))
        val locked = when (raw.trim().lowercase()) {
            "1", "true", "on", "lock", "locked" -> true
            "0", "false", "off", "unlock", "unlocked" -> false
            else -> return Result.failure(IllegalArgumentException("unknown lock value: $raw"))
        }
        val result = session.set(prop, PropertyValue.IntVal(if (locked) 1 else 0))
        if (result.isSuccess && def.lastKnown) {
            store?.put(def.id, if (locked) "1" else "0")
        }
        return result
    }

    private suspend fun setComposite(
        session: VehicleSession,
        def: EntityDef,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val v = raw.trim()
        if (def.domain == EntityType.LIGHT) {
            val scale = DomainDefaults.LIGHT_BRIGHTNESS
            when (v.lowercase()) {
                "on", "1", "true" ->
                    return writeCompositeAttr(
                        session, def, "brightness",
                        ((scale.min!! + scale.max!!) / 2f).roundToInt().toString(),
                        store,
                    )
                "off", "0", "false" ->
                    return writeCompositeAttr(session, def, "brightness", scale.min!!.roundToInt().toString(), store)
            }
        }
        // Trunk cover (composite): open/close via DOOR_MOVE.
        if (def.domain == EntityType.COVER && "move" in def.attributes) {
            when (v.lowercase()) {
                "open", "on", "1", "true" ->
                    return writeCompositeAttr(session, def, "move", "1", store)
                "closed", "close", "off", "0", "false" ->
                    return writeCompositeAttr(session, def, "move", "0", store)
            }
        }
        val colon = v.indexOf(':')
        if (colon > 0) {
            var attrKey = v.substring(0, colon).lowercase().replace('-', '_')
            val value = v.substring(colon + 1).trim()
            // Saved shortcuts predate the HA `effect` name.
            if (def.domain == EntityType.LIGHT && attrKey == "color") attrKey = "effect"
            if (attrKey in def.attributes) {
                return writeCompositeAttr(session, def, attrKey, value, store)
            }
        }
        val primary = when (def.domain) {
            EntityType.DRIVETRAIN -> "mode"
            EntityType.STEERING -> "assist_level"
            EntityType.CHASSIS -> "auto_hold"
            EntityType.HUD -> "active"
            EntityType.CHARGER -> "switch"
            EntityType.LIGHT -> "effect"
            EntityType.EV_BATTERY -> "percent"
            EntityType.COVER -> "move"
            else -> def.attributes.keys.firstOrNull()
        } ?: return Result.failure(IllegalArgumentException("composite has no primary attr"))
        if (def.domain == EntityType.COVER && primary == "move") {
            val on = v == "1" || v.equals("true", true) || v.equals("on", true) ||
                v.equals("open", true)
            return writeCompositeAttr(session, def, "move", if (on) "1" else "0", store)
        }
        return writeCompositeAttr(session, def, primary, v, store)
    }

    /** Atomic position cover (windows / sunroof / sunshade): WINDOW_POS 0–100 int32. */
    private suspend fun setCover(
        session: VehicleSession,
        def: EntityDef,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val prop = def.property()
            ?: return Result.failure(IllegalArgumentException("cover has no binding"))
        val v = raw.trim()
        val lower = v.lowercase()
        val scale = DomainDefaults.COVER_POSITION
        val opened = scale.max!!.roundToInt()
        val closed = scale.min!!.roundToInt()
        val position: Int = when {
            lower in setOf("open", "on", "true") -> opened
            lower in setOf("closed", "close", "off", "false") -> closed
            lower.startsWith("position:") || lower.startsWith("position_") ->
                lower.removePrefix("position").trimStart(':', '_').toFloatOrNull()?.toInt()
                    ?: return Result.failure(IllegalArgumentException("bad cover position"))
            lower.toFloatOrNull() != null -> lower.toFloat().toInt()
            else -> return Result.failure(IllegalArgumentException("unknown cover value: $raw"))
        }.coerceIn(closed, opened)
        val native = DomainDefaults.fromCanonical(position.toFloat(), declaredRange(session, prop, def), scale)
        // Venus WINDOW_POS requires int32.
        val result = session.set(prop, PropertyValue.IntVal(native.roundToInt()))
        if (result.isSuccess && def.lastKnown) {
            store?.put(def.id, if (position > 1) "open" else "closed")
            store?.put("${def.id}:position", position.toString())
        }
        return result
    }

    private suspend fun coverMap(
        session: VehicleSession,
        def: EntityDef,
        store: LastKnownStore?,
        i18n: I18nBundle?,
    ): Map<String, Any?>? {
        val prop = def.property() ?: return null
        if (!session.hasBinding(prop)) return null
        val preferred = def.areaId
        val outcome = if (preferred != null) {
            when (val first = session.diagnose(prop, preferred)) {
                is ReadOutcome.Ok -> first
                else -> session.diagnose(prop, null)
            }
        } else {
            session.diagnose(prop, null)
        }
        val scale = DomainDefaults.COVER_POSITION
        val native = declaredRange(session, prop, def)
        val position = when (outcome) {
            is ReadOutcome.Ok -> outcome.value?.asFloat()
                ?.let { DomainDefaults.toCanonical(it, native, scale).roundToInt() }
            else -> null
        }
        val open = (position ?: 0) > 1
        val state = if (open) "open" else "closed"
        if (def.lastKnown && position != null) {
            store?.put(def.id, state)
            store?.put("${def.id}:position", position.toString())
        }
        val attrs = linkedMapOf<String, Any?>(
            "current_position" to (position ?: 0),
        )
        val status = when (outcome) {
            is ReadOutcome.Ok -> "ok"
            is ReadOutcome.Denied -> "denied"
            is ReadOutcome.Failed -> "failed"
            is ReadOutcome.Unavailable -> "unavailable"
        }
        return EntityContract.enrich(
            mapOf(
                "id" to def.id,
                "group" to def.group,
                EntityContract.FIELD_SECTION to def.resolvedSection(),
                "domain" to def.domain.id,
                "labelKey" to def.resolvedLabelKey(),
                "hintKey" to def.resolvedHintKey(),
                "input" to "cover",
                "icon" to def.resolvedIcon(),
                "deviceClass" to def.deviceClass?.id,
                "min" to scale.min,
                "max" to scale.max,
                "step" to scale.step,
                "history" to def.history,
                "writable" to (def.writable && status == "ok"),
                "value" to state,
                "valueMapId" to def.resolvedValueMapId(),
                "state" to state,
                "status" to status,
                "permission" to (outcome as? ReadOutcome.Denied)?.permission,
                "needsPrivilege" to (status == "denied"),
                "stale" to false,
                "areaId" to def.areaId,
                "attributes" to attrs,
                EntityContract.FIELD_COMPOSITE to false,
                EntityContract.FIELD_UPDATE to EntityContract.UPDATE_ENTITY,
            ),
        )
    }

    private suspend fun writeCompositeAttr(
        session: VehicleSession,
        def: EntityDef,
        attr: String,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val prop = def.attributeProperty(attr)
            ?: return Result.failure(IllegalArgumentException("unknown attr: $attr"))
        if (!session.hasBinding(prop)) {
            return Result.failure(IllegalArgumentException("attr unbound: $attr"))
        }
        val pv = when {
            def.domain == EntityType.LIGHT && attr == "brightness" -> {
                val canonical = raw.toFloatOrNull()
                    ?: return Result.failure(IllegalArgumentException("bad brightness: $raw"))
                val native = DomainDefaults.fromCanonical(
                    canonical.coerceIn(DomainDefaults.LIGHT_BRIGHTNESS.min!!, DomainDefaults.LIGHT_BRIGHTNESS.max!!),
                    declaredRange(session, prop, null),
                    DomainDefaults.LIGHT_BRIGHTNESS,
                )
                PropertyValue.IntVal(native.roundToInt())
            }
            attr == "rgb_color" -> PropertyValue.IntVal(
                RgbColor.parse(raw) ?: return Result.failure(IllegalArgumentException("bad rgb_color: $raw")),
            )
            // Venus WINDOW_POS scheduler only accepts int32 (float writes log
            // "Haven't int32 values" and never actuate).
            prop.key == "window_pos" ->
                PropertyValue.IntVal(raw.toFloatOrNull()?.toInt() ?: raw.toInt())
            attr in FLOAT_ATTRS || raw.contains('.') ->
                PropertyValue.FloatVal(raw.toFloat())
            attr in BOOLISH_ATTRS ||
                raw.equals("true", true) || raw.equals("false", true) ||
                raw.equals("on", true) || raw.equals("off", true) -> {
                val on = raw == "1" || raw.equals("true", true) || raw.equals("on", true)
                PropertyValue.IntVal(if (on) 1 else 0)
            }
            else -> PropertyValue.IntVal(raw.toInt())
        }
        val result = session.set(prop, pv)
        if (result.isSuccess && def.lastKnown) {
            store?.put("${def.id}:$attr", raw)
            store?.put(def.id, raw)
        }
        return result
    }

    private val FLOAT_ATTRS = setOf(
        "temperature", "current_temperature", "percent", "level_raw", "temp_c",
        "hybrid_soc", "angle", "intensity", "open_height", "energy",
        "work_current", "work_voltage", "current", "limit", "soc_max", "soc_min",
        "discharge_soc", "estimated_time", "position", "brightness", "intensity",
    )

    private val BOOLISH_ATTRS = setOf(
        "power", "ac", "auto", "recirc", "max_defrost", "max_ac", "eco",
        "auto_dry", "rapid_cool", "rapid_heat", "electric_defrost", "auto_recirc",
        "auto_seat_vent", "plug", "switch", "pre_now", "v2l", "v2v", "parking",
        "external_light", "active", "snow", "ar", "battery_hold", "battery_save",
        "esc", "hdc", "auto_hold", "epb", "parking_brake", "sync_drive_mode",
        "intelligent", "lock", "fold", "auto_fold", "auto_close", "tilt",
    )

    /** Integration-declared native range for [prop], then the curated [def] bounds. */
    private fun declaredRange(session: VehicleSession?, prop: VehicleProperty?, def: EntityDef?): ValueRange? {
        val native = prop?.let { session?.valueRange(it) }
        val curated = def?.let { ValueRange(it.min, it.max, it.step) }
            ?.takeIf { it.min != null || it.max != null || it.step != null }
        return when {
            native != null && curated != null -> native.orElse(curated)
            else -> native ?: curated
        }
    }

    private suspend fun setClimate(
        session: VehicleSession,
        def: EntityDef,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val v = raw.trim()
        val lower = v.lowercase().replace('-', '_')
        when {
            lower == "off" || lower == "0" -> return writeClimateAttr(session, def, "power", "0", store)
            lower == "on" || lower == "1" -> return writeClimateAttr(session, def, "power", "1", store)
            lower.startsWith("hvac_mode:") || lower.startsWith("hvac_mode_") -> {
                val mode = lower.removePrefix("hvac_mode").trimStart(':', '_')
                return setClimateMode(session, def, mode, store)
            }
            lower.startsWith("temperature:") || lower.startsWith("temperature_") -> {
                val t = lower.removePrefix("temperature").trimStart(':', '_')
                return writeClimateAttr(session, def, "temperature", t, store)
            }
            lower.startsWith("fan_mode:") || lower.startsWith("fan_mode_") -> {
                val f = lower.removePrefix("fan_mode").trimStart(':', '_')
                return writeClimateAttr(session, def, "fan_mode", f, store)
            }
            lower.startsWith("fan_direction:") || lower.startsWith("fan_direction_") -> {
                val d = lower.removePrefix("fan_direction").trimStart(':', '_')
                return writeClimateAttr(session, def, "fan_direction", d, store)
            }
            lower.startsWith("recirc:") || lower.startsWith("recirc_") -> {
                val r = lower.removePrefix("recirc").trimStart(':', '_')
                return writeClimateAttr(session, def, "recirc", r, store)
            }
            lower.startsWith("ac:") || lower.startsWith("ac_") -> {
                val a = lower.removePrefix("ac").trimStart(':', '_')
                return writeClimateAttr(session, def, "ac", a, store)
            }
            lower.toFloatOrNull() != null ->
                return writeClimateAttr(session, def, "temperature", lower, store)
            lower in setOf("auto", "manual", "on", "off") ->
                return setClimateMode(session, def, lower, store)
        }
        return Result.failure(IllegalArgumentException("unknown climate value: $raw"))
    }

    private suspend fun setClimateMode(
        session: VehicleSession,
        def: EntityDef,
        mode: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        when (mode) {
            "off" -> return writeClimateAttr(session, def, "power", "0", store)
            "on", "manual" -> {
                writeClimateAttr(session, def, "power", "1", store)
                return writeClimateAttr(session, def, "auto", "0", store)
            }
            "auto" -> {
                writeClimateAttr(session, def, "power", "1", store)
                return writeClimateAttr(session, def, "auto", "1", store)
            }
        }
        return Result.failure(IllegalArgumentException("unknown hvac_mode: $mode"))
    }

    private suspend fun writeClimateAttr(
        session: VehicleSession,
        def: EntityDef,
        attr: String,
        raw: String,
        store: LastKnownStore?,
    ): Result<Unit> {
        val prop = def.attributeProperty(attr)
            ?: return Result.failure(IllegalArgumentException("unknown climate attr: $attr"))
        val pv = when (attr) {
            "temperature" -> PropertyValue.FloatVal(raw.toFloat())
            "fan_mode", "fan_direction" -> PropertyValue.IntVal(raw.toInt())
            else -> {
                val on = raw == "1" || raw.equals("true", true) || raw.equals("on", true)
                PropertyValue.IntVal(if (on) 1 else 0)
            }
        }
        val result = session.set(prop, pv)
        if (result.isSuccess && def.lastKnown) {
            store?.put("climate:$attr", raw)
            store?.put("${def.id}:$attr", raw)
        }
        return result
    }

    private suspend fun climateMap(
        session: VehicleSession,
        def: EntityDef,
        store: LastKnownStore?,
    ): Map<String, Any?>? {
        val powerProp = def.attributeProperty("power")
        val tempProp = def.attributeProperty("temperature")
        val coreBound = listOfNotNull(powerProp, tempProp).any { session.hasBinding(it) }
        if (!coreBound) {
            // Still show if any climate attr is bound
            val any = def.attributes.values.any { session.hasBinding(EntityRegistry.property(it)) }
            if (!any) return null
        }

        suspend fun readInt(attr: String): Int? {
            val p = def.attributeProperty(attr) ?: return null
            if (!session.hasBinding(p)) return null
            return when (val out = session.diagnose(p)) {
                is ReadOutcome.Ok -> out.value?.asInt()
                else -> null
            }
        }
        suspend fun readFloat(attr: String): Float? {
            val p = def.attributeProperty(attr) ?: return null
            if (!session.hasBinding(p)) return null
            return when (val out = session.diagnose(p)) {
                is ReadOutcome.Ok -> out.value?.asFloat()
                else -> null
            }
        }

        val power = readInt("power")
        val auto = readInt("auto")
        val ac = readInt("ac")
        val temperature = readFloat("temperature")
        val currentTemp = readFloat("current_temperature")
            ?: session.telemetry().first().tempIndoorC
        val fanMode = readInt("fan_mode")
        val fanDirection = readInt("fan_direction")
        val recirc = readInt("recirc")

        fun on(v: Int?) = v != null && v != 0 && v != 2

        // VHAL has HVAC_POWER + HVAC_AUTO only — no heat/cool/fan_only enum.
        // AC is a compressor toggle; "fan only" is just manual with AC off.
        val hvacMode = when {
            power != null && !on(power) -> "off"
            on(auto) -> "auto"
            on(power) -> "manual"
            else -> "off"
        }

        if (def.lastKnown) {
            store?.put(def.id, hvacMode)
            temperature?.let { store?.put("climate:temperature", it.toString()) }
        }

        fun boundRange(attr: String): ValueRange? =
            def.attributeProperty(attr)?.takeIf { session.hasBinding(it) }?.let { session.valueRange(it) }
        val tempRange = (declaredRange(session, tempProp, def) ?: ValueRange())
            .orElse(DomainDefaults.CLIMATE_TEMPERATURE)

        val attrs = linkedMapOf<String, Any?>(
            "hvac_modes" to listOf("off", "manual", "auto"),
            "hvac_mode" to hvacMode,
        )
        boundRange("fan_mode")?.levels()?.let { attrs["fan_modes"] = it }
        boundRange("fan_direction")?.levels()?.let { attrs["fan_directions"] = it }
        temperature?.let { attrs["temperature"] = it }
        currentTemp?.let { attrs["current_temperature"] = it }
        fanMode?.let { attrs["fan_mode"] = it }
        fanDirection?.let { attrs["fan_direction"] = it }
        attrs["recirc"] = if (on(recirc)) 1 else 0
        attrs["ac"] = if (on(ac)) 1 else 0
        attrs["auto"] = if (on(auto)) 1 else 0
        attrs["power"] = if (on(power)) 1 else 0
        attrs["min_temp"] = tempRange.min
        attrs["max_temp"] = tempRange.max
        attrs["target_temp_step"] = tempRange.step

        val status = when {
            power != null || temperature != null -> "ok"
            else -> "unavailable"
        }

        return EntityContract.enrich(
            mapOf(
                "id" to def.id,
                "group" to def.group,
                EntityContract.FIELD_SECTION to def.resolvedSection(),
                "domain" to def.domain.id,
                "labelKey" to def.resolvedLabelKey(),
                "hintKey" to def.resolvedHintKey(),
                "input" to "climate",
                "icon" to def.resolvedIcon(),
                "deviceClass" to def.deviceClass?.id,
                "unitOfMeasurement" to def.unitOfMeasurement?.id,
                "min" to tempRange.min,
                "max" to tempRange.max,
                "step" to tempRange.step,
                "history" to def.history,
                "writable" to (status == "ok"),
                "value" to hvacMode,
                "state" to hvacMode,
                "status" to status,
                "permission" to null,
                "needsPrivilege" to false,
                "stale" to false,
                "attributes" to attrs,
                EntityContract.FIELD_COMPOSITE to true,
                EntityContract.FIELD_UPDATE to EntityContract.UPDATE_CATALOG,
            ),
        )
    }

    private suspend fun compositeMap(
        session: VehicleSession,
        def: EntityDef,
        store: LastKnownStore?,
        i18n: I18nBundle?,
    ): Map<String, Any?>? {
        val anyBound = def.attributes.values.any { session.hasBinding(EntityRegistry.property(it)) }
        if (!anyBound) return null

        val attrs = linkedMapOf<String, Any?>()
        var anyOk = false
        for ((attr, _) in def.attributes) {
            val prop = def.attributeProperty(attr) ?: continue
            if (!session.hasBinding(prop)) continue
            val preferred = def.areaId
            val outcome = if (preferred != null) {
                when (val first = session.diagnose(prop, preferred)) {
                    is ReadOutcome.Ok -> first
                    else -> session.diagnose(prop, null)
                }
            } else {
                session.diagnose(prop, null)
            }
            when (outcome) {
                is ReadOutcome.Ok -> {
                    anyOk = true
                    val disp = outcome.value?.display()
                    if (disp != null) {
                        attrs[attr] = when {
                            attr in FLOAT_ATTRS -> outcome.value?.asFloat() ?: disp
                            attr in BOOLISH_ATTRS -> {
                                val n = outcome.value?.asInt()
                                if (n != null && n != 0 && n != 2) 1 else 0
                            }
                            else -> outcome.value?.asInt() ?: disp
                        }
                        if (def.lastKnown) store?.put("${def.id}:$attr", disp)
                    }
                }
                else -> Unit
            }
        }
        if (attrs.isEmpty() && !anyOk) return null

        // Trunk cover: BCM status 0/1 = closed; 2+ = open / moving.
        if (def.domain == EntityType.COVER) {
            fun num(key: String): Float? =
                (attrs[key] as? Number)?.toFloat() ?: attrs[key]?.toString()?.toFloatOrNull()
            val s = num("status")?.toInt()
            val open = s != null && s !in setOf(0, 1)
            attrs["open"] = if (open) 1 else 0
        }

        if (def.domain == EntityType.LIGHT) lightAttributes(session, def, attrs)

        val primaryAttr = when (def.domain) {
            EntityType.DRIVETRAIN -> "mode"
            EntityType.STEERING -> "assist_level"
            EntityType.CHASSIS -> "auto_hold"
            EntityType.HUD -> "active"
            EntityType.CHARGER -> "switch"
            EntityType.LIGHT -> "effect"
            EntityType.EV_BATTERY -> "percent"
            EntityType.COVER -> "open"
            else -> def.attributes.keys.firstOrNull()
        }
        val primary = when (def.domain) {
            EntityType.LIGHT -> {
                val bri = (attrs["brightness"] as? Number)?.toFloat()
                    ?: attrs["brightness"]?.toString()?.toFloatOrNull()
                if (bri != null && bri > 0f) "on" else "off"
            }
            EntityType.COVER -> {
                if (attrs["open"] == 1) "open" else "closed"
            }
            else -> primaryAttr?.let { attrs[it]?.toString() }
        }
        if (def.lastKnown && primary != null) store?.put(def.id, primary)

        val status = if (anyOk) "ok" else "unavailable"
        val options = optionMaps(def, i18n).ifEmpty { null }
        val input = when (def.domain) {
            EntityType.LIGHT -> "light"
            EntityType.COVER -> "cover"
            else -> def.input
        }
        val range = if (def.domain == EntityType.LIGHT) {
            DomainDefaults.LIGHT_BRIGHTNESS
        } else {
            ValueRange(def.min, def.max, def.step)
        }

        return EntityContract.enrich(
            mapOf(
                "id" to def.id,
                "group" to def.group,
                EntityContract.FIELD_SECTION to def.resolvedSection(),
                "domain" to def.domain.id,
                "labelKey" to def.resolvedLabelKey(),
                "hintKey" to def.resolvedHintKey(),
                "input" to input,
                "icon" to def.resolvedIcon(),
                "deviceClass" to def.deviceClass?.id,
                "unitOfMeasurement" to def.unitOfMeasurement?.id,
                "min" to range.min,
                "max" to range.max,
                "step" to range.step,
                "history" to def.history,
                "writable" to (def.writable && status == "ok"),
                "options" to options,
                "value" to primary,
                "valueMapId" to def.resolvedValueMapId(),
                "state" to primary,
                "status" to status,
                "permission" to null,
                "needsPrivilege" to false,
                "stale" to false,
                "areaId" to def.areaId,
                "attributes" to attrs,
                EntityContract.FIELD_COMPOSITE to true,
                EntityContract.FIELD_UPDATE to EntityContract.UPDATE_CATALOG,
            ),
        )
    }

    /**
     * HA light shape: `brightness` on the canonical 0–255 scale, `rgb_color` as `[r, g, b]`,
     * `supported_color_modes` / `color_mode` from the bound attributes.
     */
    private fun lightAttributes(session: VehicleSession, def: EntityDef, attrs: MutableMap<String, Any?>) {
        fun bound(attr: String) = def.attributeProperty(attr)?.takeIf { session.hasBinding(it) }
        val brightnessProp = bound("brightness")
        (attrs["brightness"] as? Number)?.let { native ->
            attrs["brightness"] = DomainDefaults.toCanonical(
                native.toFloat(),
                declaredRange(session, brightnessProp, null),
                DomainDefaults.LIGHT_BRIGHTNESS,
            ).roundToInt()
        }
        (attrs["rgb_color"] as? Number)?.let { attrs["rgb_color"] = RgbColor.toList(it.toInt()) }
        val mode = when {
            bound("rgb_color") != null -> "rgb"
            brightnessProp != null -> "brightness"
            else -> "onoff"
        }
        attrs["supported_color_modes"] = listOf(mode)
        attrs["color_mode"] = mode
    }

    private fun enrichPersist(
        base: Map<String, Any?>,
        def: EntityDef,
        pin: Map<String, Any?>?,
    ): Map<String, Any?> {
        val enabled = pin?.get("enabled") == true
        val pVal = pin?.get("value") as? String
        return base + mapOf(
            "persistEnabled" to enabled,
            "persistValue" to pVal,
            "valueMapId" to (base["valueMapId"] ?: def.resolvedValueMapId()),
        )
    }

    private fun defToMap(
        def: EntityDef,
        outcome: ReadOutcome,
        store: LastKnownStore?,
        i18n: I18nBundle?,
        session: VehicleSession? = null,
        writeLocked: Boolean = false,
    ): Map<String, Any?> {
        // Write-only commands never have a lasting current value — don't surface reads.
        if (def.input == "command") {
            val permission = (outcome as? ReadOutcome.Denied)?.permission
            val denied = outcome is ReadOutcome.Denied
            val bound = def.property()?.let { session?.hasBinding(it) } == true
            val ready = def.writable && bound && !denied
            return baseMap(
                def,
                value = null,
                status = when {
                    denied -> "denied"
                    ready -> "ok"
                    else -> "unavailable"
                },
                permission = permission,
                stale = false,
                i18n = i18n,
                forceWritable = ready,
                writeLocked = writeLocked,
            )
        }
        val (value, status, permission) = when (outcome) {
            is ReadOutcome.Ok -> {
                val disp = outcome.value?.display()
                // Cache every successful read, including Off/0/false — those are real states.
                if (def.lastKnown && !disp.isNullOrBlank()) {
                    store?.put(def.id, disp)
                }
                Triple(disp, "ok", null as String?)
            }
            is ReadOutcome.Denied -> Triple(null, "denied", outcome.permission)
            is ReadOutcome.Failed -> Triple(null, "failed", outcome.message)
            is ReadOutcome.Unavailable -> Triple(null, "unavailable", null)
        }
        // Only fall back when the live value is actually missing. Off (0) and false are
        // real states — treating them as empty made exterior_light/CST/night_mode show
        // "last known" after the user turned them off, and overwrote card descriptions.
        val range = if (def.input == "int" || def.input == "float") {
            (declaredRange(session, def.property(), def) ?: ValueRange()).orElse(DomainDefaults.NUMBER)
        } else {
            null
        }
        if (value.isNullOrBlank() && def.lastKnown) {
            val cached = store?.get(def.id)
            if (cached != null) {
                return baseMap(
                    def, cached, "cached", null,
                    stale = true, i18n = i18n, range = range, writeLocked = writeLocked,
                )
            }
        }
        return baseMap(
            def, value, status, permission,
            stale = false, i18n = i18n, range = range, writeLocked = writeLocked,
        )
    }

    private fun optionMaps(def: EntityDef, i18n: I18nBundle?): List<Map<String, Any?>> {
        // Always emit options sorted by ascending numeric value (Auto=3/4 last, etc.).
        val keys = def.optionKeys
        if (keys != null) {
            return keys.sortedBy { it.second }.map { (key, value) ->
                mapOf("labelKey" to key, "value" to value)
            }
        }
        val fromMaps = i18n?.valueMapsSnapshot()?.get(def.resolvedValueMapId())
        if (!fromMaps.isNullOrEmpty() &&
            (def.input == "choice" || def.input == "command" || def.input == "light")
        ) {
            val parsed = fromMaps.mapNotNull { (k, labelKey) ->
                k.toIntOrNull()?.let { value -> labelKey to value }
            }.sortedBy { it.second }
            if (parsed.isNotEmpty()) {
                return parsed.map { (labelKey, value) ->
                    mapOf("labelKey" to labelKey, "value" to value)
                }
            }
        }
        return emptyList()
    }

    private fun baseMap(
        def: EntityDef,
        value: String?,
        status: String,
        permission: String?,
        stale: Boolean,
        i18n: I18nBundle?,
        forceWritable: Boolean? = null,
        range: ValueRange? = null,
        writeLocked: Boolean = false,
    ): Map<String, Any?> {
        val writable = !writeLocked &&
            (forceWritable ?: (def.writable && (status == "ok" || status == "cached")))
        return EntityContract.enrich(
            mapOf(
                "id" to def.id,
                "group" to def.group,
                EntityContract.FIELD_SECTION to def.resolvedSection(),
                "domain" to def.domain.id,
                "labelKey" to def.resolvedLabelKey(),
                "hintKey" to def.resolvedHintKey(),
                "acronym" to def.acronym,
                "input" to def.input,
                "icon" to def.resolvedIcon(),
                "deviceClass" to def.deviceClass?.id,
                "unitOfMeasurement" to def.unitOfMeasurement?.id,
                "min" to (range?.min ?: def.min),
                "max" to (range?.max ?: def.max),
                "step" to (range?.step ?: def.step),
                "history" to def.history,
                "writable" to writable,
                "writeLocked" to writeLocked,
                "options" to optionMaps(def, i18n).ifEmpty { null },
                "value" to value,
                "valueMapId" to def.resolvedValueMapId(),
                "binary" to (def.input == "bool"),
                "status" to status,
                "permission" to permission,
                "needsPrivilege" to (status == "denied"),
                "stale" to stale,
                "writeOnly" to (def.input == "command"),
                EntityContract.FIELD_COMPOSITE to def.isComposite,
                EntityContract.FIELD_UPDATE to EntityContract.updatePolicy(def.isComposite),
            ),
        )
    }

    private fun sensor(
        objectId: String,
        group: String,
        value: String?,
        deviceClass: DeviceClass? = null,
        unitOfMeasurement: UnitOfMeasurement? = null,
        icon: String = "sensor",
        history: Boolean = false,
        /** valueMaps id for enum-like raw values (gear, ignition, …). */
        valueMapId: String? = null,
        /** Treat 0/1/on/off/true/false as localized On/Off. */
        binary: Boolean = false,
        section: String = "telemetry",
    ): Map<String, Any?> {
        val id = SENSOR_PREFIX + objectId
        val ok = value != null && value.isNotBlank()
        return EntityContract.enrich(
            mapOf(
                "id" to id,
                "group" to group,
                EntityContract.FIELD_SECTION to section,
                "domain" to EntityType.SENSOR.id,
                "labelKey" to id,
                "hintKey" to "$id.hint",
                "input" to "sensor",
                "icon" to icon,
                "deviceClass" to deviceClass?.id,
                "unitOfMeasurement" to unitOfMeasurement?.id,
                "writable" to false,
                "options" to null,
                "value" to if (ok) value else null,
                "valueMapId" to valueMapId,
                "binary" to binary,
                "status" to if (ok) "ok" else "unavailable",
                "permission" to null,
                "needsPrivilege" to false,
                "stale" to false,
                "history" to history,
                "persistEnabled" to false,
            ),
        )
    }
}
