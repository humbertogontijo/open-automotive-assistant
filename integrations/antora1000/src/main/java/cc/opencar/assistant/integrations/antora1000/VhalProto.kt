package cc.opencar.assistant.integrations.antora1000

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-rolled vhal_proto messages matching VenusVehicleServer.
 *
 * VehiclePropValue field numbers:
 * 1 prop, 2 value_type, 3 timestamp, 4 area_id, 5 int32_values (packed),
 * 6 int64_values, 7 float_values, 8 string_value, 9 bytes_value, 10 status.
 *
 * SetPropertyRequest: 1 value, 2 update_status.
 * VehiclePropValueList: 1 values (repeated SetPropertyRequest-shaped entries).
 * SetPropertyResponse: 1 status_code.
 *
 * Write quirk: the server zigzag-decodes int32_values (sint32 wire) even though
 * the client schema labels the field INT32 — encode with [zigzag32] on write.
 * Stream reads use the same zigzag encoding (plain decode doubles values:
 * 4→8, 6→12, 1→2) — decode with [unzigzag32] on read.
 */
internal object VhalProto {
    fun zigzag32(n: Int): Int = (n shl 1) xor (n shr 31)

    fun unzigzag32(n: Int): Int = (n ushr 1) xor -(n and 1)

    fun encodeSetInt(propId: Int, areaId: Int, value: Int): ByteArray =
        encodeSetIntVector(propId, areaId, listOf(value))

    fun encodeSetIntVector(propId: Int, areaId: Int, values: List<Int>): ByteArray {
        val prop = ByteArrayOutputStream()
        writeVarintField(prop, 1, propId.toLong() and 0xffffffffL)
        writeVarintField(prop, 3, 0)
        writeVarintField(prop, 4, areaId.toLong() and 0xffffffffL)
        writePackedVarintField(prop, 5, values.map { zigzag32(it).toLong() and 0xffffffffL })
        val req = ByteArrayOutputStream()
        writeLenField(req, 1, prop.toByteArray())
        writeVarintField(req, 2, 0) // update_status = false
        return req.toByteArray()
    }

    fun encodeSetFloat(propId: Int, areaId: Int, value: Float): ByteArray {
        val prop = ByteArrayOutputStream()
        writeVarintField(prop, 1, propId.toLong() and 0xffffffffL)
        writeVarintField(prop, 3, 0)
        writeVarintField(prop, 4, areaId.toLong() and 0xffffffffL)
        writeFixed32Field(prop, 7, java.lang.Float.floatToIntBits(value))
        val req = ByteArrayOutputStream()
        writeLenField(req, 1, prop.toByteArray())
        writeVarintField(req, 2, 0)
        return req.toByteArray()
    }

    data class CachedProp(
        val propId: Int,
        val areaId: Int,
        val int32: List<Int> = emptyList(),
        val float: List<Float> = emptyList(),
        val int64: List<Long> = emptyList(),
        val string: String? = null,
        val status: Int = 0,
    ) {
        fun primary(): Any? = when {
            float.isNotEmpty() -> float[0]
            int32.isNotEmpty() -> int32[0]
            int64.isNotEmpty() -> int64[0]
            string != null -> string
            else -> null
        }
    }

    /** Parse VehiclePropValueList (field 1 = repeated entries with nested value). */
    fun parseValueList(bytes: ByteArray): List<CachedProp> {
        val out = ArrayList<CachedProp>()
        var i = 0
        while (i < bytes.size) {
            val (key, ni) = readVarint(bytes, i)
            i = ni
            val fn = (key ushr 3).toInt()
            val wt = (key and 7).toInt()
            when {
                wt == 2 -> {
                    val (len, nj) = readVarint(bytes, i)
                    i = nj
                    val end = i + len.toInt()
                    if (fn == 1) {
                        parseEntry(bytes, i, end)?.let { out.add(it) }
                    }
                    i = end
                }
                wt == 0 -> {
                    val (_, nj) = readVarint(bytes, i)
                    i = nj
                }
                wt == 5 -> i += 4
                wt == 1 -> i += 8
                else -> break
            }
        }
        return out
    }

