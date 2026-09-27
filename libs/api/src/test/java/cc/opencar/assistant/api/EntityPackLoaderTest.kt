package cc.opencar.assistant.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EntityPackLoaderTest {
    @Test
    fun loadStandardPilotFromResources() {
        val pack = EntityPackLoader.loadClasspath("entities/standard-pilot.json")
        assertTrue(pack.size >= 6)
        val climate = pack.first { it.id == "climate.cabin" }
        assertEquals(EntityType.CLIMATE, climate.domain)
        assertTrue(climate.isComposite)
        assertNull(climate.bindingKey)
        assertEquals("HVAC_POWER_ON", climate.attributes["power"])
        assertEquals("climate", climate.resolvedSection())

        val speed = pack.first { it.id == "PERF_VEHICLE_SPEED" }
        assertEquals("PERF_VEHICLE_SPEED", speed.bindingKey)
        assertEquals(DeviceClass.SPEED, speed.deviceClass)
        assertFalse(speed.writable)
    }

    @Test
    fun mergePrefersDeclarative() {
        val builtin = listOf(
            EntityDef(
                id = "climate.cabin",
                domain = EntityType.CLIMATE,
                group = "controls",
                bindingKey = null,
                attributes = mapOf("power" to "old"),
                input = "climate",
            ),
            EntityDef(
                id = "switch.wifi",
                domain = EntityType.SWITCH,
                group = "connect",
                bindingKey = "wifi",
                input = "bool",
            ),
        )
        val declarative = listOf(
            EntityDef(
                id = "climate.cabin",
                domain = EntityType.CLIMATE,
                group = "controls",
                bindingKey = null,
                attributes = mapOf("power" to "hvac_power"),
                input = "climate",
            ),
        )
        val merged = EntityPackLoader.merge(declarative, builtin)
        assertEquals(2, merged.size)
        assertEquals("hvac_power", merged.first { it.id == "climate.cabin" }.attributes["power"])
        assertNotNull(merged.firstOrNull { it.id == "switch.wifi" })
    }

    @Test
    fun registryLoadsPilotClimate() {
        val climate = EntityRegistry.CLIMATE
        assertEquals("climate.cabin", climate.id)
        assertTrue(climate.isComposite)
        assertNotNull(EntityRegistry.byId("PERF_VEHICLE_SPEED"))
        assertNotNull(EntityRegistry.byId("TYPE_EV_BATTERY_PERCENTAGE"))
        assertNull(EntityRegistry.byId("sensor.speed"))
        // File exists for contributors inspecting the pack.
        val file = File("src/main/resources/entities/standard-pilot.json")
        assertTrue("expected ${file.absolutePath}", file.isFile)
    }
}
