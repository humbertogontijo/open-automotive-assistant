package cc.opencar.assistant.api

/**
 * Single product entity registry.
 *
 * - **Atomic (single property):** [id] and [bindingKey] are the VHAL property key
 *   (e.g. `MIRROR_FOLD`, `PERF_VEHICLE_SPEED`) so provenance is obvious.
 * - **Composite / multi-area:** HA-shaped ids (`climate.cabin`, `cover.window_driver`);
 *   [attributes] map semantic names → VHAL property keys.
 *
 * [group] = nav page; [section] = subsection within that page (not HA domain).
 */
data class EntityDef(
    val id: String,
    val domain: EntityType,
    /** Nav page id (`home`, `controls`, `drive`, …). */
    val group: String,
    /**
     * Subsection within [group] (e.g. `climate`, `lock`, `adas`).
     * UI sections by this field — not by [domain].
     */
    val section: String? = null,
    /** Single-binding entities: VHAL property key. Composite: null. */
    val bindingKey: String? = null,
    /** Composite only: attribute name → VHAL property key. */
    val attributes: Map<String, String> = emptyMap(),
    /**
     * Soft UI hint: bool | choice | command | int | float | text | sensor | climate | cover | …
     * Card family is selected by [domain]; this refines the widget inside simple cards.
     */
    val input: String = "bool",
    val optionKeys: List<Pair<String, Int>>? = null,
    val writable: Boolean = true,
    val deviceClass: DeviceClass? = null,
    val unitOfMeasurement: UnitOfMeasurement? = null,
    val acronym: String? = null,
    val lastKnown: Boolean = false,
    val valueMapId: String? = null,
    val labelKey: String? = null,
    val hintKey: String? = null,
    val icon: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val history: Boolean = false,
    /**
     * Preferred VHAL area for instanced covers (`cover.window_driver`, `cover.sunroof`, …).
     */
    val areaId: Int? = null,
    /**
     * Per-attribute area overrides (rare; prefer separate cover entities per area).
     */
    val attributeAreas: Map<String, Int> = emptyMap(),
) {
    val isComposite: Boolean get() = bindingKey == null && attributes.isNotEmpty()

    /** Object id segment after `domain.` when HA-shaped; otherwise the full [id]. */
    val objectId: String
        get() {
            val prefix = domain.id + "."
            return if (id.startsWith(prefix)) id.removePrefix(prefix) else id
        }

    fun resolvedSection(): String = section ?: domain.id

    fun resolvedLabelKey(): String = labelKey ?: "control.$id"
    fun resolvedHintKey(): String = hintKey ?: "control.$id.hint"
    fun resolvedValueMapId(): String = valueMapId ?: id
    fun resolvedIcon(): String = icon ?: objectId

    fun property(): VehicleProperty? =
        bindingKey?.let { EntityRegistry.property(it, areaId ?: 0) }

    fun attributeProperty(attr: String): VehicleProperty? {
        val key = attributes[attr] ?: return null
        val area = when {
            attr in EntityRegistry.GLOBAL_AREA_ATTRS -> 0
            attributeAreas.containsKey(attr) -> attributeAreas.getValue(attr)
            else -> areaId ?: 0
        }
        return EntityRegistry.property(key, area)
    }
}

object EntityRegistry {
    const val NS = "oaa"

    /** Attributes that are always area 0 even on instanced covers. */
    val GLOBAL_AREA_ATTRS: Set<String> = setOf("auto_close", "status", "open_height")

    fun property(bindingKey: String, areaId: Int = 0): VehicleProperty =
        VehicleProperty(NS, bindingKey, defaultAreaId = areaId)

    fun entityId(domain: EntityType, objectId: String): String = "${domain.id}.$objectId"

    // AOSP VehicleAreaSeat row-1 (HVAC_SEAT_VENTILATION areas on Antora).
    const val AREA_SEAT_ROW_1_LEFT = 0x1
    const val AREA_SEAT_ROW_1_RIGHT = 0x4

    // AOSP-style seat / window area ids used on Antora openings.
    const val AREA_WINDOW_ROW_1_LEFT = 0x10
    const val AREA_WINDOW_ROW_1_RIGHT = 0x40
    const val AREA_WINDOW_ROW_2_LEFT = 0x100
    const val AREA_WINDOW_ROW_2_RIGHT = 0x400
    /** Panoramic sunroof glass (VehicleAreaWindow.WINDOW_ROOF_TOP_1). */
    const val AREA_WINDOW_ROOF_TOP = 0x10000
    /** Sunshade / curtain (VehicleAreaWindow.WINDOW_ROOF_TOP_2). */
    const val AREA_WINDOW_ROOF_TOP_2 = 0x20000
    /** Power liftgate / trunk (VehicleAreaDoor rear). */
    const val AREA_DOOR_REAR = 0x20000000

