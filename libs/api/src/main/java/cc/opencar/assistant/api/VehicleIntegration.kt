package cc.opencar.assistant.api

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface VehicleIntegration {
    val id: String
    val displayName: String

    fun matches(device: DeviceFingerprint): Boolean

    fun detectVariant(session: VehicleSession): PlatformVariant

    fun capabilities(variant: PlatformVariant): Set<Capability>

    suspend fun connect(context: Context): VehicleSession

    /**
     * Preload platform config / match needles before [matches] runs.
     * Default: no-op.
     */
    fun warm(context: Context) {}

    /**
     * Optional persistent UI entry for this platform (Flyme status-bar icon,
     * float chip, …). Default: none — [cc.opencar.assistant.feature.shortcuts]
     * falls back to a float chip.
     */
    fun createQuickEntry(): QuickEntry? = null

    /**
     * Optional vendor wake/sleep broadcast actions layered on top of AOSP
     * SCREEN_ON / SCREEN_OFF. Default: none.
     */
    fun wakeSignals(): WakeSignals = WakeSignals()
}

interface VehicleSession {
    val integrationId: String
    val variant: StateFlow<PlatformVariant>

    fun telemetry(): Flow<TelemetrySnapshot>
    fun events(): Flow<VehicleEvent>

    suspend fun get(property: VehicleProperty): PropertyValue?
    suspend fun set(property: VehicleProperty, value: PropertyValue): Result<Unit>

    /** Diagnostic read used by CatalogProbe (permission / area aware). */
    suspend fun diagnose(property: VehicleProperty, areaId: Int? = null): ReadOutcome =
        try {
            val v = get(property)
            if (v != null) ReadOutcome.Ok(v, areaId ?: property.defaultAreaId)
            else ReadOutcome.Unavailable(areaId ?: property.defaultAreaId)
        } catch (t: SecurityException) {
            ReadOutcome.Denied(t.message, areaId ?: property.defaultAreaId, t.message)
        } catch (t: Throwable) {
            ReadOutcome.Failed(t.message, areaId ?: property.defaultAreaId)
        }

    fun catalog(): List<CatalogEntry>
    fun cameras(): List<CameraSource>

    /**
     * Native VHAL id → product entity / control id for props with `entity` in `platform.json`.
     * Lab uses this to mark catalog rows as cards vs still-missing.
     */
    fun entityBindings(): Map<Long, String> = emptyMap()

    /**
     * True when this session maps [property] to a native id.
     * Used for write-only commands where [diagnose] may be Unavailable but [set] still works.
     */
    fun hasBinding(property: VehicleProperty): Boolean = false

    /** Native range the integration declares for [property]; null when undeclared. */
    fun valueRange(property: VehicleProperty): ValueRange? = null

    /**
     * Cabin volume groups from `platform.json` → `android.volumeGroups`
     * (after `extends` merge). Empty when the platform does not declare any.
     */
    fun androidVolumeGroups(): List<AndroidVolumeGroup> = emptyList()

    fun close()
}

interface IntegrationRegistry {
    fun all(): List<VehicleIntegration>
    fun match(fingerprint: DeviceFingerprint): VehicleIntegration?
}
