package cc.opencar.assistant.protocol

import kotlin.math.ceil

/**
 * Home Assistant–style services per entity domain, called by shortcut and routine actions
 * (`{type: "service", service: "light.turn_on", entityId, data}`) and listed at
 * [OaaPaths.SERVICES].
 *
 * Domains Home Assistant also has (switch, light, cover, climate, media_player, …) copy its
 * service names and fields. Car-only domains (drivetrain, chassis, steering, charger, hud)
 * define their own. A call resolves to the control-write values the car accepts on
 * `POST /api/controls/{id}`, written in order.
 */
object OaaServices {

    /**
     * One service field. [min] / [max] / [step] and options can come from the target entity:
     * the `*From` keys name an entity field (`min`, `options`, …) or attribute (`min_temp`, `hvac_modes`).
     */
    data class Field(
        val key: String,
        /** `boolean` | `number` | `select` | `color` | `text`. */
        val type: String,
        val required: Boolean = false,
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val minFrom: String? = null,
        val maxFrom: String? = null,
        val stepFrom: String? = null,
        val optionsFrom: String? = null,
        /** i18n prefix for option labels (`climate.mode.` + value). */
        val optionLabelPrefix: String? = null,
        /** Attribute the entity must expose for this field to apply. */
        val requires: String? = null,
        val default: Any? = null,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("key" to key, "type" to type).apply {
            if (required) put("required", true)
            min?.let { put("min", it) }
            max?.let { put("max", it) }
            step?.let { put("step", it) }
            minFrom?.let { put("minFrom", it) }
            maxFrom?.let { put("maxFrom", it) }
            stepFrom?.let { put("stepFrom", it) }
            optionsFrom?.let { put("optionsFrom", it) }
            optionLabelPrefix?.let { put("optionLabelPrefix", it) }
            requires?.let { put("requires", it) }
            default?.let { put("default", it) }
        }
    }

    class Service internal constructor(
        val domain: String,
        val name: String,
        val fields: List<Field>,
        /** Attribute the entity must expose for this service to apply. */
        val requires: String?,
        /** True when resolving reads the entity's current value, options or range. */
        val readsEntity: Boolean,
        private val resolver: (Call) -> List<String>,
    ) {
        val id: String get() = "$domain.$name"

        fun resolve(call: Call): List<String> = resolver(call)

        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("service" to name).apply {
            if (fields.isNotEmpty()) put("fields", fields.map { it.toMap() })
            requires?.let { put("requires", it) }
        }
    }

    /** Service data plus the target entity row as `/api/entities` serves it. */
    class Call(val data: Map<String, Any?>, val entity: Map<String, Any?>) {
        val value: String? get() = entity["value"]?.toString()

        val isOn: Boolean
            get() {
                val v = value?.trim()?.lowercase() ?: return false
                return v.toDoubleOrNull()?.let { it != 0.0 } ?: (v !in OFF_STATES)
            }

        fun str(key: String): String? = data[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        fun num(key: String): Double? = when (val v = data[key]) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }

        fun bool(key: String): Boolean? = when (val v = data[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase()) {
                "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> null
            }
            else -> null
        }

        fun requireStr(key: String): String = str(key) ?: throw IllegalArgumentException("$key required")
        fun requireNum(key: String): Double = num(key) ?: throw IllegalArgumentException("$key required")
        fun requireBool(key: String): Boolean = bool(key) ?: throw IllegalArgumentException("$key required")

        /** Option values in the order the entity lists them. */
        fun options(): List<String> = (entity["options"] as? List<*>).orEmpty().mapNotNull { o ->
            when (o) {
                is Map<*, *> -> o["value"]?.toString()
                null -> null
                else -> o.toString()
            }
        }

