package cc.opencar.assistant.integrations.antora1000

import java.io.ByteArrayOutputStream

/** Minimal protobuf writer for building test payloads. */
internal class Pb {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
        raw((field shl 3).toLong())
        raw(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray) = apply {
        raw(((field shl 3) or 2).toLong())
        raw(value.size.toLong())
        out.write(value)
    }

    fun double(field: Int, value: Double) = apply {
        raw(((field shl 3) or 1).toLong())
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xff).toInt())
    }

    fun build(): ByteArray = out.toByteArray()

    private fun raw(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

internal fun fixture(name: String): ByteArray {
    val hex = requireNotNull(Pb::class.java.classLoader?.getResource("navi/$name")) { "missing fixture $name" }
        .readText().trim()
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
