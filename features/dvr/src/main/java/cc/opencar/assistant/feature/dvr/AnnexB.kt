package cc.opencar.assistant.feature.dvr

import java.io.ByteArrayOutputStream

/** H.264 byte-stream helpers: Annex-B (start codes) and AVCC (4-byte length prefixes). */
object AnnexB {
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Length of the start code at [at] (4 or 3), or 0 when there is none. */
    fun startCodeAt(data: ByteArray, at: Int): Int = when {
        at + 4 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() &&
            data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte() -> 4
        at + 3 <= data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() &&
            data[at + 2] == 1.toByte() -> 3
        else -> 0
    }

    fun hasStartCode(data: ByteArray): Boolean = startCodeAt(data, 0) > 0

    fun withStartCode(nal: ByteArray): ByteArray = if (hasStartCode(nal)) nal else START_CODE + nal

    fun stripStartCode(data: ByteArray): ByteArray = data.copyOfRange(startCodeAt(data, 0), data.size)

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    /** NAL units of an Annex-B stream, without their start codes. */
    fun split(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 3 <= data.size) {
            val sc = startCodeAt(data, i)
            if (sc == 0) {
                i++
                continue
            }
            val start = i + sc
            var end = start
            while (end < data.size && startCodeAt(data, end) == 0) end++
            if (end > start) out += data.copyOfRange(start, end)
            i = end
        }
        return out
    }

    fun containsNalType(data: ByteArray, type: Int): Boolean = split(data).any { nalType(it) == type }

    /** NAL units of an AVCC access unit, or null when the length prefixes do not add up. */
    fun splitAvcc(data: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xff) shl 24) or
                ((data[i + 1].toInt() and 0xff) shl 16) or
                ((data[i + 2].toInt() and 0xff) shl 8) or
                (data[i + 3].toInt() and 0xff)
            if (len < 1 || len > data.size - i - 4) return null
            out += data.copyOfRange(i + 4, i + 4 + len)
            i += 4 + len
        }
        return if (i == data.size) out else null
    }

    fun toAvcc(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write((n.size ushr 24) and 0xff)
            out.write((n.size ushr 16) and 0xff)
            out.write((n.size ushr 8) and 0xff)
            out.write(n.size and 0xff)
            out.write(n)
        }
        return out.toByteArray()
    }
}
