package cc.opencar.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainDefaultsTest {
    private val ambient = ValueRange(min = 0f, max = 20f, step = 1f)
    private val brightness = DomainDefaults.LIGHT_BRIGHTNESS

    @Test
    fun scalesNativeToCanonicalAndBack() {
        assertEquals(0f, DomainDefaults.toCanonical(0f, ambient, brightness))
        assertEquals(128f, DomainDefaults.toCanonical(10f, ambient, brightness))
        assertEquals(255f, DomainDefaults.toCanonical(20f, ambient, brightness))
        assertEquals(20f, DomainDefaults.fromCanonical(255f, ambient, brightness))
        assertEquals(10f, DomainDefaults.fromCanonical(128f, ambient, brightness))
        assertEquals(0f, DomainDefaults.fromCanonical(0f, ambient, brightness))
    }

    @Test
    fun dimButOnNeverWritesNativeMinimum() {
        assertEquals(1f, DomainDefaults.fromCanonical(1f, ambient, brightness))
    }

    @Test
    fun unboundedNativeRangeIsIdentity() {
        assertEquals(42f, DomainDefaults.toCanonical(42f, null, brightness))
        assertEquals(42f, DomainDefaults.fromCanonical(42f, ValueRange(step = 1f), brightness))
    }

    @Test
    fun levelsExpandRangeOrUseDeclaredValues() {
        assertEquals((0..9).toList(), ValueRange(0f, 9f, 1f).levels())
        assertEquals(listOf(1, 2, 4), ValueRange(0f, 9f, values = listOf(1, 2, 4)).levels())
        assertNull(ValueRange(step = 1f).levels())
    }

    @Test
    fun orElseFillsOnlyMissingFields() {
        val r = ValueRange(min = 15.5f, max = 28.5f).orElse(DomainDefaults.CLIMATE_TEMPERATURE)
        assertEquals(ValueRange(15.5f, 28.5f, 0.5f), r)
    }

    @Test
    fun rgbColorRoundTrip() {
        assertEquals(listOf(35, 181, 29), RgbColor.toList(0x23B51D))
        assertEquals(0x23B51D, RgbColor.parse("35,181,29"))
        assertEquals(0x23B51D, RgbColor.parse("[35, 181, 29]"))
        assertEquals(0x23B51D, RgbColor.parse("#23b51d"))
        assertNull(RgbColor.parse("300,0,0"))
        assertNull(RgbColor.parse("1,2"))
    }
}
