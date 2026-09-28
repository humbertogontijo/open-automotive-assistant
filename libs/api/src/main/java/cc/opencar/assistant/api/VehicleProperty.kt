package cc.opencar.assistant.api

/**
 * Opaque property identity. Integrations map keys to platform-native IDs (VHAL, OBD2, …).
 */
data class VehicleProperty(
    val namespace: String,
    val key: String,
    val nativeId: Long? = null,
    val defaultAreaId: Int = 0,
) {
    val qualifiedName: String get() = "$namespace:$key"
}

sealed class PropertyValue {
    data class IntVal(val value: Int) : PropertyValue()
    data class LongVal(val value: Long) : PropertyValue()
    data class FloatVal(val value: Float) : PropertyValue()
    data class BoolVal(val value: Boolean) : PropertyValue()
    data class StringVal(val value: String) : PropertyValue()
    data class BytesVal(val value: ByteArray) : PropertyValue() {
        override fun equals(other: Any?): Boolean =
            other is BytesVal && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        /** Lowercase hex of the first [maxBytes] bytes, `…` appended when truncated. */
        fun hex(maxBytes: Int = 256): String {
            val sb = StringBuilder(minOf(value.size, maxBytes) * 2 + 1)
            for (i in 0 until minOf(value.size, maxBytes)) {
                val b = value[i].toInt() and 0xff
                sb.append(HEX[b ushr 4]).append(HEX[b and 0xf])
            }
            if (value.size > maxBytes) sb.append('…')
            return sb.toString()
        }

        private companion object {
            const val HEX = "0123456789abcdef"
        }
    }

    fun asInt(): Int? = when (this) {
        is IntVal -> value
        is LongVal -> value.toInt()
        is FloatVal -> value.toInt()
        is BoolVal -> if (value) 1 else 0
        else -> null
    }

    fun asFloat(): Float? = when (this) {
        is FloatVal -> value
        is IntVal -> value.toFloat()
        is LongVal -> value.toFloat()
        else -> null
    }

    fun display(): String = when (this) {
        is IntVal -> value.toString()
        is LongVal -> value.toString()
        is FloatVal -> value.toString()
        is BoolVal -> value.toString()
        is StringVal -> value
        is BytesVal -> "bytes[${value.size}]"
    }
}

data class CatalogEntry(
    val property: VehicleProperty,
    val name: String,
    val writable: Boolean = false,
    val areaIds: List<Int> = listOf(0),
)
