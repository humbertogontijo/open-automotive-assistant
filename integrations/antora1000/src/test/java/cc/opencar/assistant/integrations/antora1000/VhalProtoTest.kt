package cc.opencar.assistant.integrations.antora1000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VhalProtoTest {
    private fun valueList(vararg props: ByteArray): ByteArray {
        val list = Pb()
        for (p in props) list.bytes(1, Pb().bytes(1, p).varint(2, 0).build())
        return list.build()
    }

    @Test
    fun parsesBytesValue() {
        val payload = fixture("tbt_idle.hex")
        val prop = Pb()
            .varint(1, 0x2170744fL)
            .varint(2, 0x00700000L)
            .varint(3, 37_125_603_061L)
            .varint(4, 0)
            .bytes(9, payload)
            .varint(10, 0)
            .build()
        val parsed = VhalProto.parseValueList(valueList(prop)).single()
        assertEquals(0x2170744f, parsed.propId)
        assertArrayEquals(payload, parsed.bytes)
        assertArrayEquals(payload, parsed.primary() as ByteArray)
    }

    @Test
    fun emptyBytesValueIsKept() {
        val prop = Pb().varint(1, 0x21707450L).bytes(9, ByteArray(0)).build()
        val parsed = VhalProto.parseValueList(valueList(prop)).single()
        assertEquals(0, (parsed.primary() as ByteArray).size)
    }

    @Test
    fun packedInt32LeavesBytesUnset() {
        val packedZigzag = Pb().varint(1, 0x11400400L).bytes(5, byteArrayOf(8)).build()
        val parsed = VhalProto.parseValueList(valueList(packedZigzag)).single()
        assertEquals(4, parsed.primary())
        assertNull(parsed.bytes)
    }
}
