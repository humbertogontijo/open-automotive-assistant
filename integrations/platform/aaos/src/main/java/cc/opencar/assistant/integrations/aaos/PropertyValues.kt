package cc.opencar.assistant.integrations.aaos

import cc.opencar.assistant.api.PropertyValue

/** Shared conversion from raw VHAL/backend values to [PropertyValue]. */
fun toPropertyValue(raw: Any?): PropertyValue? = when (raw) {
    null -> null
    is Int -> PropertyValue.IntVal(raw)
    is Long -> PropertyValue.LongVal(raw)
    is Float -> PropertyValue.FloatVal(raw)
    is Double -> PropertyValue.FloatVal(raw.toFloat())
    is Boolean -> PropertyValue.BoolVal(raw)
    is String -> PropertyValue.StringVal(raw)
    is ByteArray -> PropertyValue.BytesVal(raw)
    is Number -> PropertyValue.IntVal(raw.toInt())
    else -> PropertyValue.StringVal(raw.toString())
}
