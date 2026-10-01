package cc.opencar.assistant.feature.shortcuts

data class Shortcut(
    val id: String,
    val name: String,
    val icon: String = "drive",
    val enabled: Boolean = true,
    val actions: List<ShortcutAction> = emptyList(),
    val triggers: List<ShortcutTrigger> = emptyList(),
    /** AND conditions evaluated after any trigger matches. Empty = always pass. */
    val conditions: List<ShortcutCondition> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "icon" to icon,
        "enabled" to enabled,
        "actions" to actions.map { it.toMap() },
        "triggers" to triggers.map { it.toMap() },
        "conditions" to conditions.map { it.toMap() },
    )

    companion object {
        fun fromMap(m: Map<*, *>): Shortcut? {
            val id = m["id"] as? String ?: return null
            val name = m["name"] as? String ?: return null
            val icon = m["icon"] as? String ?: "drive"
            val enabled = m["enabled"] as? Boolean ?: true
            @Suppress("UNCHECKED_CAST")
            val actionsRaw = m["actions"] as? List<Map<*, *>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val triggersRaw = m["triggers"] as? List<Map<*, *>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val conditionsRaw = m["conditions"] as? List<Map<*, *>> ?: emptyList()
            return Shortcut(
                id = id,
                name = name,
                icon = icon,
                enabled = enabled,
                actions = actionsRaw.mapNotNull { ShortcutAction.fromMap(it) },
                triggers = triggersRaw.mapNotNull { ShortcutTrigger.fromMap(it) },
                conditions = conditionsRaw.mapNotNull { ShortcutCondition.fromMap(it) },
            )
        }
    }
}

sealed class ShortcutCondition {
    abstract fun toMap(): Map<String, Any?>

    data class EntityEquals(val entityId: String, val value: String) : ShortcutCondition() {
        override fun toMap() = mapOf(
            "type" to "entity_equals",
            "entityId" to entityId,
            "value" to value,
        )
    }

    data class GearEquals(val gear: Int) : ShortcutCondition() {
        override fun toMap() = mapOf("type" to "gear_equals", "gear" to gear)
    }

    data class WifiSsid(val ssid: String, val contains: Boolean = false) : ShortcutCondition() {
        override fun toMap() = mapOf(
            "type" to "wifi_ssid",
            "ssid" to ssid,
            "contains" to contains,
        )
    }

    companion object {
        fun fromMap(m: Map<*, *>): ShortcutCondition? {
            return when (m["type"] as? String) {
                "entity_equals" -> {
                    val id = m["entityId"] as? String ?: return null
                    val value = m["value"]?.toString() ?: return null
                    EntityEquals(id, value)
                }
                "gear_equals" -> {
                    val gear = when (val v = m["gear"]) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: return null
                        else -> return null
                    }
                    GearEquals(gear)
                }
                "wifi_ssid" -> {
                    val ssid = m["ssid"] as? String ?: return null
                    val contains = when (val v = m["contains"]) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> v.equals("true", true) || v == "1"
                        else -> false
                    }
                    WifiSsid(ssid, contains)
                }
                else -> null
            }
        }
    }
}

sealed class ShortcutAction {
    abstract fun toMap(): Map<String, Any?>

