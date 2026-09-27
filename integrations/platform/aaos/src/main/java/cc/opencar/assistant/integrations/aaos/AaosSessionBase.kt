package cc.opencar.assistant.integrations.aaos

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import cc.opencar.assistant.api.CameraSource
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.PropertyValue
import cc.opencar.assistant.api.ReadOutcome
import cc.opencar.assistant.api.ScreenState
import cc.opencar.assistant.api.TelemetrySnapshot
import cc.opencar.assistant.api.VehicleEvent
import cc.opencar.assistant.api.VehicleProperty
import cc.opencar.assistant.api.VehicleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared AAOS session plumbing: observe-or-poll telemetry, entity fan-out, diagnosed reads,
 * allowlisted writes and cameras. Subclasses supply [readSnapshot], codecs and lookups, then
 * call [start] at the end of their `init`.
 */
@OptIn(FlowPreview::class)
abstract class AaosSessionBase(
    protected val context: Context,
    protected val backend: VehiclePropertyBackend,
    initialVariant: PlatformVariant,
    private val tag: String,
) : VehicleSession {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _variant = MutableStateFlow(initialVariant)
    override val variant: StateFlow<PlatformVariant> = _variant.asStateFlow()
    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    protected val eventFlow = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    private val fanout = SessionEventFanout(_telemetry, eventFlow)

    /** Active platform config (may follow the selected model). */
    protected abstract val platform: PlatformConfig
    protected abstract val catalogEntries: List<CatalogEntry>
    protected abstract val allowlist: Set<Int>

    // Derived from [platform]; rebuilt by [refreshLookups], never per event.
    @Volatile private var bindings: Map<VehicleProperty, Pair<Int, Int>> = emptyMap()
    @Volatile private var entityByProp: Map<Int, String> = emptyMap()
    @Volatile private var telemetryPropIds: Set<Int> = emptySet()

    protected abstract fun readSnapshot(): TelemetrySnapshot

    /** Native ids [readSnapshot] reads besides the bound ones. */
    protected open fun extraTelemetryPropIds(): Collection<Int> = emptyList()

    protected open fun decode(property: VehicleProperty, raw: Any?): PropertyValue? = toPropertyValue(raw)

    protected open fun encode(property: VehicleProperty, value: PropertyValue): PropertyValue = value

    /** Areas [diagnose] tries, in order. */
    protected open fun diagnoseAreas(property: VehicleProperty, boundArea: Int, areaId: Int?): List<Int> =
        listOfNotNull(areaId, boundArea).distinct()

    /** Resolution for properties without a platform binding. */
    protected open fun resolveUnbound(property: VehicleProperty): Pair<Int, Int>? = null

    /** Observe stream is live (push mode). */
    protected open fun onObserve(observe: Flow<PropertyUpdate>) = Unit

    /** No observe stream; subclasses start their own pollers. */
    protected open fun onPollMode() = Unit

    protected fun setVariant(variant: PlatformVariant) {
        _variant.value = variant
    }

    protected fun refreshLookups() {
        bindings = platform.propertyBindings()
        entityByProp = platform.entityByProp()
        telemetryPropIds = platform.bindings.values.mapTo(HashSet()) { it.nativeId } + extraTelemetryPropIds()
    }

    protected fun start(observe: Flow<PropertyUpdate>?) {
        refreshLookups()
        scope.launch { eventFlow.emit(VehicleEvent.Boot) }
        if (observe != null) {
            Log.i(tag, "telemetry: observe (push) mode — no continuous poll")
            scope.launch {
                // Seed so the UI and edge detectors have a baseline before the first delta.
                publishAll()
                observe
                    .filter { it.propId in telemetryPropIds }
                    .debounce(TELEMETRY_DEBOUNCE_MS)
                    .collect { publishTelemetry() }
            }
            scope.launch { observe.collect { fanout.onPropertyUpdate(it, entityByProp) } }
            onObserve(observe)
        } else {
            Log.i(tag, "telemetry: poll mode (${POLL_MS}ms, ${IDLE_POLL_MS}ms while the screen is off)")
            scope.launch {
                while (isActive) {
                    publishAll()
                    pollDelay(POLL_MS)
                }
            }
            onPollMode()
        }
    }

    /** Waits [activeMs]; while the screen is off, up to [IDLE_POLL_MS] or until it turns on. */
    protected suspend fun pollDelay(activeMs: Long) {
        if (ScreenState.on.value) {
            delay(activeMs)
        } else {
            withTimeoutOrNull(IDLE_POLL_MS) { ScreenState.on.first { it } }
        }
    }

    private fun publishTelemetry() {
        try {
            fanout.publishTelemetry(readSnapshot())
        } catch (_: Throwable) { /* best-effort */ }
    }

    private fun publishAll() {
        publishTelemetry()
        try {
            fanout.emitBoundSnapshots(platform.bindings) { id, area -> backend.read(id, area) }
        } catch (_: Throwable) { /* best-effort */ }
    }

    override fun telemetry(): Flow<TelemetrySnapshot> = _telemetry

    override fun events(): Flow<VehicleEvent> = eventFlow.asSharedFlow()

    override suspend fun get(property: VehicleProperty): PropertyValue? {
        val (propId, areaId) = resolve(property) ?: return null
        return redact(property, decode(property, backend.read(propId, areaId)))
    }

    override suspend fun diagnose(property: VehicleProperty, areaId: Int?): ReadOutcome {
        val (propId, boundArea) = resolve(property) ?: return ReadOutcome.Unavailable(areaId ?: 0)
        val areas = diagnoseAreas(property, boundArea, areaId)
        var last: ReadOutcome = ReadOutcome.Unavailable(areas.firstOrNull() ?: 0)
        for (a in areas) {
            last = when (val d = backend.readDetailed(propId, a)) {
                is VehiclePropertyBackend.DetailedRead.Ok ->
                    return ReadOutcome.Ok(redact(property, decode(property, d.value)), a)
                is VehiclePropertyBackend.DetailedRead.Denied -> ReadOutcome.Denied(d.permission, a, d.message)
                is VehiclePropertyBackend.DetailedRead.Failed -> ReadOutcome.Failed(d.message, a)
                VehiclePropertyBackend.DetailedRead.Empty,
                VehiclePropertyBackend.DetailedRead.Unavailable,
                -> ReadOutcome.Unavailable(a)
            }
        }
        return last
    }

    override suspend fun set(property: VehicleProperty, value: PropertyValue): Result<Unit> {
        val (propId, areaId) = resolve(property)
            ?: return Result.failure(IllegalArgumentException("Unknown property ${property.qualifiedName}"))
        if (propId !in allowlist) {
            return Result.failure(SecurityException("Property not on writable allowlist"))
        }
        val ok = when (val encoded = encode(property, value)) {
            is PropertyValue.IntVal -> backend.writeInt(propId, areaId, encoded.value)
            is PropertyValue.FloatVal -> backend.writeFloat(propId, areaId, encoded.value)
            is PropertyValue.BoolVal -> backend.writeBoolean(propId, areaId, encoded.value)
            is PropertyValue.LongVal -> backend.writeInt(propId, areaId, encoded.value.toInt())
            else -> false
        }
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("VHAL write failed"))
    }

    override fun catalog(): List<CatalogEntry> = catalogEntries

    override fun entityBindings(): Map<Long, String> =
        platform.bindings.entries.associate { (entityId, b) -> b.nativeId.toLong() to entityId }

    override fun hasBinding(property: VehicleProperty): Boolean = resolve(property) != null

    override fun androidVolumeGroups() = platform.androidVolumeGroups()

    override fun cameras(): List<CameraSource> = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        platform.resolveCameras(cm.cameraIdList.toList())
    } catch (t: Throwable) {
        Log.w(tag, "camera enum failed: ${t.message}")
        emptyList()
    }

    override fun close() {
        scope.cancel()
        backend.close()
    }

    private fun resolve(property: VehicleProperty): Pair<Int, Int>? {
        val bound = bindings[property]
            ?: platform.bindings[property.key]?.let { it.nativeId to it.areaId }
            ?: return resolveUnbound(property)
        val preferred = property.defaultAreaId
        return bound.first to if (preferred != 0) preferred else bound.second
    }

    private fun redact(property: VehicleProperty, value: PropertyValue?): PropertyValue? =
        if (property.key == "INFO_VIN" && value is PropertyValue.StringVal) {
            PropertyValue.StringVal(redactVin(value.value))
        } else {
            value
        }

    companion object {
        const val POLL_MS = 1_000L
        const val IDLE_POLL_MS = 10_000L
        private const val TELEMETRY_DEBOUNCE_MS = 150L

        fun redactVin(vin: String): String =
            if (vin.length < 8) "[redacted]" else vin.take(3) + "****" + vin.takeLast(4)
    }
}