    // ----- Composites -----
    // climate.cabin (+ pilot sensors) load from entities/standard-pilot.json via [EntityPackLoader].

    /** Cabin climate — definition lives in the declarative standard-pilot pack. */
    val CLIMATE: EntityDef
        get() = byId("climate.cabin")
            ?: error("climate.cabin missing from entities/standard-pilot.json")

    val DRIVETRAIN = EntityDef(
        id = entityId(EntityType.DRIVETRAIN, "vehicle"),
        domain = EntityType.DRIVETRAIN,
        group = "drive",
        section = "drive",
        bindingKey = null,
        attributes = mapOf(
            "gear" to "GEAR_SELECTION",
            "mode" to "DM_FUNC_DRIVE_MODE_SELECT",
            "regen" to "SETTING_FUNC_ENERGY_REGENERATION",
            "battery_hold" to "HYBRID_FUNC_BATTERY_CHARGE_MODE",
            "battery_save" to "HYBRID_FUNC_BATTERY_SAVE_MODE",
            "battery_mode" to "HYBRID_FUNC_BATTERY_MODE",
        ),
        input = "choice",
        valueMapId = "drive_mode",
        icon = "drive_mode",
        history = true,
        lastKnown = true,
    )

    val CHASSIS = EntityDef(
        id = entityId(EntityType.CHASSIS, "vehicle"),
        domain = EntityType.CHASSIS,
        group = "drive",
        section = "drive",
        bindingKey = null,
        attributes = mapOf(
            "brake_pedal" to "SETTING_BRAKE_PEDAL_STATUS",
            "esc" to "SETTING_FUNC_ESC_SPORT_MODE",
            "hdc" to "SETTING_FUNC_HDC_SWITCH",
            "auto_hold" to "SETTING_FUNC_AUTO_HOLD",
            "epb" to "SETTING_FUNC_PBC_DOUBLE_EPB_SWITCH",
            "parking_brake" to "PARKING_BRAKE_ON",
        ),
        input = "bool",
        icon = "brake",
        lastKnown = true,
    )

    val STEERING = EntityDef(
        id = entityId(EntityType.STEERING, "vehicle"),
        domain = EntityType.STEERING,
        group = "drive",
        section = "drive",
        bindingKey = null,
        attributes = mapOf(
            "assist_level" to "SETTING_FUNC_STEERING_ASSISTANCE_LEVEL",
            "sync_drive_mode" to "DM_FUNC_STEERING_WHEEL_FEEL_SYNC_DRIVEMODE",
            "intelligent" to "SETTING_FUNC_INTELLIGENT_STEERING_SWITCH",
            "custom_key" to "BCM_FUNC_CUSTOM_KEY",
        ),
        input = "choice",
        optionKeys = listOf(
            "opt.steer_assist_level.1" to 1,
            "opt.steer_assist_level.2" to 2,
            "opt.steer_assist_level.3" to 3,
        ),
        valueMapId = "steer_assist_level",
        icon = "steer",
        lastKnown = true,
    )

    val CHARGER = EntityDef(
        id = entityId(EntityType.CHARGER, "vehicle"),
        domain = EntityType.CHARGER,
        group = "energy",
        section = "charge",
        bindingKey = null,
        attributes = mapOf(
            "plug" to "CHARGE_FUNC_CHARGING_PLUG_STATE",
            "current" to "CHARGE_FUNC_CHARGING_CURRENT",
            "limit" to "CHARGE_FUNC_CHARGING_CURRENT_MAX",
            "switch" to "CHARGE_FUNC_AC_CHARGING",
            "pre_now" to "CHARGE_FUNC_PRE_CHARGING_IMMEDIATELY",
            "soc_max" to "CHARGE_FUNC_CHARGING_SOC_MAX",
            "soc_min" to "CHARGE_FUNC_CHARGING_SOC_MIN",
            "discharge_soc" to "CHARGE_FUNC_DISCHARGING_SOC",
            "v2l" to "CHARGE_FUNC_DISCHARGING_SWITCH_V2L",
            "v2v" to "CHARGE_FUNC_DISCHARGING_SWITCH_V2V",
            "parking" to "CHARGE_FUNC_PARKING",
            "estimated_time" to "CHARGE_FUNC_CHARGING_ESTIMATED_TIME",
            "energy" to "CHARGE_FUNC_CHARGING_ENERGY",
            "work_current" to "CHARGE_FUNC_CHARGING_WORK_CURRENT",
            "work_voltage" to "CHARGE_FUNC_CHARGING_WORK_VOLTAGE",
            "external_light" to "CHARGE_FUNC_EXTERNAL_CHARGING_LIGHT",
        ),
        input = "bool",
        icon = "charge",
        history = true,
        lastKnown = true,
    )