        /** Discrete levels: numeric options, else `min..max` by `step`. */
        fun levels(): List<Double> {
            val fromOptions = options().mapNotNull { it.toDoubleOrNull() }.sorted()
            if (fromOptions.isNotEmpty()) return fromOptions
            val min = (entity["min"] as? Number)?.toDouble() ?: return emptyList()
            val max = (entity["max"] as? Number)?.toDouble() ?: return emptyList()
            val step = (entity["step"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 1.0
            return generateSequence(min) { it + step }.takeWhile { it <= max + 1e-9 }.take(MAX_LEVELS).toList()
        }
    }

    private val OFF_STATES = setOf("", "off", "false", "closed", "close", "idle", "paused", "standby", "none")
    private const val MAX_LEVELS = 256

    /** Whole numbers without a decimal point (int-typed properties reject `22.0`). */
    fun formatNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun onOff(on: Boolean) = if (on) "1" else "0"

    private fun rgb(raw: Any?): String? = when (raw) {
        null -> null
        is List<*> -> raw.joinToString(",") { (it as? Number)?.toInt()?.toString() ?: it.toString() }
        else -> raw.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** Level for an HA fan percentage: `ordered[ceil(pct * n / 100) - 1]` over the non-zero levels. */
    private fun levelForPercentage(levels: List<Double>, pct: Double): Double {
        if (levels.isEmpty()) throw IllegalArgumentException("entity has no speed levels")
        val off = levels.first()
        val speeds = levels.drop(1)
        if (pct <= 0 || speeds.isEmpty()) return off
        val idx = (ceil(pct.coerceAtMost(100.0) * speeds.size / 100.0).toInt() - 1).coerceIn(0, speeds.lastIndex)
        return speeds[idx]
    }

    private fun stepLevel(call: Call, delta: Int): List<String> {
        val levels = call.levels()
        if (levels.isEmpty()) throw IllegalArgumentException("entity has no speed levels")
        val cur = call.value?.toDoubleOrNull() ?: levels.first()
        val idx = levels.indexOfFirst { it >= cur - 1e-9 }.takeIf { it >= 0 } ?: levels.lastIndex
        return listOf(formatNumber(levels[(idx + delta).coerceIn(0, levels.lastIndex)]))
    }

    private fun cycleOption(call: Call, pick: (List<String>, Int, Boolean) -> Int): List<String> {
        val opts = call.options()
        if (opts.isEmpty()) throw IllegalArgumentException("entity has no options")
        val cycle = call.bool("cycle") ?: true
        val idx = pick(opts, opts.indexOf(call.value), cycle).coerceIn(0, opts.lastIndex)
        return listOf(opts[idx])
    }

    private class DomainBuilder(val domain: String) {
        val services = mutableListOf<Service>()

        fun service(
            name: String,
            vararg fields: Field,
            requires: String? = null,
            readsEntity: Boolean = false,
            resolve: (Call) -> List<String>,
        ) {
            services += Service(domain, name, fields.toList(), requires, readsEntity, resolve)
        }

        /** `attr:1` / `attr:0` from an `enabled` field. */
        fun boolAttr(name: String, attr: String) =
            service(name, Field("enabled", "boolean", required = true, default = true), requires = attr) {
                listOf("$attr:${onOff(it.requireBool("enabled"))}")
            }

        fun numberAttr(name: String, attr: String, field: Field) =
            service(name, field, requires = attr) { listOf("$attr:${formatNumber(it.requireNum(field.key))}") }
    }

    private fun domain(id: String, block: DomainBuilder.() -> Unit): Pair<String, List<Service>> =
        id to DomainBuilder(id).apply(block).services.toList()

    private val cycleField = Field("cycle", "boolean", default = true)

    /** Domain id → services, in display order. */
    val DOMAINS: Map<String, List<Service>> = linkedMapOf(
        // --- Home Assistant domains ---
        domain("switch") {
            service("turn_on") { listOf("1") }
            service("turn_off") { listOf("0") }
            service("toggle", readsEntity = true) { listOf(onOff(!it.isOn)) }
        },
        domain("light") {
            service(
                "turn_on",
                Field("brightness", "number", min = 0.0, max = 255.0, step = 1.0, requires = "brightness"),
                Field("rgb_color", "color", requires = "rgb_color"),
                Field("effect", "select", optionsFrom = "options", requires = "effect"),
            ) { call ->
                val writes = listOfNotNull(
                    call.str("effect")?.let { "effect:$it" },
                    rgb(call.data["rgb_color"])?.let { "rgb_color:$it" },
                    call.num("brightness")?.let { "brightness:${formatNumber(it.coerceIn(0.0, 255.0))}" },
                )
                writes.ifEmpty { listOf("on") }
            }
            service("turn_off") { listOf("off") }
            service("toggle", readsEntity = true) { listOf(if (it.isOn) "off" else "on") }
        },
        domain("fan") {
            service(
                "turn_on",
                Field("percentage", "number", min = 0.0, max = 100.0, step = 1.0),
                readsEntity = true,
            ) { call ->
                val levels = call.levels()
                val pct = call.num("percentage")
                val level = if (pct != null) levelForPercentage(levels, pct) else levels.getOrNull(1)
                    ?: throw IllegalArgumentException("entity has no speed levels")
                listOf(formatNumber(level))
            }
            service("turn_off", readsEntity = true) { listOf(formatNumber(it.levels().firstOrNull() ?: 0.0)) }
            service("toggle", readsEntity = true) { call ->
                val levels = call.levels()
                val level = if (call.isOn) levels.firstOrNull() ?: 0.0 else levels.getOrNull(1)
                    ?: throw IllegalArgumentException("entity has no speed levels")
                listOf(formatNumber(level))
            }
            service(
                "set_percentage",
                Field("percentage", "number", required = true, min = 0.0, max = 100.0, step = 1.0),
                readsEntity = true,
            ) { listOf(formatNumber(levelForPercentage(it.levels(), it.requireNum("percentage")))) }
            service("increase_speed", readsEntity = true) { stepLevel(it, 1) }
            service("decrease_speed", readsEntity = true) { stepLevel(it, -1) }
        },
        domain("cover") {
            service("open_cover") { listOf("open") }
            service("close_cover") { listOf("close") }
            service(
                "set_cover_position",
                Field("position", "number", required = true, min = 0.0, max = 100.0, step = 1.0),
                requires = "current_position",
            ) { listOf("position:${formatNumber(it.requireNum("position").coerceIn(0.0, 100.0))}") }
            service("toggle", readsEntity = true) { listOf(if (it.isOn) "close" else "open") }
        },
        domain("lock") {
            service("lock") { listOf("lock") }
            service("unlock") { listOf("unlock") }
        },
        domain("climate") {
            service("turn_on") { listOf("on") }
            service("turn_off") { listOf("off") }
            service("toggle", readsEntity = true) { listOf(if (it.isOn) "off" else "on") }
            service(
                "set_hvac_mode",
                Field("hvac_mode", "select", required = true, optionsFrom = "hvac_modes", optionLabelPrefix = "climate.mode."),
            ) { listOf("hvac_mode:${it.requireStr("hvac_mode")}") }
            service(
                "set_temperature",
                Field(
                    "temperature", "number", required = true,
                    minFrom = "min_temp", maxFrom = "max_temp", stepFrom = "target_temp_step",
                ),
                Field("hvac_mode", "select", optionsFrom = "hvac_modes", optionLabelPrefix = "climate.mode."),
            ) { call ->
                listOfNotNull(
                    call.str("hvac_mode")?.let { "hvac_mode:$it" },
                    "temperature:${formatNumber(call.requireNum("temperature"))}",
                )
            }
            service(
                "set_fan_mode",
                Field("fan_mode", "select", required = true, optionsFrom = "fan_modes"),
                requires = "fan_modes",
            ) { listOf("fan_mode:${it.requireStr("fan_mode")}") }
        },
        domain("media_player") {
            service("media_play") { listOf("play") }
            service("media_pause") { listOf("pause") }
            service("media_play_pause") { listOf("play_pause") }
            service("media_stop") { listOf("stop") }
            service("media_next_track") { listOf("next") }
            service("media_previous_track") { listOf("previous") }
            service("volume_up") { listOf("volume_up") }
            service("volume_down") { listOf("volume_down") }
            service(
                "volume_set",
                Field("volume_level", "number", required = true, min = 0.0, max = 1.0, step = 0.05),
            ) { listOf("volume_level:${it.requireNum("volume_level").coerceIn(0.0, 1.0)}") }
        },
        domain("number") {
            service(
                "set_value",
                Field("value", "number", required = true, minFrom = "min", maxFrom = "max", stepFrom = "step"),
            ) { listOf(formatNumber(it.requireNum("value"))) }
        },
        domain("select") {
            service("select_option", Field("option", "select", required = true, optionsFrom = "options")) {
                listOf(it.requireStr("option"))
            }
            service("select_next", cycleField, readsEntity = true) { call ->
                cycleOption(call) { opts, i, cycle -> if (i + 1 > opts.lastIndex) (if (cycle) 0 else opts.lastIndex) else i + 1 }
            }
            service("select_previous", cycleField, readsEntity = true) { call ->
                cycleOption(call) { opts, i, cycle ->
                    when {
                        i < 0 -> opts.lastIndex
                        i == 0 -> if (cycle) opts.lastIndex else 0
                        else -> i - 1
                    }
                }
            }
            service("select_first", readsEntity = true) { call -> cycleOption(call) { _, _, _ -> 0 } }
            service("select_last", readsEntity = true) { call -> cycleOption(call) { opts, _, _ -> opts.lastIndex } }
        },
        domain("button") {
            service("press", readsEntity = true) { listOf(it.options().firstOrNull() ?: "1") }
        },
        domain("text") {
            service("set_value", Field("value", "text", required = true)) { listOf(it.data["value"]?.toString().orEmpty()) }
        },

        // --- Car domains ---
        domain("drivetrain") {
            service("set_drive_mode", Field("mode", "select", required = true, optionsFrom = "options"), requires = "mode") {
                listOf("mode:${it.requireStr("mode")}")
            }
            numberAttr("set_regeneration", "regen", Field("level", "number", required = true, step = 1.0))
            numberAttr("set_battery_mode", "battery_mode", Field("mode", "number", required = true, step = 1.0))
            boolAttr("set_battery_hold", "battery_hold")
            boolAttr("set_battery_save", "battery_save")
        },
        domain("chassis") {
            boolAttr("set_auto_hold", "auto_hold")
            boolAttr("set_hill_descent", "hdc")
            boolAttr("set_esc_sport", "esc")
            boolAttr("set_epb", "epb")
        },
        domain("steering") {
            service(
                "set_assist_level",
                Field("level", "select", required = true, optionsFrom = "options"),
                requires = "assist_level",
            ) { listOf("assist_level:${it.requireStr("level")}") }
            boolAttr("set_sync_drive_mode", "sync_drive_mode")
            boolAttr("set_intelligent_assist", "intelligent")
        },
        domain("charger") {
            service("start_charging", requires = "switch") { listOf("switch:1") }
            service("stop_charging", requires = "switch") { listOf("switch:0") }
            service("charge_now", requires = "pre_now") { listOf("pre_now:1") }
            numberAttr("set_current_limit", "limit", Field("current", "number", required = true, min = 0.0, step = 1.0))
            numberAttr("set_charge_limit", "soc_max", Field("soc", "number", required = true, min = 0.0, max = 100.0, step = 1.0))
            numberAttr("set_minimum_charge", "soc_min", Field("soc", "number", required = true, min = 0.0, max = 100.0, step = 1.0))
            numberAttr("set_discharge_limit", "discharge_soc", Field("soc", "number", required = true, min = 0.0, max = 100.0, step = 1.0))
            boolAttr("set_v2l", "v2l")
            boolAttr("set_v2v", "v2v")
            boolAttr("set_port_light", "external_light")
        },
        domain("hud") {
            service("turn_on", requires = "active") { listOf("active:1") }
            service("turn_off", requires = "active") { listOf("active:0") }
            service("toggle", requires = "active", readsEntity = true) { listOf("active:${onOff(!it.isOn)}") }
            boolAttr("set_snow_mode", "snow")
            boolAttr("set_ar", "ar")
            numberAttr("set_display_mode", "display_mode", Field("mode", "number", required = true, step = 1.0))
            numberAttr("set_angle", "angle", Field("angle", "number", required = true))
        },
    )

    private val byId: Map<String, Service> = DOMAINS.values.flatten().associateBy { it.id }

    fun find(id: String): Service? = byId[id]

    /**
     * Control-write values for [serviceId] on [entityId]. The service's domain must match the
     * entity id's domain prefix when the id has one (`light.ambient`); VHAL-keyed ids carry none.
     */
    fun resolve(
        serviceId: String,
        entityId: String,
        data: Map<String, Any?>,
        entity: Map<String, Any?>?,
    ): Result<List<String>> = runCatching {
        val service = find(serviceId) ?: throw IllegalArgumentException("unknown service: $serviceId")
        val entityDomain = entity?.get("domain")?.toString()
            ?: entityId.substringBefore('.', "").takeIf { it in DOMAINS }
        if (entityDomain != null && entityDomain != service.domain) {
            throw IllegalArgumentException("$serviceId does not apply to $entityId")
        }
        if (service.readsEntity && entity == null) throw IllegalArgumentException("entity unavailable: $entityId")
        service.resolve(Call(data, entity.orEmpty())).ifEmpty { throw IllegalArgumentException("nothing to write") }
    }

    /** `GET /api/services`: `[{domain, services: [{service, fields?, requires?}]}]`. */
    fun catalog(): List<Map<String, Any?>> = DOMAINS.map { (domain, services) ->
        mapOf("domain" to domain, "services" to services.map { it.toMap() })
    }
}
