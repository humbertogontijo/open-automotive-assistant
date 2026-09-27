package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import cc.opencar.assistant.support.JsonMaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide singleton — DataStore forbids two instances on the same file.
 * Holds **flows** (Shortcuts tab) and **routines**; scenes live in [SceneStore].
 */
class ShortcutStore private constructor(context: Context) {
    private val store = PreferenceDataStoreFactory.create {
        context.applicationContext.preferencesDataStoreFile("oaa_shortcuts")
    }

    val shortcuts: Flow<List<Shortcut>> = store.data.map { prefs ->
        parseShortcutList(prefs[KEY_LIST])
    }

    val routines: Flow<List<Routine>> = store.data.map { prefs ->
        parseRoutineList(prefs[KEY_ROUTINES])
    }

    /** Fixed pin slots 0..7 → shortcut (flow) id (or absent). */
    val slots: Flow<Map<Int, String>> = store.data.map { prefs ->
        parseSlots(prefs[KEY_SLOTS])
    }

    val overlayEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_OVERLAY] != false
    }

    suspend fun list(): List<Shortcut> = shortcuts.first()

    suspend fun listRoutines(): List<Routine> = routines.first()

    suspend fun get(id: String): Shortcut? = list().firstOrNull { it.id == id }

    suspend fun getRoutine(id: String): Routine? = listRoutines().firstOrNull { it.id == id }

    suspend fun slotMap(): Map<Int, String> = slots.first()

    suspend fun isOverlayEnabled(): Boolean = overlayEnabled.first()

    suspend fun setOverlayEnabled(enabled: Boolean) {
        store.edit { it[KEY_OVERLAY] = enabled }
    }

    suspend fun setSlot(slot: Int, shortcutId: String?) {
        val s = slot.coerceIn(0, 7)
        store.edit { prefs ->
            val map = parseSlots(prefs[KEY_SLOTS]).toMutableMap()
            if (shortcutId.isNullOrBlank()) {
                map.remove(s)
            } else {
                val keys = map.filterValues { it == shortcutId }.keys.toList()
                keys.forEach { map.remove(it) }
                map[s] = shortcutId
            }
            prefs[KEY_SLOTS] = serializeSlots(map)
        }
    }

    suspend fun upsert(shortcut: Shortcut): Shortcut {
        val normalized = normalizeFlow(shortcut)
        store.edit { prefs ->
            val current = parseShortcutList(prefs[KEY_LIST]).toMutableList()
            val idx = current.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) current[idx] = normalized else current.add(normalized)
            prefs[KEY_LIST] = serializeShortcuts(current)
        }
        return get(normalized.id) ?: normalized
    }

    suspend fun upsertRoutine(routine: Routine): Routine {
        val normalized = routine.copy(
            actions = routine.actions.take(ShortcutAction.MAX_ACTIONS),
        )
        store.edit { prefs ->
            val current = parseRoutineList(prefs[KEY_ROUTINES]).toMutableList()
            val idx = current.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) current[idx] = normalized else current.add(normalized)
            prefs[KEY_ROUTINES] = serializeRoutines(current)
        }
        return getRoutine(normalized.id) ?: normalized
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        store.edit { prefs ->
            val current = parseShortcutList(prefs[KEY_LIST]).toMutableList()
            removed = current.removeAll { it.id == id }
            prefs[KEY_LIST] = serializeShortcuts(current)
            val map = parseSlots(prefs[KEY_SLOTS]).toMutableMap()
            val cleared = map.filterValues { it != id }
            prefs[KEY_SLOTS] = serializeSlots(cleared)
        }
        return removed
    }

    suspend fun deleteRoutine(id: String): Boolean {
        var removed = false
        store.edit { prefs ->
            val current = parseRoutineList(prefs[KEY_ROUTINES]).toMutableList()
            removed = current.removeAll { it.id == id }
            prefs[KEY_ROUTINES] = serializeRoutines(current)
        }
        return removed
    }

    private fun normalizeFlow(s: Shortcut): Shortcut =
        s.copy(actions = s.actions.take(ShortcutAction.MAX_ACTIONS))

    /** Seed builtin flows (Sentinel) once. */
    suspend fun ensureSeeded() {
        store.edit { prefs -> seedSentinelFlowLocked(prefs) }
    }

    private fun seedSentinelFlowLocked(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val flows = parseShortcutList(prefs[KEY_LIST]).toMutableList()
        if (flows.any { it.id == Scene.SENTINEL_ID }) return
        flows.add(
            0,
            Shortcut(
                id = Scene.SENTINEL_ID,
                name = "Sentinel",
                icon = "battery",
                enabled = true,
                actions = listOf(ShortcutAction.SetScene(Scene.SENTINEL_ID, active = null)),
                triggers = listOf(ShortcutTrigger.UiCard(group = "assistant")),
            ),
        )
        prefs[KEY_LIST] = serializeShortcuts(flows)
    }

    companion object {
        private val KEY_LIST = stringPreferencesKey("shortcuts_json")
        private val KEY_ROUTINES = stringPreferencesKey("routines_json")
        private val KEY_SLOTS = stringPreferencesKey("pin_slots_json")
        private val KEY_OVERLAY = booleanPreferencesKey("overlay_enabled")

        @Volatile
        private var instance: ShortcutStore? = null

        fun get(context: Context): ShortcutStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ShortcutStore(context.applicationContext).also { instance = it }
            }
        }

        fun parseSlots(raw: String?): Map<Int, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return try {
                val o = JSONObject(raw)
                buildMap {
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val slot = k.toIntOrNull() ?: continue
                        val id = o.optString(k, null) ?: continue
                        if (id.isNotBlank() && slot in 0..7) put(slot, id)
                    }
                }
            } catch (_: Throwable) {
                emptyMap()
            }
        }

        fun serializeSlots(map: Map<Int, String>): String {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k.toString(), v) }
            return o.toString()
        }

        fun parseShortcutList(raw: String?): List<Shortcut> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        fromShortcutJson(obj)?.let { add(it) }
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun serializeShortcuts(list: List<Shortcut>): String {
            val arr = JSONArray()
            for (s in list) arr.put(toShortcutJson(s))
            return arr.toString()
        }

        fun parseRoutineList(raw: String?): List<Routine> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        Routine.fromMap(JsonMaps.jsonObjectToMap(obj))?.let { add(it) }
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun serializeRoutines(list: List<Routine>): String {
            val arr = JSONArray()
            for (r in list) arr.put(JsonMaps.mapToJson(r.toMap()))
            return arr.toString()
        }

        private fun toShortcutJson(s: Shortcut): JSONObject {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("icon", s.icon)
            o.put("enabled", s.enabled)
            val actions = JSONArray()
            for (a in s.actions) actions.put(JsonMaps.mapToJson(a.toMap()))
            o.put("actions", actions)
            val triggers = JSONArray()
            for (t in s.triggers) triggers.put(JsonMaps.mapToJson(t.toMap()))
            o.put("triggers", triggers)
            val conditions = JSONArray()
            for (c in s.conditions) conditions.put(JsonMaps.mapToJson(c.toMap()))
            o.put("conditions", conditions)
            return o
        }

        private fun fromShortcutJson(o: JSONObject): Shortcut? {
            val map = mutableMapOf<String, Any?>()
            map["id"] = o.optString("id", null) ?: return null
            map["name"] = o.optString("name", null) ?: return null
            map["icon"] = o.optString("icon", "drive")
            map["enabled"] = o.optBoolean("enabled", true)
            map["actions"] = JsonMaps.jsonArrayToMaps(o.optJSONArray("actions"))
            map["triggers"] = JsonMaps.jsonArrayToMaps(o.optJSONArray("triggers"))
            map["conditions"] = JsonMaps.jsonArrayToMaps(o.optJSONArray("conditions"))
            return Shortcut.fromMap(map)
        }
    }
}
