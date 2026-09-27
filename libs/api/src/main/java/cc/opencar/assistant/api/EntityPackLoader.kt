package cc.opencar.assistant.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Loads declarative [EntityDef] packs (JSON) for merge into [EntityRegistry].
 *
 * Pilot path: `assets/entities/standard-pilot.json`. Integrations may later ship overlays.
 */
object EntityPackLoader {
    fun load(stream: InputStream): List<EntityDef> =
        load(stream.bufferedReader().use { it.readText() })

    fun load(json: String): List<EntityDef> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("entities") ?: JSONArray()
        val out = ArrayList<EntityDef>(arr.length())
        for (i in 0 until arr.length()) {
            out += parseEntity(arr.getJSONObject(i))
        }
        return out
    }

    fun loadClasspath(path: String, classLoader: ClassLoader = EntityPackLoader::class.java.classLoader!!): List<EntityDef> {
        val stream = classLoader.getResourceAsStream(path)
            ?: classLoader.getResourceAsStream("assets/$path")
            ?: error("entity pack not found: $path")
        return load(stream)
    }

    /**
     * Merge [declarative] over [builtin]: same [EntityDef.id] prefers declarative;
     * remaining builtin entries follow.
     */
    fun merge(declarative: List<EntityDef>, builtin: List<EntityDef>): List<EntityDef> {
        val byId = LinkedHashMap<String, EntityDef>()
        for (d in declarative) byId[d.id] = d
        for (b in builtin) byId.putIfAbsent(b.id, b)
        return byId.values.toList()
    }

    private fun parseEntity(o: JSONObject): EntityDef {
        val id = o.getString("id")
        val domain = EntityType.fromId(o.getString("domain"))
        val group = o.getString("group")
        val bindingKey = if (o.has("bindingKey") && !o.isNull("bindingKey")) {
            o.getString("bindingKey")
        } else {
            null
        }
        val attributes = o.optJSONObject("attributes")?.toStringMap().orEmpty()
        val isComposite = attributes.isNotEmpty() && bindingKey == null
        return EntityDef(
            id = id,
            domain = domain,
            group = group,
            section = o.optStringOrNull("section"),
            bindingKey = when {
                isComposite -> null
                bindingKey != null -> bindingKey
                else -> id.substringAfter('.', id)
            },
            attributes = attributes,
            input = o.optString("input", "bool"),
            optionKeys = o.optJSONArray("optionKeys")?.toOptionKeys(),
            writable = o.optBoolean("writable", true),
            deviceClass = DeviceClass.fromId(o.optStringOrNull("deviceClass")),
            unitOfMeasurement = UnitOfMeasurement.fromId(o.optStringOrNull("unitOfMeasurement")),
            acronym = o.optStringOrNull("acronym"),
            lastKnown = o.optBoolean("lastKnown", false),
            valueMapId = o.optStringOrNull("valueMapId"),
            labelKey = o.optStringOrNull("labelKey"),
            hintKey = o.optStringOrNull("hintKey"),
            icon = o.optStringOrNull("icon"),
            min = o.optDoubleOrNull("min")?.toFloat(),
            max = o.optDoubleOrNull("max")?.toFloat(),
            step = o.optDoubleOrNull("step")?.toFloat(),
            history = o.optBoolean("history", false),
            areaId = o.optIntOrNull("areaId"),
            attributeAreas = o.optJSONObject("attributeAreas")?.toIntMap().orEmpty(),
        )
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = getString(k)
        }
        return out
    }

    private fun JSONObject.toIntMap(): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = getInt(k)
        }
        return out
    }

    private fun JSONArray.toOptionKeys(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(length())
        for (i in 0 until length()) {
            val row = getJSONObject(i)
            out += row.getString("labelKey") to row.getInt("value")
        }
        return out
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
}