    /** Home Assistant–style `domain.service` on one entity (see `OaaServices`). */
    data class CallService(
        val service: String,
        val entityId: String,
        val data: Map<String, Any?> = emptyMap(),
    ) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "service",
            "service" to service,
            "entityId" to entityId,
            "data" to data,
        )
    }

    data class LaunchApp(val packageName: String) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "launch_app",
            "packageName" to packageName,
        )
    }

    data class DelayMs(val ms: Long) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "delay_ms",
            "ms" to ms.coerceIn(0L, MAX_DELAY_MS),
        )
    }

    /** Plugin-backed action (`pluginId` + contribution `action` + `params`). */
    data class Plugin(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any?> = emptyMap(),
    ) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "plugin",
            "pluginId" to pluginId,
            "action" to action,
            "params" to params,
        )
    }

    /** Activate / deactivate / toggle a [Scene]. `active == null` toggles. */
    data class SetScene(val sceneId: String, val active: Boolean? = null) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "set_scene",
            "sceneId" to sceneId,
            "active" to active,
        )
    }

    /** Run a reusable [Routine] by id. */
    data class RunRoutine(val routineId: String) : ShortcutAction() {
        override fun toMap() = mapOf(
            "type" to "run_routine",
            "routineId" to routineId,
        )
    }

    companion object {
        const val MAX_DELAY_MS = 60_000L
        const val MAX_ACTIONS = 10

        fun fromMap(m: Map<*, *>): ShortcutAction? {
            return when (m["type"] as? String) {
                "service" -> {
                    val service = (m["service"] as? String)?.takeIf { it.contains('.') } ?: return null
                    val id = (m["entityId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
                    CallService(service, id, readParams(m["data"]))
                }
                "launch_app" -> {
                    val pkg = m["packageName"] as? String ?: return null
                    LaunchApp(pkg)
                }
                "delay_ms" -> {
                    val ms = when (val v = m["ms"]) {
                        is Number -> v.toLong()
                        is String -> v.toLongOrNull() ?: return null
                        else -> return null
                    }
                    DelayMs(ms.coerceIn(0L, MAX_DELAY_MS))
                }
                "plugin" -> {
                    val pluginId = m["pluginId"] as? String ?: return null
                    val action = m["action"] as? String ?: return null
                    Plugin(pluginId, action, readParams(m["params"]))
                }
                "set_scene" -> {
                    val sceneId = m["sceneId"] as? String ?: return null
                    val active = when (val v = m["active"]) {
                        null -> null
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> when (v.trim().lowercase()) {
                            "", "null", "toggle" -> null
                            "0", "false", "off" -> false
                            else -> true
                        }
                        else -> null
                    }
                    SetScene(sceneId, active)
                }
                "run_routine" -> {
                    val routineId = m["routineId"] as? String ?: return null
                    RunRoutine(routineId)
                }
                else -> null
            }
        }
    }
}

sealed class ShortcutTrigger {
    abstract fun toMap(): Map<String, Any?>

    data object Boot : ShortcutTrigger() {
        override fun toMap() = mapOf("type" to "boot")
    }

    /** HU display wake (`on=true`) or sleep (`on=false`). */
    data class Screen(val on: Boolean) : ShortcutTrigger() {
        override fun toMap() = mapOf("type" to "screen", "on" to on)
    }

    data class Gear(val gear: Int? = null) : ShortcutTrigger() {
        override fun toMap() = mapOf("type" to "gear", "gear" to gear)
    }

    data class WheelKey(val key: String, val longPress: Boolean = false) : ShortcutTrigger() {
        override fun toMap() = mapOf(
            "type" to "wheel_key",
            "key" to key,
            "longPress" to longPress,
        )
    }

    /** Fires when Wi‑Fi connects to [ssid] (null/blank = any SSID connect). */
    data class WifiSsid(val ssid: String? = null) : ShortcutTrigger() {
        override fun toMap() = mapOf("type" to "wifi_ssid", "ssid" to ssid)
    }

    /** Fires when a bound entity value changes; optional [value] match. */
    data class EntityState(val entityId: String, val value: String? = null) : ShortcutTrigger() {
        override fun toMap() = mapOf(
            "type" to "entity_state",
            "entityId" to entityId,
            "value" to value,
        )
    }

    /**
     * Not an event trigger — presence publishes a virtual control card for this flow.
     * [group] is the nav section id (default assistant).
     */
    data class UiCard(val group: String = "assistant") : ShortcutTrigger() {
        override fun toMap() = mapOf(
            "type" to "ui_card",
            "group" to group.ifBlank { "assistant" },
        )
    }

    /** Plugin-backed trigger (`pluginId` + contribution `trigger` + `params`). */
    data class Plugin(
        val pluginId: String,
        val trigger: String,
        val params: Map<String, Any?> = emptyMap(),
    ) : ShortcutTrigger() {
        override fun toMap() = mapOf(
            "type" to "plugin",
            "pluginId" to pluginId,
            "trigger" to trigger,
            "params" to params,
        )
    }

    companion object {
        fun fromMap(m: Map<*, *>): ShortcutTrigger? {
            return when (m["type"] as? String) {
                "boot" -> Boot
                "screen" -> Screen(on = parseScreenOn(m["on"]))
                "gear" -> {
                    val gear = when (val v = m["gear"]) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull()
                        null -> null
                        else -> null
                    }
                    Gear(gear)
                }
                "wheel_key" -> {
                    val key = m["key"] as? String ?: return null
                    val longPress = when (val v = m["longPress"]) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> v.equals("true", true) || v == "1"
                        else -> false
                    }
                    WheelKey(key, longPress)
                }
                "wifi_ssid" -> {
                    val ssid = (m["ssid"] as? String)?.takeIf { it.isNotBlank() }
                    WifiSsid(ssid)
                }
                "entity_state" -> {
                    val id = m["entityId"] as? String ?: return null
                    val value = m["value"]?.toString()?.takeIf { it.isNotBlank() }
                    EntityState(id, value)
                }
                "ui_card" -> {
                    val group = (m["group"] as? String)?.takeIf { it.isNotBlank() } ?: "assistant"
                    UiCard(group)
                }
                "plugin" -> {
                    val pluginId = m["pluginId"] as? String ?: return null
                    val trigger = m["trigger"] as? String ?: return null
                    Plugin(pluginId, trigger, readParams(m["params"]))
                }
                else -> null
            }
        }

        private fun parseScreenOn(raw: Any?): Boolean {
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> when (raw.trim().lowercase()) {
                    "off", "false", "0" -> false
                    else -> true
                }
                else -> true
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun readParams(raw: Any?): Map<String, Any?> {
    val m = raw as? Map<*, *> ?: return emptyMap()
    return m.entries.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        key to v
    }.toMap()
}
