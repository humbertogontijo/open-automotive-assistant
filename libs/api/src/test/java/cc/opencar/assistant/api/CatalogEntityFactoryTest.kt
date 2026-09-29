package cc.opencar.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogEntityFactoryTest {
    private fun entry(key: String, writable: Boolean, writeLocked: Boolean = false, vhalType: Int? = null) =
        CatalogEntry(
            property = VehicleProperty("vhal", key),
            name = key,
            writable = writable,
            writeLocked = writeLocked,
            vhalType = vhalType,
        )

    private fun def(e: CatalogEntry) = CatalogEntityFactory.fromCatalog(listOf(e), claimed = emptySet()).single()

    @Test
    fun writeLockedKeepsItsControlButIsNotWritable() {
        val d = def(entry("SETTING_FUNC_STR_SWITCH", writable = false, writeLocked = true, vhalType = 0x40))
        assertEquals(EntityType.SWITCH, d.domain)
        assertFalse(d.writable)
    }

    @Test
    fun readOnlyIsSensor() {
        val d = def(entry("SETTING_FUNC_SOMETHING", writable = false, vhalType = 0x40))
        assertEquals(EntityType.SENSOR, d.domain)
    }

    @Test
    fun vhalTypePicksFallbackWidget() {
        assertEquals("int", def(entry("SETTING_FUNC_PCM_TIMER", writable = true, vhalType = 0x40)).input)
        assertEquals("float", def(entry("SETTING_FUNC_SEAT_LENGTH_POS", writable = true, vhalType = 0x60)).input)
        assertEquals("bool", def(entry("SETTING_FUNC_ONE_CLICK_TRAILER", writable = true, vhalType = 0x20)).input)
        assertEquals(EntityType.SENSOR, def(entry("SWITCH_USER", writable = true, vhalType = 0xe0)).domain)
    }

    @Test
    fun untypedIdsKeepNameHeuristics() {
        val d = def(entry("regen", writable = true))
        assertEquals(EntityType.SWITCH, d.domain)
        assertTrue(d.writable)
    }
}
