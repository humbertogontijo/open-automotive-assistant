package cc.opencar.assistant.integrations.demo

import android.content.Context
import cc.opencar.assistant.api.Capability
import cc.opencar.assistant.api.DeviceFingerprint
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.VehicleIntegration
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.integrations.aaos.PlatformConfig

/**
 * In-memory fake vehicle for CI and laptop contributors (no head unit).
 * Match fingerprint needles `demo` / `oaa-demo`, or Lab override integration id `demo`.
 */
class DemoIntegration : VehicleIntegration {
    override val id: String = ID
    override val displayName: String = "Demo (fake vehicle)"

    @Volatile private var config: PlatformConfig? = null

    override fun warm(context: Context) {
        runCatching { config = PlatformConfig.load(context) }
    }

    private fun cfg(): PlatformConfig = config ?: FALLBACK

    override fun matches(device: DeviceFingerprint): Boolean =
        device.containsAny(*cfg().match.toTypedArray())

    override fun detectVariant(session: VehicleSession): PlatformVariant =
        PlatformVariant("default", "Demo")

    override fun capabilities(variant: PlatformVariant): Set<Capability> =
        cfg().capabilities + variant.extraCapabilities

    override suspend fun connect(context: Context): VehicleSession {
        val platform = PlatformConfig.load(context).also { config = it }
        return DemoSession(platform, PlatformVariant("default", "Demo"))
    }

    companion object {
        const val ID = "demo"

        private val FALLBACK = PlatformConfig(
            id = ID,
            displayName = "Demo (fake vehicle)",
            backend = "demo",
            match = listOf("demo", "oaa-demo"),
            capabilities = setOf(
                Capability.READ_TELEMETRY,
                Capability.WRITE_SETTINGS,
                Capability.DRIVE_MODES,
                Capability.HVAC,
                Capability.CHARGING,
                Capability.GEAR_EVENTS,
                Capability.IGNITION_EVENTS,
            ),
            variants = emptyList(),
            properties = emptyList(),
        )
    }
}