    val EV_BATTERY = EntityDef(
        id = entityId(EntityType.EV_BATTERY, "main"),
        domain = EntityType.EV_BATTERY,
        group = "energy",
        section = "hybrid",
        bindingKey = null,
        attributes = mapOf(
            "percent" to "TYPE_EV_BATTERY_PERCENTAGE",
            "level_raw" to "EV_BATTERY_LEVEL",
            "temp_c" to "SENSOR_TYPE_EV_BATTERY_TEMP",
            "hybrid_soc" to "HYBRID_FUNC_BATTERY_SOC",
        ),
        input = "sensor",
        icon = "battery",
        history = true,
        writable = false,
    )

    val HUD = EntityDef(
        id = entityId(EntityType.HUD, "main"),
        domain = EntityType.HUD,
        group = "display",
        bindingKey = null,
        attributes = mapOf(
            "active" to "SETTING_FUNC_HUD_ACTIVE",
            "snow" to "SETTING_FUNC_HUD_SNOW_MODE",
            "ar" to "SETTING_FUNC_HUD_AR_ENGINE",
            "display_mode" to "SETTING_FUNC_HUD_DISPLAY_MODE",
            "angle" to "SETTING_FUNC_HUD_ANGLE_ADJUST",
        ),
        input = "bool",
        icon = "hud",
        lastKnown = true,
    )

    val LIGHT = EntityDef(
        id = entityId(EntityType.LIGHT, "ambient"),
        domain = EntityType.LIGHT,
        group = "lights",
        bindingKey = null,
        attributes = mapOf(
            "color" to "SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR",
            "brightness" to "SETTING_FUNC_AMBIENCE_LIGHT_INTENSITY_SET",
        ),
        input = "light",
        optionKeys = listOf(
            "opt.ambience_main_color.2" to 2,
            "opt.ambience_main_color.3" to 3,
            "opt.ambience_main_color.4" to 4,
        ),
        icon = "light",
        lastKnown = true,
        min = 0f,
        max = 100f,
        step = 1f,
        valueMapId = "ambience_main_color",
    )

    // ----- Covers (position 0–100 except trunk) -----

    val COVER_WINDOW_DRIVER = cover(
        objectId = "window_driver",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROW_1_LEFT,
        deviceClass = DeviceClass.WINDOW,
        icon = "window",
    )
    val COVER_WINDOW_PASSENGER = cover(
        objectId = "window_passenger",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROW_1_RIGHT,
        deviceClass = DeviceClass.WINDOW,
        icon = "window",
    )
    val COVER_WINDOW_REAR_LEFT = cover(
        objectId = "window_rear_left",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROW_2_LEFT,
        deviceClass = DeviceClass.WINDOW,
        icon = "window",
    )
    val COVER_WINDOW_REAR_RIGHT = cover(
        objectId = "window_rear_right",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROW_2_RIGHT,
        deviceClass = DeviceClass.WINDOW,
        icon = "window",
    )
    val COVER_SUNROOF = cover(
        objectId = "sunroof",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROOF_TOP,
        deviceClass = DeviceClass.WINDOW,
        icon = "window",
    )
    val COVER_SUNSHADE = cover(
        objectId = "sunshade",
        bindingKey = "WINDOW_POS",
        areaId = AREA_WINDOW_ROOF_TOP_2,
        deviceClass = DeviceClass.SHADE,
        icon = "window",
    )

