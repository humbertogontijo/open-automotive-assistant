package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists scenes, active flags, and per-scene snapshots for restore-on-deactivate.
 */
class SceneStore private constructor(context: Context) {
    private val store = PreferenceDataStoreFactory.create {
        context.applicationContext.preferencesDataStoreFile("oaa_scenes")
    }

    val scenes: Flow<List<Scene>> = store.data.map { prefs ->
        val list = parseList(prefs[KEY_LIST])
        if (list.any { it.id == Scene.SENTINEL_ID }) list
        else listOf(Scene.sentinel()) + list
    }

    suspend fun list(): List<Scene> {
        ensureSeeded()
        return scenes.first()
    }

    suspend fun get(id: String): Scene? = list().firstOrNull { it.id == id }

    suspend fun isActive(id: String): Boolean = activeIds().contains(id)

    suspend fun activeIds(): Set<String> =
        JsonMaps.parseStringSet(store.data.first()[KEY_ACTIVE])

    suspend fun snapshotFor(id: String): Map<String, String> =
        JsonMaps.parseNestedStringMap(store.data.first()[KEY_SNAPSHOTS])[id].orEmpty()

    suspend fun ensureSeeded() {
        store.edit { prefs ->
            val current = parseList(prefs[KEY_LIST]).toMutableList()
            val factory = Scene.sentinel()
            val idx = current.indexOfFirst { it.id == Scene.SENTINEL_ID }
            if (idx < 0) {
                current.add(0, factory)
                prefs[KEY_LIST] = serialize(current)
                prefs[KEY_SEEDED] = true
            }
        }
    }

    suspend fun upsert(scene: Scene): Scene {
        val normalized = scene.copy(targets = scene.targets.take(Scene.MAX_TARGETS))
        store.edit { prefs ->
            val current = parseList(prefs[KEY_LIST]).toMutableList()
            if (current.none { it.id == Scene.SENTINEL_ID }) {
                current.add(0, Scene.sentinel())
            }
            val idx = current.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) {
                val builtin = current[idx].builtin || normalized.builtin
                current[idx] = normalized.copy(builtin = builtin)
            } else {
                current.add(normalized)
            }
            prefs[KEY_LIST] = serialize(current)
            prefs[KEY_SEEDED] = true
        }
        return get(normalized.id) ?: normalized
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        store.edit { prefs ->
            val current = parseList(prefs[KEY_LIST]).toMutableList()
            if (current.none { it.id == Scene.SENTINEL_ID }) {
                current.add(0, Scene.sentinel())
            }
            val existing = current.firstOrNull { it.id == id }
            if (existing == null) return@edit
            if (existing.builtin) {
                val idx = current.indexOfFirst { it.id == id }
                current[idx] = Scene.sentinel()
                prefs[KEY_LIST] = serialize(current)
                val active = JsonMaps.parseStringSet(prefs[KEY_ACTIVE]).toMutableSet()
                active.remove(id)
                prefs[KEY_ACTIVE] = JsonMaps.serializeStringSet(active)
                val snaps = JsonMaps.parseNestedStringMap(prefs[KEY_SNAPSHOTS]).toMutableMap()
                snaps.remove(id)
                prefs[KEY_SNAPSHOTS] = JsonMaps.serializeNestedStringMap(snaps)
                removed = true
                return@edit
            }
            removed = current.removeAll { it.id == id }
            prefs[KEY_LIST] = serialize(current)
            val active = JsonMaps.parseStringSet(prefs[KEY_ACTIVE]).toMutableSet()
            active.remove(id)
            prefs[KEY_ACTIVE] = JsonMaps.serializeStringSet(active)
            val snaps = JsonMaps.parseNestedStringMap(prefs[KEY_SNAPSHOTS]).toMutableMap()
            snaps.remove(id)
            prefs[KEY_SNAPSHOTS] = JsonMaps.serializeNestedStringMap(snaps)
        }
        return removed
    }

    suspend fun resetBuiltin(id: String): Scene? {
        if (id != Scene.SENTINEL_ID) return null
        val factory = Scene.sentinel()
        store.edit { prefs ->
            val current = parseList(prefs[KEY_LIST]).toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) current[idx] = factory else current.add(0, factory)
            prefs[KEY_LIST] = serialize(current)
            prefs[KEY_SEEDED] = true
        }
        return get(id)
    }

    suspend fun markActive(id: String, active: Boolean, snapshot: Map<String, String>?) {
        store.edit { prefs ->
            val activeSet = JsonMaps.parseStringSet(prefs[KEY_ACTIVE]).toMutableSet()
            val snaps = JsonMaps.parseNestedStringMap(prefs[KEY_SNAPSHOTS]).toMutableMap()
            if (active) {
                activeSet.add(id)
                if (snapshot != null) snaps[id] = snapshot
            } else {
                activeSet.remove(id)
                snaps.remove(id)
            }
            prefs[KEY_ACTIVE] = JsonMaps.serializeStringSet(activeSet)
            prefs[KEY_SNAPSHOTS] = JsonMaps.serializeNestedStringMap(snaps)
        }
    }

    companion object {
        private val KEY_LIST = stringPreferencesKey("scenes_json")
        private val KEY_ACTIVE = stringPreferencesKey("active_json")
        private val KEY_SNAPSHOTS = stringPreferencesKey("snapshots_json")
        private val KEY_SEEDED = booleanPreferencesKey("seeded")

        @Volatile
        private var instance: SceneStore? = null

        fun get(context: Context): SceneStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: SceneStore(context.applicationContext).also { instance = it }
            }
        }

        fun parseList(raw: String?): List<Scene> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        Scene.fromMap(JsonMaps.jsonObjectToMap(obj))?.let { add(it) }
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun serialize(list: List<Scene>): String {
            val arr = JSONArray()
            for (s in list) arr.put(JsonMaps.mapToJson(s.toMap(active = false).filterKeys { it != "active" }))
            return arr.toString()
        }
    }
}
