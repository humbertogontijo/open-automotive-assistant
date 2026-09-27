package cc.opencar.assistant.feature.shortcuts

/** Optional virtual control card published into the entities grid. */
data class UiCardSpec(
    val group: String = DEFAULT_GROUP,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "type" to "ui_card",
        "group" to group,
    )

    companion object {
        const val DEFAULT_GROUP = "assistant"

        fun fromMap(m: Map<*, *>?): UiCardSpec? {
            if (m == null) return null
            val enabled = when (val v = m["enabled"]) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v.equals("true", true) || v == "1"
                null -> true
                else -> true
            }
            if (!enabled) return null
            val type = m["type"] as? String
            if (type != null && type != "ui_card") return null
            val group = (m["group"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP
            return UiCardSpec(group)
        }

        fun fromAny(raw: Any?): UiCardSpec? = when (raw) {
            is Map<*, *> -> fromMap(raw)
            is Boolean -> if (raw) UiCardSpec() else null
            else -> null
        }
    }
}

sealed class SceneOff {
    data object Restore : SceneOff()
    data class Set(val value: String) : SceneOff()

    fun toMap(): Map<String, Any?> = when (this) {
        is Restore -> mapOf("policy" to "restore")
        is Set -> mapOf("policy" to "set", "value" to value)
    }

    companion object {
        fun fromMap(m: Map<*, *>?): SceneOff {
            if (m == null) return Restore
            return when (m["policy"] as? String) {
                "set" -> Set((m["value"]?.toString() ?: "0"))
                else -> Restore
            }
        }
    }
}

data class SceneTarget(
    val entityId: String,
    val onValue: String,
    val off: SceneOff = SceneOff.Restore,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "entityId" to entityId,
        "onValue" to onValue,
        "off" to off.toMap(),
    )

    companion object {
        fun fromMap(m: Map<*, *>): SceneTarget? {
            val id = m["entityId"] as? String ?: return null
            val on = m["onValue"]?.toString() ?: return null
            @Suppress("UNCHECKED_CAST")
            val offMap = m["off"] as? Map<*, *>
            return SceneTarget(id, on, SceneOff.fromMap(offMap))
        }
    }
}

data class Scene(
    val id: String,
    val name: String,
    val icon: String = "climate",
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val targets: List<SceneTarget> = emptyList(),
    val uiCard: UiCardSpec? = null,
) {
    fun toMap(active: Boolean = false): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "icon" to icon,
        "enabled" to enabled,
        "builtin" to builtin,
        "targets" to targets.map { it.toMap() },
        "uiCard" to uiCard?.toMap(),
        "active" to active,
    )

    companion object {
        const val MAX_TARGETS = 16
        const val SENTINEL_ID = "sentinel"

        fun fromMap(m: Map<*, *>): Scene? {
            val id = m["id"] as? String ?: return null
            val name = m["name"] as? String ?: return null
            val icon = m["icon"] as? String ?: "climate"
            val enabled = m["enabled"] as? Boolean ?: true
            val builtin = m["builtin"] as? Boolean ?: false
            @Suppress("UNCHECKED_CAST")
            val targetsRaw = m["targets"] as? List<Map<*, *>> ?: emptyList()
            val targets = targetsRaw.mapNotNull { SceneTarget.fromMap(it) }.take(MAX_TARGETS)
            val uiCard = UiCardSpec.fromAny(m["uiCard"])
            return Scene(id, name, icon, enabled, builtin, targets, uiCard)
        }

        fun sentinel(): Scene = Scene(
            id = SENTINEL_ID,
            name = "Sentinel",
            icon = "battery",
            builtin = true,
            targets = listOf(
                SceneTarget("SCENE_FUNC_PARKING_COMFORT_SWITCH", "1", SceneOff.Set("0")),
                SceneTarget("climate.cabin", "off", SceneOff.Restore),
                SceneTarget("SETTING_FUNC_LAMP_EXTERIOR_LIGHT_CONTROL", "0", SceneOff.Restore),
                SceneTarget("FOG_LIGHTS_SWITCH", "0", SceneOff.Restore),
            ),
        )
    }
}

/** Fire-once action sequence (reusable building block). */
data class Routine(
    val id: String,
    val name: String,
    val icon: String = "drive",
    val enabled: Boolean = true,
    val actions: List<ShortcutAction> = emptyList(),
    /** AND conditions evaluated before actions on every run. Empty = always pass. */
    val conditions: List<ShortcutCondition> = emptyList(),
    val uiCard: UiCardSpec? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "icon" to icon,
        "enabled" to enabled,
        "actions" to actions.map { it.toMap() },
        "conditions" to conditions.map { it.toMap() },
        "uiCard" to uiCard?.toMap(),
    )

    companion object {
        fun fromMap(m: Map<*, *>): Routine? {
            val id = m["id"] as? String ?: return null
            val name = m["name"] as? String ?: return null
            val icon = m["icon"] as? String ?: "drive"
            val enabled = m["enabled"] as? Boolean ?: true
            @Suppress("UNCHECKED_CAST")
            val actionsRaw = m["actions"] as? List<Map<*, *>> ?: emptyList()
            val actions = actionsRaw.mapNotNull { ShortcutAction.fromMap(it) }
                .take(ShortcutAction.MAX_ACTIONS)
            @Suppress("UNCHECKED_CAST")
            val conditionsRaw = m["conditions"] as? List<Map<*, *>> ?: emptyList()
            val conditions = conditionsRaw.mapNotNull { ShortcutCondition.fromMap(it) }
            val uiCard = UiCardSpec.fromAny(m["uiCard"])
            return Routine(id, name, icon, enabled, actions, conditions, uiCard)
        }
    }
}
