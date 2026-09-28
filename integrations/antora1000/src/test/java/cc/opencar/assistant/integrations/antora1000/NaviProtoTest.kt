package cc.opencar.assistant.integrations.antora1000

import cc.opencar.assistant.api.PropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviProtoTest {
    private fun activeTbt(dist: Int, unit: Int, road: String = "Rua Augusta") = Pb()
        .varint(1, 1)
        .varint(2, 3)
        .varint(3, dist.toLong())
        .varint(4, unit.toLong())
        .string(5, road)
        .double(8, -46.65)
        .double(9, -23.55)
        .varint(12, 2)
        .build()

    private fun activeEta(dist: Int, unit: Int, minutes: Int) = Pb()
        .varint(1, dist.toLong())
        .varint(2, unit.toLong())
        .varint(3, minutes.toLong())
        .string(4, "16:58")
        .double(5, -46.6)
        .double(6, -23.5)
        .build()

    @Test
    fun decodesCapturedIdleTbt() {
        val tbt = NaviProto.decodeTbt(fixture("tbt_idle.hex"))!!
        assertEquals(0, tbt.status)
        assertFalse(tbt.active)
        assertEquals(0, tbt.distanceMeters)
        assertEquals("", tbt.roadName)
    }

    @Test
    fun decodesCapturedIdleEtaWithStaleTimeString() {
        val eta = NaviProto.decodeEta(fixture("eta_idle.hex"))!!
        assertEquals(0, eta.timeMin)
        assertEquals(0, eta.distanceMeters)
        assertEquals("15:38 到达", eta.timeString)
    }

    @Test
    fun decodesActiveTbtAndEta() {
        val tbt = NaviProto.decodeTbt(activeTbt(350, 0))!!
        assertTrue(tbt.active)
        assertEquals(3, tbt.arrow)
        assertEquals(350, tbt.distanceMeters)
        assertEquals("Rua Augusta", tbt.roadName)
        assertEquals(2, tbt.appType)

        val eta = NaviProto.decodeEta(activeEta(123, 1, 17))!!
        assertEquals(12_300, eta.distanceMeters)
        assertEquals(17, eta.timeMin)
        assertEquals("16:58", eta.timeString)
    }

    @Test
    fun unknownUnitHasNoDistance() {
        assertNull(NaviProto.decodeTbt(activeTbt(5, 7))!!.distanceMeters)
    }

    @Test
    fun rejectsTruncatedPayload() {
        val bytes = activeTbt(350, 0)
        assertNull(NaviProto.decodeTbt(bytes.copyOf(bytes.size - 3)))
    }

    @Test
    fun sensorsWithheldWhileIdle() {
        val values = NaviSensors.values(
            NaviProto.decodeTbt(fixture("tbt_idle.hex")),
            NaviProto.decodeEta(fixture("eta_idle.hex")),
        )
        assertEquals(PropertyValue.IntVal(0), values[NaviSensors.ACTIVE])
        assertNull(values[NaviSensors.ETA_MIN])
        assertNull(values[NaviSensors.ETA_DISTANCE])
        assertNull(values[NaviSensors.NEXT_TURN_DISTANCE])
        assertNull(values[NaviSensors.ROAD_NAME])
    }

    @Test
    fun sensorsWhileNavigating() {
        val values = NaviSensors.values(
            NaviProto.decodeTbt(activeTbt(12, 1, road = "  Av. Paulista ")),
            NaviProto.decodeEta(activeEta(456, 1, 42)),
        )
        assertEquals(PropertyValue.IntVal(1), values[NaviSensors.ACTIVE])
        assertEquals(PropertyValue.IntVal(1_200), values[NaviSensors.NEXT_TURN_DISTANCE])
        assertEquals(PropertyValue.StringVal("Av. Paulista"), values[NaviSensors.ROAD_NAME])
        assertEquals(PropertyValue.IntVal(42), values[NaviSensors.ETA_MIN])
        assertEquals(PropertyValue.FloatVal(45.6f), values[NaviSensors.ETA_DISTANCE])
    }

    @Test
    fun noTbtMeansUnknownActive() {
        val values = NaviSensors.values(null, NaviProto.decodeEta(activeEta(456, 1, 42)))
        assertNull(values[NaviSensors.ACTIVE])
        assertNull(values[NaviSensors.ETA_MIN])
    }
}
