package cc.opencar.assistant.support

import org.json.JSONArray
import org.json.JSONObject

/** org.json ↔ Kotlin collections, plus the compact string-map encodings stored in DataStore. */
object JsonMaps {
    fun jsonArrayToMaps(arr: JSONArray?): List<Map<String, Any?>> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(jsonObjectToMap(obj))
            }
        }
    }

    fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = jsonToAny(obj.opt(k))
        }
        return m
    }

    fun jsonToAny(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> (0 until v.length()).map { jsonToAny(v.opt(it)) }
        else -> v
    }

    fun mapToJson(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, anyToJson(v)) }
        return o
    }

    fun anyToJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o ->
            v.forEach { (k, vv) ->
                if (k != null) o.put(k.toString(), anyToJson(vv))
            }
        }
        is List<*> -> JSONArray().also { a ->
            v.forEach { a.put(anyToJson(it)) }
        }
        is Boolean, is Number, is String -> v
        else -> v.toString()
    }

    fun parseStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.optString(k, null) ?: continue
                    put(k, v)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun serializeStringMap(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun parseNestedStringMap(raw: String?): Map<String, Map<String, String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = o.optJSONObject(k) ?: continue
                    put(k, buildMap {
                        val ck = child.keys()
                        while (ck.hasNext()) {
                            val id = ck.next()
                            put(id, child.optString(id, ""))
                        }
                    })
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun serializeNestedStringMap(map: Map<String, Map<String, String>>): String {
        val o = JSONObject()
        map.forEach { (k, child) ->
            o.put(k, JSONObject().also { c ->
                child.forEach { (id, v) -> c.put(id, v) }
            })
        }
        return o.toString()
    }

    fun parseStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, null) ?: continue
                    if (s.isNotBlank()) add(s)
                }
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun serializeStringSet(set: Set<String>): String {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        return arr.toString()
    }
}