    /** Power liftgate: open/close via DOOR_MOVE; status via BCM enum (no live position). */
    val COVER_TRUNK = EntityDef(
        id = entityId(EntityType.COVER, "trunk"),
        domain = EntityType.COVER,
        group = "controls",
        bindingKey = null,
        attributes = mapOf(
            "status" to "BCM_FUNC_TRUNK_DOOR_STATUS",
            "move" to "DOOR_MOVE",
        ),
        input = "cover",
        icon = "cabin",
        lastKnown = true,
        deviceClass = DeviceClass.GARAGE,
        areaId = AREA_DOOR_REAR,
        valueMapId = "cover",
    )

    // Camera entities are virtual (Camera2 + platform.json roles), not VHAL-bound.
    val CAMERA_FRONT = camera("front")
    val CAMERA_REAR = camera("rear")
    val CAMERA_LEFT = camera("left")
    val CAMERA_RIGHT = camera("right")
    val CAMERAS: List<EntityDef> = listOf(
        CAMERA_FRONT, CAMERA_RIGHT, CAMERA_REAR, CAMERA_LEFT,
    )

    private val kotlinBuiltin: List<EntityDef> = listOf(
        DRIVETRAIN,
        CHASSIS,
        STEERING,
        CHARGER,
        EV_BATTERY,
        HUD,
        LIGHT,
        COVER_WINDOW_DRIVER,
        COVER_WINDOW_PASSENGER,
        COVER_WINDOW_REAR_LEFT,
        COVER_WINDOW_REAR_RIGHT,
        COVER_SUNROOF,
        COVER_SUNSHADE,
        COVER_TRUNK,
        CAMERA_FRONT,
        CAMERA_RIGHT,
        CAMERA_REAR,
        CAMERA_LEFT,

        // Cover siblings
        e("SETTING_FUNC_AUTO_CLOSE_WINDOW", "controls", EntityType.SWITCH, "bool",
            lastKnown = true, icon = "window",
        ),
        e("BCM_FUNC_SUNROOF_TILT", "controls", EntityType.SWITCH, "bool",
            lastKnown = true, icon = "window",
        ),
        e("SETTING_FUNC_TRUNK_OPENING_POSITION", "controls", EntityType.NUMBER, "int",
            icon = "cabin", min = 0f, max = 100f, step = 1f, lastKnown = true,
        ),

        // Drive extras
        e("SETTING_FUNC_CST_SWITCH", "drive", EntityType.SWITCH, "bool", acronym = "CST", lastKnown = true, icon = "drive"),

        // HVAC extras — boosts / presets, not climate modes.
        e("HVAC_MAX_DEFROST_ON", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_MAX_AC_ON", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_FUNC_ECO_SWITCH", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_FUNC_AUTO_DRY", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_RAPID_COOLING", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_RAPID_HEATING", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_ELECTRIC_DEFROSTER_ON", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_AUTO_RECIRC_ON", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("HVAC_FUNC_AUTO_SEAT_VENTILATION", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "seat"),

        // Seat ventilation — fan domain (separate from climate cabin fan).
        fan(
            objectId = "seat_driver",
            bindingKey = "HVAC_SEAT_VENTILATION",
            areaId = AREA_SEAT_ROW_1_LEFT,
            icon = "seat",
        ),
        fan(
            objectId = "seat_passenger",
            bindingKey = "HVAC_SEAT_VENTILATION",
            areaId = AREA_SEAT_ROW_1_RIGHT,
            icon = "seat",
        ),

        e("SCENE_FUNC_PARKING_COMFORT_SWITCH", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("SCENE_FUNC_NAP_MODE", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),
        e("SETTING_SPACE_CAPSULE_SWITCH", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "climate"),

        e("MIRROR_FOLD", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "cabin"),
        e("SETTING_FUNC_MIRROR_AUTO_FOLDING", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "cabin"),

        // Real locks (actuators). Policy helpers stay switch.* (approach_unlock, …).
        lock(
            objectId = "central",
            bindingKey = "SETTING_FUNC_CENTRAL_LOCK",
            icon = "lock",
        ),
        lock(
            objectId = "windows",
            bindingKey = "WINDOW_LOCK",
            icon = "lock",
        ),

        e("SETTING_FUNC_LANE_KEEPING_AID", "adas", EntityType.SWITCH, "bool", acronym = "LKA", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_LANE_KEEPING_AID_WARNING", "adas", EntityType.SWITCH, "bool", acronym = "LDW", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_EMGY_LANE_KEEP_AID", "adas", EntityType.SWITCH, "bool", acronym = "ELKA", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_AUTONOMOUS_EMERGENCY_BRAKING", "adas", EntityType.SWITCH, "bool", acronym = "AEB", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_FORWARD_COLLISION_WARN_SNVTY", "adas", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.fcw.0" to 0,
                "opt.fcw.1" to 1,
                "opt.fcw.2" to 2,
                "opt.fcw.3" to 3,
            ),
            acronym = "FCW", icon = "adas",
        ),
        e("SETTING_FUNC_REAR_CROSS_TRAFFIC_ALERT", "adas", EntityType.SWITCH, "bool", acronym = "RCTA", lastKnown = true, icon = "adas"),
        e("rcta_volume", "adas", EntityType.NUMBER, "int",
            icon = "adas", min = 0f, max = 3f, step = 1f, lastKnown = true,
        ),
        e("SETTING_FUNC_REAR_COLLISION_WARNING", "adas", EntityType.SWITCH, "bool", acronym = "RCW", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_FCDA_STATUS", "adas", EntityType.SWITCH, "bool", acronym = "FCDA", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_DOOR_OPEN_WARN_ACTIVE", "adas", EntityType.SWITCH, "bool", acronym = "DOW", lastKnown = true, icon = "adas"),
        e("SETTING_INTELLIGENT_DRIVING_ASSISTANCE_MODE", "adas", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.idas_mode.1" to 1,
                "opt.idas_mode.2" to 2,
            ),
            acronym = "IDAS", icon = "adas", lastKnown = true,
        ),
        e("SETTING_FUNC_SPEED_LIMIT_WARNING_MODE", "adas", EntityType.SWITCH, "bool", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_SPEED_WARNING_MAX", "adas", EntityType.SENSOR, "sensor",
            writable = false,
            deviceClass = DeviceClass.SPEED,
            unitOfMeasurement = UnitOfMeasurement.KM_PER_HOUR,
            icon = "adas", lastKnown = true,
        ),
        e("speed_limit_update", "adas", EntityType.SWITCH, "bool", lastKnown = true, icon = "adas"),
        e("SETTING_FUNC_LANE_CHANGE_WARNING_MODE", "adas", EntityType.SWITCH, "bool", lastKnown = true, icon = "adas"),
        e("DMS_DPS_SWITCH", "adas", EntityType.SWITCH, "bool", acronym = "DMS", lastKnown = true, icon = "adas"),

        // Access *policy* — not lock domain.
        e("SETTING_FUNC_APPROACH_UNLOCK", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "lock"),
        e("SETTING_FUNC_AWAY_LOCK", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "lock"),
        e("SETTING_FUNC_AUDIBLE_LOCKING_FEEDBACK", "controls", EntityType.SWITCH, "bool", icon = "lock"),
        e("SETTING_FUNC_KEYLESS_UNLOCKING", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "lock"),
        e("SETTING_FUNC_TWOSTEP_UNLOCKING", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "lock"),
        e("SETTING_FUNC_P_GEAR_UNLOCK", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "lock"),
        e("SETTING_FUNC_EASY_INGRESS_EGRESS", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "seat"),
        e("SETTING_FUNC_CAR_LOCATOR_REMINDER_MODE", "controls", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.vehicle_locator_mode.1" to 1,
                "opt.vehicle_locator_mode.2" to 2,
                "opt.vehicle_locator_mode.3" to 3,
            ),
            lastKnown = true, icon = "cabin",
        ),

