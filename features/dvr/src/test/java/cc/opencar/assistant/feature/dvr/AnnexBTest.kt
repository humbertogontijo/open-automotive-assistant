package cc.opencar.assistant.feature.dvr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c)
    private val idr = byteArrayOf(0x65, 0x11, 0x00, 0x22)

    @Test
    fun startCodes() {
        assertEquals(4, AnnexB.startCodeAt(byteArrayOf(0, 0, 0, 1, 0x67), 0))
        assertEquals(3, AnnexB.startCodeAt(byteArrayOf(0, 0, 1, 0x67), 0))
        assertEquals(0, AnnexB.startCodeAt(byteArrayOf(0, 0, 2, 0x67), 0))
        assertArrayEquals(sps, AnnexB.stripStartCode(AnnexB.withStartCode(sps)))
        assertArrayEquals(sps, AnnexB.stripStartCode(sps))
        val prefixed = AnnexB.withStartCode(sps)
        assertTrue(AnnexB.withStartCode(prefixed) === prefixed)
    }

    @Test
    fun splitsMixedStartCodes() {
        val stream = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr
        val nals = AnnexB.split(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
        assertArrayEquals(idr, nals[2])
        assertTrue(AnnexB.containsNalType(stream, AnnexB.NAL_SPS))
        assertFalse(AnnexB.containsNalType(byteArrayOf(0, 0, 0, 1) + idr, AnnexB.NAL_SPS))
    }

    @Test
    fun avccRoundTrip() {
        val avcc = AnnexB.toAvcc(listOf(sps, idr))
        assertEquals(4 + sps.size + 4 + idr.size, avcc.size)
        val back = AnnexB.splitAvcc(avcc)!!
        assertArrayEquals(sps, back[0])
        assertArrayEquals(idr, back[1])
        assertNull(AnnexB.splitAvcc(byteArrayOf(0, 0, 0, 9, 1, 2)))
        assertNull(AnnexB.splitAvcc(avcc + byteArrayOf(1)))
    }

    @Test
    fun nalType() {
        assertEquals(AnnexB.NAL_SPS, AnnexB.nalType(sps))
        assertEquals(AnnexB.NAL_PPS, AnnexB.nalType(pps))
        assertEquals(5, AnnexB.nalType(idr))
        assertEquals(-1, AnnexB.nalType(ByteArray(0)))
    }
}
