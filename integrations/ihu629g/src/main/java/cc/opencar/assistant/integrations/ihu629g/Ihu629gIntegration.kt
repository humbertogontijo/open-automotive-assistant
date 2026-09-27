package cc.opencar.assistant.integrations.ihu629g

import android.content.Context
import cc.opencar.assistant.api.Capability
import cc.opencar.assistant.api.DeviceFingerprint
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.QuickEntry
import cc.opencar.assistant.api.VehicleIntegration
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.api.WakeSignals
import cc.opencar.assistant.integrations.aaos.PlatformConfig
import cc.opencar.assistant.integrations.platform.flyme.FlymePlatform

class Ihu629gIntegration : VehicleIntegration {
    override val id: String = ID
    override val displayName: String = "IHU629G (EX2 / Geometry E)"

    @Volatile private var config: PlatformConfig? = null

    override fun warm(context: Context) {
        runCatching { config = PlatformConfig.load(context) }
    }

    private fun cfg(): PlatformConfig = config ?: FALLBACK

    override fun matches(device: DeviceFingerprint): Boolean =
        device.containsAny(*cfg().match.toTypedArray())

    override fun createQuickEntry(): QuickEntry = FlymePlatform.createStatusBarQuickEntry()

    override fun wakeSignals(): WakeSignals = FlymePlatform.wakeSignals()

    override fun detectVariant(session: VehicleSession): PlatformVariant =
        PlatformVariant("default", "EX2 / Geometry E")

    override fun capabilities(variant: PlatformVariant): Set<Capability> =
        cfg().capabilities + variant.extraCapabilities

    override suspend fun connect(context: Context): VehicleSession {
        val platform = PlatformConfig.load(context).also { config = it }
        return Ihu629gSession(context, platform, PlatformVariant("default", "EX2 / Geometry E"))
    }

    companion object {
        const val ID = "ihu629g"
        private val FALLBACK = PlatformConfig(
            id = ID,
            displayName = "IHU629G (EX2 / Geometry E)",
            backend = "vhal",
            match = listOf("ihu629", "IHU629", "ihu629g", "geometry"),
            capabilities = setOf(
                Capability.READ_TELEMETRY,
                Capability.WRITE_SETTINGS,
                Capability.DRIVE_MODES,
                Capability.HVAC,
                Capability.CHARGING,
                Capability.GEAR_EVENTS,
                Capability.IGNITION_EVENTS,
            ),
            properties = emptyList(),
        )
    }
}
