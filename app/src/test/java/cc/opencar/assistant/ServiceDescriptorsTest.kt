package cc.opencar.assistant

import cc.opencar.assistant.api.VehicleIntegration
import cc.opencar.assistant.api.plugin.OaaPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The runtime finds integrations and plugins only through these ServiceLoader descriptors. */
class ServiceDescriptorsTest {
    private val loader = javaClass.classLoader!!

    private fun providers(service: Class<*>): Set<String> =
        loader.getResources("META-INF/services/${service.name}").toList()
            .flatMap { url -> url.readText().lines() }
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun assertLoadable(service: Class<*>, names: Set<String>) {
        for (name in names) {
            val cls = Class.forName(name, false, loader)
            assertTrue("$name must implement ${service.simpleName}", service.isAssignableFrom(cls))
            assertTrue(
                "$name needs a public no-arg constructor",
                cls.constructors.any { it.parameterCount == 0 && Modifier.isPublic(it.modifiers) },
            )
        }
    }

    @Test
    fun vehicleIntegrations() {
        val names = providers(VehicleIntegration::class.java)
        assertEquals(
            setOf(
                "cc.opencar.assistant.integrations.antora1000.Antora1000Integration",
                "cc.opencar.assistant.integrations.demo.DemoIntegration",
                "cc.opencar.assistant.integrations.ihu629g.Ihu629gIntegration",
            ),
            names,
        )
        assertLoadable(VehicleIntegration::class.java, names)
    }

    @Test
    fun plugins() {
        val names = providers(OaaPlugin::class.java)
        assertEquals(setOf("cc.opencar.assistant.plugin.homeassistant.HomeAssistantPlugin"), names)
        assertLoadable(OaaPlugin::class.java, names)
    }
}