        e("SETTING_FUNC_LAMP_AUTOMATIC_COURTESY_LIGHT", "lights", EntityType.SWITCH, "bool", icon = "light"),
        e("SETTING_FUNC_LAMP_APPROACH_LIGHT", "lights", EntityType.SWITCH, "bool", icon = "light"),
        e("SETTING_FUNC_LAMP_EXTERIOR_LIGHT_CONTROL", "lights", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.exterior_light.0" to 0,
                "opt.exterior_light.1" to 1,
                "opt.exterior_light.2" to 2,
                "opt.exterior_light.3" to 3,
            ),
            lastKnown = true, icon = "light",
        ),
        e("FOG_LIGHTS_SWITCH", "lights", EntityType.SWITCH, "bool", icon = "light"),
        e("hazard_lights", "lights", EntityType.SWITCH, "bool", icon = "light"),
        e("high_beam", "lights", EntityType.SWITCH, "bool", icon = "light"),
        e("SETTING_FUNC_LAMP_LOW_BEAM_VERTICAL_LEVEL", "lights", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.headlight_height.0" to 0,
                "opt.headlight_height.1" to 1,
                "opt.headlight_height.2" to 2,
                "opt.headlight_height.3" to 3,
            ),
            lastKnown = true, icon = "light",
        ),
        e("SETTING_FUNC_LAMP_HOME_SAFE_LIGHT", "lights", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.home_safe_light.0" to 0,
                "opt.home_safe_light.1" to 1,
                "opt.home_safe_light.2" to 2,
                "opt.home_safe_light.3" to 3,
                "opt.home_safe_light.4" to 4,
                "opt.home_safe_light.5" to 5,
                "opt.home_safe_light.6" to 6,
            ),
            lastKnown = true, icon = "light",
        ),
        e("SETTING_FUNC_DAYMODE_SETTING", "lights", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.day_mode.1" to 1,
                "opt.day_mode.2" to 2,
                "opt.day_mode.3" to 3,
            ),
            lastKnown = true, icon = "light",
        ),
        e("SETTING_FUNC_NIGHTMODE", "lights", EntityType.SWITCH, "bool", lastKnown = true, icon = "light"),

        e("display_brightness", "display", EntityType.NUMBER, "int",
            icon = "light", min = 0f, max = 100f, step = 1f, lastKnown = true,
        ),
        e("display_auto_brightness", "display", EntityType.SWITCH, "bool", lastKnown = true, icon = "light"),
        e("rain_sensor_sensitivity", "controls", EntityType.NUMBER, "int",
            icon = "cabin", min = 0f, max = 5f, step = 1f, lastKnown = true,
        ),
        e("auto_rear_wiper", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "cabin"),
        e("wireless_charge", "controls", EntityType.SWITCH, "bool", lastKnown = true, icon = "charge"),
        e("wireless_charge_state", "controls", EntityType.SENSOR, "sensor",
            writable = false, icon = "charge",
        ),
        e("tire_pressure", "vehicle", EntityType.SENSOR, "sensor",
            writable = false, icon = "sensor", history = true,
        ),
        e("tire_warning", "vehicle", EntityType.SENSOR, "sensor",
            writable = false, icon = "sensor",
        ),
        e("SETTING_FUNC_ESM_VOLUME", "sound", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.esm_volume.0" to 0,
                "opt.esm_volume.1" to 1,
                "opt.esm_volume.2" to 2,
                "opt.esm_volume.3" to 3,
            ),
            acronym = "AVAS", icon = "system", lastKnown = true,
        ),
        e("SETTING_FUNC_ESM_SOUND_TYPE", "sound", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.esm_sound.0" to 1,
                "opt.esm_sound.1" to 2,
                "opt.esm_sound.2" to 3,
            ),
            acronym = "AVAS", icon = "system", lastKnown = true,
        ),
        e("SETTING_FUNC_AUDIO_MEDIA_VOLUME", "sound", EntityType.NUMBER, "int",
            icon = "sound", min = 0f, max = 39f, step = 1f, lastKnown = true,
        ),
        e("SETTING_FUNC_AUDIO_COMPENSATION_LEVEL", "sound", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.speed_volume.0" to 0,
                "opt.speed_volume.1" to 1,
                "opt.speed_volume.2" to 2,
                "opt.speed_volume.3" to 3,
            ),
            lastKnown = true, icon = "system",
        ),
        e("warning_volume", "sound", EntityType.NUMBER, "int",
            icon = "sound", min = 0f, max = 39f, step = 1f, lastKnown = true,
        ),
        e("voice_broadcast", "assistant", EntityType.SELECT, "choice",
            lastKnown = true, icon = "system",
        ),
        e("SETTING_FUNC_USB_SWITCH", "vehicle", EntityType.SELECT, "choice",
            optionKeys = listOf(
                "opt.usb_mode.0" to 0,
                "opt.usb_mode.1" to 1,
                "opt.usb_mode.2" to 2,
            ),
            icon = "usb",
        ),
        e("VR_ACTIVATED", "assistant", EntityType.SWITCH, "bool", lastKnown = true, icon = "system"),
    )

    private val declarativePilot: List<EntityDef> = runCatching {
        EntityPackLoader.loadClasspath("entities/standard-pilot.json")
    }.getOrElse { emptyList() }

    /** Product catalog: declarative packs win on id, then Kotlin builtins. */
    val ALL: List<EntityDef> = EntityPackLoader.merge(declarativePilot, kotlinBuiltin)

    private val byId: Map<String, EntityDef> = ALL.associateBy { it.id }

    private val byBindingKey: Map<String, EntityDef> = buildMap {
        for (def in ALL) {
            def.bindingKey?.let { putIfAbsent(it, def) }
            for (bk in def.attributes.values) putIfAbsent(bk, def)
        }
    }

    fun byId(id: String): EntityDef? = byId[id]

    /** Entity for a session event key: an entity id, or a binding key (atomic or composite attribute). */
    fun resolveBinding(bindingKey: String): EntityDef? = byId[bindingKey] ?: byBindingKey[bindingKey]

    private fun camera(role: String) = EntityDef(
        id = entityId(EntityType.CAMERA, role),
        domain = EntityType.CAMERA,
        group = "cameras",
        bindingKey = null,
        input = "camera",
        writable = false,
        icon = "camera",
    )

    /**
     * Position cover bound to a single `window_pos` (or similar) property + area.
     * Product state is open/closed; `current_position` is 0–100.
     */
    private fun cover(
        objectId: String,
        bindingKey: String,
        areaId: Int,
        deviceClass: DeviceClass,
        icon: String,
    ): EntityDef {
        val id = entityId(EntityType.COVER, objectId)
        return EntityDef(
            id = id,
            domain = EntityType.COVER,
            group = "controls",
            section = "window",
            bindingKey = bindingKey,
            input = "cover",
            icon = icon,
            lastKnown = true,
            areaId = areaId,
            deviceClass = deviceClass,
            min = 0f,
            max = 100f,
            step = 1f,
            valueMapId = "cover",
        )
    }

    /** Seat / cabin fan with discrete levels (HA fan presets). */
    private fun fan(
        objectId: String,
        bindingKey: String,
        areaId: Int,
        icon: String,
    ): EntityDef {
        val id = entityId(EntityType.FAN, objectId)
        return EntityDef(
            id = id,
            domain = EntityType.FAN,
            group = "controls",
            section = "seat",
            bindingKey = bindingKey,
            input = "choice",
            optionKeys = listOf(
                "opt.hvac_seat_vent.0" to 0,
                "opt.hvac_seat_vent.1" to 1,
                "opt.hvac_seat_vent.2" to 2,
                "opt.hvac_seat_vent.3" to 3,
            ),
            icon = icon,
            lastKnown = true,
            areaId = areaId,
            valueMapId = "hvac_seat_vent",
            min = 0f,
            max = 3f,
            step = 1f,
        )
    }

    /** Actuator lock — locked (1) / unlocked (0). */
    private fun lock(
        objectId: String,
        bindingKey: String,
        icon: String,
    ): EntityDef {
        val id = entityId(EntityType.LOCK, objectId)
        return EntityDef(
            id = id,
            domain = EntityType.LOCK,
            group = "controls",
            section = "lock",
            bindingKey = bindingKey,
            input = "choice",
            optionKeys = listOf(
                "lock.unlocked" to 0,
                "lock.locked" to 1,
            ),
            icon = icon,
            lastKnown = true,
            valueMapId = "lock",
        )
    }

    /**
     * Atomic widget bound to one VHAL property.
     *
     * [propertyKey] is both the entity [EntityDef.id] and [EntityDef.bindingKey].
     */
    private fun e(
        propertyKey: String,
        group: String,
        domain: EntityType,
        input: String,
        section: String? = null,
        optionKeys: List<Pair<String, Int>>? = null,
        writable: Boolean = true,
        deviceClass: DeviceClass? = null,
        unitOfMeasurement: UnitOfMeasurement? = null,
        acronym: String? = null,
        lastKnown: Boolean = false,
        icon: String? = null,
        min: Float? = null,
        max: Float? = null,
        step: Float? = null,
        history: Boolean = false,
        areaId: Int? = null,
    ): EntityDef {
        val key = propertyKey
        return EntityDef(
            id = key,
            domain = domain,
            group = group,
            section = section ?: CatalogEntityFactory.familyOf(key),
            bindingKey = key,
            input = input,
            optionKeys = optionKeys,
            writable = writable,
            deviceClass = deviceClass,
            unitOfMeasurement = unitOfMeasurement,
            acronym = acronym,
            lastKnown = lastKnown,
            icon = icon,
            min = min,
            max = max,
            step = step,
            history = history,
            areaId = areaId,
            labelKey = "control.$key",
            hintKey = "control.$key.hint",
        )
    }
}
