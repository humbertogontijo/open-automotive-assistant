package cc.opencar.assistant.api

/**
 * Synthesizes atomic [EntityDef]s from the platform VHAL catalog so every
 * property key can appear as a product entity (id = property key).
 *
 * Skips keys already claimed by curated [EntityRegistry] atomics or composite
 * attributes (and multi-area covers/fans that share a binding key).
 */
object CatalogEntityFactory {

    fun claimedBindingKeys(defs: List<EntityDef> = EntityRegistry.ALL): Set<String> {
        val out = mutableSetOf<String>()
        for (def in defs) {
            def.bindingKey?.let { out += it }
            out += def.attributes.values
        }
        return out
    }

    /**
     * One entity per catalog property whose key is not already claimed.
     * [id] / [EntityDef.bindingKey] = property key.
     */
    fun fromCatalog(
        entries: List<CatalogEntry>,
        claimed: Set<String> = claimedBindingKeys(),
    ): List<EntityDef> {
        val out = ArrayList<EntityDef>(entries.size)
        val seen = claimed.toMutableSet()
        for (entry in entries) {
            val key = entry.property.key.ifBlank { entry.name }
            if (key.isBlank() || !seen.add(key)) continue
            val family = familyOf(key)
            val (domain, input) = domainAndInput(key, entry.writable || entry.writeLocked, entry.vhalType)
            out += EntityDef(
                id = key,
                domain = domain,
                group = groupOf(family),
                section = family,
                bindingKey = key,
                input = input,
                writable = entry.writable && domain != EntityType.SENSOR,
                lastKnown = entry.writable,
                icon = iconOf(family),
                labelKey = "control.$key",
                hintKey = "control.$key.hint",
                areaId = entry.areaIds.firstOrNull()?.takeIf { it != 0 },
            )
        }
        return out
    }

    fun familyOf(name: String): String {
        val n = name.uppercase()
        return when {
            n.contains("OBD2") || n.startsWith("OBD_") -> "obd2"
            n.startsWith("SCENE_") || n.contains("NAP_MODE") || n.contains("SPACE_CAPSULE") ||
                n.contains("PARKING_COMFORT") -> "scene"
            n.startsWith("LAMP_") || n.contains("LIGHT_CONTROL") ||
                (n.contains("LAMP") && !n.contains("FAULT")) -> "light"
            n.contains("DMS") || n.contains("FCDA") || n.contains("DOOR_OPEN_WARN") ||
                n.contains("RCTA") || n.contains("RCW") || n.contains("ELKA") ||
                n.contains("IDAS") || n.contains("INTELLIGENT_DRIVING") ||
                n.contains("LANE") || n.contains("AEB") || n.contains("COLLISION") ||
                n.contains("CROSS_TRAFFIC") || n.contains("FCW") || n.startsWith("PAS_") -> "adas"
            n.contains("SUNROOF") || n.contains("WINDOW") -> "window"
            n.contains("LOCK") || n.contains("UNLOCK") -> "lock"
            n.contains("HUD") -> "hud"
            n.contains("AMBIENCE") -> "ambience"
            n.contains("BRIGHTNESS") || n.contains("BACKLIGHT") -> "brightness"
            n.contains("SEAT") || n.contains("BELT") || n.contains("OCCUPANCY") -> "seat"
            n.contains("MIRROR") -> "mirror"
            n.startsWith("HYBRID_") -> "hybrid"
            n.startsWith("CHARGE_") -> "charge"
            n.startsWith("HVAC_") -> "hvac"
            n.startsWith("DM_") || n.contains("DRIVE_MODE") -> "drive"
            n.startsWith("INFO_") || n.startsWith("PERF_") || n.contains("GEAR") ||
                n.contains("IGNITION") || n.contains("RANGE") || n.contains("BATTERY") -> "telemetry"
            n.startsWith("SETTING_FUNC_") || n.startsWith("SETTING_") -> "setting"
            else -> "other"
        }
    }

    fun groupOf(family: String): String = when (family) {
        "hvac", "window", "lock", "seat", "mirror", "scene", "setting" -> "controls"
        "adas" -> "adas"
        "light", "ambience" -> "lights"
        "hud", "brightness" -> "display"
        "charge", "hybrid" -> "energy"
        "drive" -> "drive"
        "telemetry" -> "home"
        "obd2", "other" -> "vehicle"
        else -> "vehicle"
    }

    private fun iconOf(family: String): String = when (family) {
        "hvac", "scene" -> "climate"
        "window" -> "window"
        "lock" -> "lock"
        "seat" -> "seat"
        "mirror" -> "cabin"
        "adas" -> "adas"
        "light", "ambience", "brightness" -> "light"
        "hud" -> "display"
        "charge", "hybrid" -> "charge"
        "drive" -> "drive"
        "telemetry" -> "sensor"
        else -> "sensor"
    }

    private fun domainAndInput(key: String, writable: Boolean, type: Int?): Pair<EntityType, String> {
        val n = key.uppercase()
        if (!writable) return EntityType.SENSOR to "sensor"
        if (type != null && type !in SCALAR_TYPES) return EntityType.SENSOR to "sensor"
        return when {
            n.endsWith("_ON") || n.contains("SWITCH") || n.endsWith("_ACTIVE") ||
                n.contains("_ENABLE") || n.endsWith("_MODE") && !n.contains("SELECT") ->
                EntityType.SWITCH to "bool"
            n.contains("VOLUME") || n.contains("BRIGHTNESS") || n.contains("HEIGHT") ||
                n.contains("INTENSITY") || n.contains("LEVEL") || n.contains("CURRENT") ||
                n.contains("SOC") || n.contains("TEMPERATURE_SET") || n.contains("FAN_SPEED") ->
                EntityType.NUMBER to "int"
            n.contains("SELECT") || n.contains("DIRECTION") || n.contains("COLOR") ||
                n.contains("TYPE") && n.startsWith("SETTING") ->
                EntityType.SELECT to "choice"
            type == TYPE_INT32 || type == TYPE_INT64 -> EntityType.NUMBER to "int"
            type == TYPE_FLOAT -> EntityType.NUMBER to "float"
            else -> EntityType.SWITCH to "bool"
        }
    }

    // VehiclePropertyType bytes ([CatalogEntry.vhalType]).
    private const val TYPE_BOOLEAN = 0x20
    private const val TYPE_INT32 = 0x40
    private const val TYPE_INT64 = 0x50
    private const val TYPE_FLOAT = 0x60
    private val SCALAR_TYPES = setOf(TYPE_BOOLEAN, TYPE_INT32, TYPE_INT64, TYPE_FLOAT)
}