    private fun parseEntry(buf: ByteArray, start: Int, end: Int): CachedProp? {
        var i = start
        var valueSlice: Pair<Int, Int>? = null
        while (i < end) {
            val (key, ni) = readVarint(buf, i)
            i = ni
            val fn = (key ushr 3).toInt()
            val wt = (key and 7).toInt()
            when {
                wt == 2 -> {
                    val (len, nj) = readVarint(buf, i)
                    i = nj
                    val e = i + len.toInt()
                    if (fn == 1) valueSlice = i to e
                    i = e
                }
                wt == 0 -> {
                    val (_, nj) = readVarint(buf, i)
                    i = nj
                }
                wt == 5 -> i += 4
                wt == 1 -> i += 8
                else -> return null
            }
        }
        val slice = valueSlice ?: return null
        return parsePropValue(buf, slice.first, slice.second)
    }

    private fun parsePropValue(buf: ByteArray, start: Int, end: Int): CachedProp? {
        var i = start
        var propId = 0
        var areaId = 0
        var status = 0
        val int32 = ArrayList<Int>()
        val float = ArrayList<Float>()
        val int64 = ArrayList<Long>()
        var string: String? = null
        while (i < end) {
            val (key, ni) = readVarint(buf, i)
            i = ni
            val fn = (key ushr 3).toInt()
            val wt = (key and 7).toInt()
            when {
                wt == 0 -> {
                    val (v, nj) = readVarint(buf, i)
                    i = nj
                    when (fn) {
                        1 -> propId = v.toInt()
                        4 -> areaId = v.toInt()
                        // Same zigzag as writes — plain int32 doubles live values.
                        5 -> int32.add(unzigzag32(v.toInt()))
                        6 -> int64.add(v)
                        10 -> status = v.toInt()
                    }
                }
                wt == 2 -> {
                    val (len, nj) = readVarint(buf, i)
                    i = nj
                    val e = i + len.toInt()
                    when (fn) {
                        5 -> { // packed int32 (zigzag, matching encodeSetInt)
                            var p = i
                            while (p < e) {
                                val (v, np) = readVarint(buf, p)
                                int32.add(unzigzag32(v.toInt()))
                                p = np
                            }
                        }
                        6 -> {
                            var p = i
                            while (p < e) {
                                val (v, np) = readVarint(buf, p)
                                int64.add(v)
                                p = np
                            }
                        }
                        7 -> { // packed float
                            var p = i
                            while (p + 4 <= e) {
                                float.add(ByteBuffer.wrap(buf, p, 4).order(ByteOrder.LITTLE_ENDIAN).float)
                                p += 4
                            }
                        }
                        8 -> string = String(buf, i, len.toInt(), Charsets.UTF_8)
                    }
                    i = e
                }
                wt == 5 -> {
                    val bits = ByteBuffer.wrap(buf, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    i += 4
                    if (fn == 7) float.add(java.lang.Float.intBitsToFloat(bits))
                }
                wt == 1 -> i += 8
                else -> return null
            }
        }
        return CachedProp(propId, areaId, int32, float, int64, string, status)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarint(out, ((field shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writePackedVarintField(out: ByteArrayOutputStream, field: Int, values: List<Long>) {
        val packed = ByteArrayOutputStream()
        for (v in values) writeVarint(packed, v)
        writeLenField(out, field, packed.toByteArray())
    }

    private fun writeFixed32Field(out: ByteArrayOutputStream, field: Int, bits: Int) {
        writeVarint(out, ((field shl 3) or 5).toLong())
        out.write(bits and 0xff)
        out.write((bits ushr 8) and 0xff)
        out.write((bits ushr 16) and 0xff)
        out.write((bits ushr 24) and 0xff)
    }

    private fun writeLenField(out: ByteArrayOutputStream, field: Int, data: ByteArray) {
        writeVarint(out, ((field shl 3) or 2).toLong())
        writeVarint(out, data.size.toLong())
        out.write(data)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7f.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xff
            i++
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) break
        }
        return result to i
    }
}
