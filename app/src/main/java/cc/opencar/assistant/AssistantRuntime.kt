package cc.opencar.assistant

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.Capability
import cc.opencar.assistant.api.IntegrationRegistry
import cc.opencar.assistant.api.PendingWake
import cc.opencar.assistant.api.ServiceLoaderIntegrationRegistry
import cc.opencar.assistant.api.VehicleIntegration
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.api.WakeSignals
import cc.opencar.assistant.api.plugin.PluginHost
import cc.opencar.assistant.api.plugin.PluginRegistry
import cc.opencar.assistant.api.plugin.ServiceLoaderPluginRegistry
import cc.opencar.assistant.feature.debug.CatalogProbe
import cc.opencar.assistant.feature.debug.Obd2Probe
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.history.EntityHistoryRecorder
import cc.opencar.assistant.feature.install.ApkInstaller
import cc.opencar.assistant.feature.memory.SettingsMemoryController
import cc.opencar.assistant.feature.shortcuts.ShortcutsController
import cc.opencar.assistant.feature.telemetry.TelemetryRepository
import cc.opencar.assistant.feature.web.AndroidSettingsController
import cc.opencar.assistant.feature.web.ControlCatalog
import cc.opencar.assistant.feature.web.HubClient
import cc.opencar.assistant.feature.web.LocationTrackerController
import cc.opencar.assistant.feature.web.OaaWebServer
import cc.opencar.assistant.support.OaaPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class AssistantRuntime(private val app: OaaApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startMutex = Mutex()
    private val registry: IntegrationRegistry = ServiceLoaderIntegrationRegistry(app.classLoader)
    val plugins: PluginRegistry = ServiceLoaderPluginRegistry(app.classLoader)

    val debug = ContributorDebugState(app)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    var integration: VehicleIntegration? = null
        private set
    var session: VehicleSession? = null
        private set
    var capabilities: Set<Capability> = emptySet()
        private set

    var memory: SettingsMemoryController? = null
        private set
    var telemetry: TelemetryRepository? = null
        private set
    var web: OaaWebServer? = null
        private set
    var installer: ApkInstaller? = null
        private set
    var dvr: DvrController? = null
        private set
    var probe: CatalogProbe? = null
        private set
    var obd2: Obd2Probe? = null
        private set
    var androidSettings: AndroidSettingsController? = null
        private set
    var locationTracker: LocationTrackerController? = null
        private set
    var history: EntityHistoryRecorder? = null
        private set
    var shortcuts: ShortcutsController? = null
        private set
    var hub: HubClient? = null
        private set

    fun startAsync() {
        if (_ready.value) return
        val unlocked = runCatching {
            app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(true)
        if (!unlocked) {
            Log.i(TAG, "skip startAsync — user locked (direct boot)")
            return
        }
        scope.launch(Dispatchers.IO) {
            startMutex.withLock {
                if (_ready.value || shortcuts != null) {
                    Log.i(TAG, "startAsync skipped — already started")
                    return@withLock
                }
                try {
                    startInternal()
                } catch (t: Throwable) {
                    app.log.e(TAG, "runtime start failed", t)
                    LogRingBuffer.append("START FAILED: ${t.message}")
                }
            }
        }
    }

    private suspend fun startInternal() {
        val fp = OaaApp.deviceFingerprint()
        registry.all().forEach { it.warm(app) }
        val overrideId = integrationOverride()
        val matched = if (overrideId != null) {
            registry.all().firstOrNull { it.id == overrideId }
        } else {
            registry.match(fp)
        } ?: error("No vehicle integration available for device=${fp.device} hw=${fp.hardware}")

        integration = matched
        app.log.i(TAG, "Matched integration ${matched.id} for device=${fp.device} hw=${fp.hardware}")

        val sess = matched.connect(app)
        session = sess
        val variant = sess.variant.value
        capabilities = matched.capabilities(variant)
        app.log.i(TAG, "Variant=${variant.id} caps=$capabilities")

        telemetry = TelemetryRepository(sess)
        installer = ApkInstaller(app)
        dvr = DvrController(app, sess)
        probe = CatalogProbe(app, sess)
        obd2 = Obd2Probe(sess)
        androidSettings = AndroidSettingsController(app, sess.androidVolumeGroups()).also {
            // Best-effort (shell/privileged); opens nothing if Secure write is denied.
            it.tryEnableMediaListener()
            it.start(scope)
        }
        locationTracker = LocationTrackerController(
            app,
            OaaPrefs.ui(app),
        )
        history = EntityHistoryRecorder(app, sess).also { it.start() }

        val applyControl: suspend (String, String) -> Result<Unit> = { id, value ->
            when {
                id in (androidSettings?.writableIds ?: emptySet()) -> {
                    val ok = androidSettings?.apply(id, value) == true
                    if (ok) Result.success(Unit)
                    else Result.failure(IllegalStateException("android apply failed: $id"))
                }
                else -> ControlCatalog.set(sess, id, value, app)
            }
        }
        val readControl: suspend (String) -> String? = { id ->
            locationTracker?.read(id)
                ?: androidSettings?.read(id)
                ?: ControlCatalog.currentValue(sess, id)
        }

        if (Capability.WRITE_SETTINGS in capabilities) {
            memory = SettingsMemoryController(
                context = app,
                session = sess,
                hasWrite = true,
                applyControl = applyControl,
                readControl = readControl,
                extraPinIds = androidSettings?.allIds.orEmpty(),
            ).also { it.start() }
        }

        val pluginHost = object : PluginHost {
            override val context: Context = app
            override val session: VehicleSession = sess
            override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        for (plugin in plugins.all()) {
            runCatching { plugin.start(pluginHost) }
                .onFailure { t -> app.log.e(TAG, "plugin ${plugin.id} start failed", t) }
        }

        val actionHandlers = plugins.all().mapNotNull { p ->
            p.actionHandler?.let { p.id to it }
        }.toMap()
        val triggerSources = plugins.all().mapNotNull { it.triggerSource }

        shortcuts = ShortcutsController(
            context = app,
            session = sess,
            setControl = applyControl,
            mainActivityClass = MainActivity::class.java,
            quickEntry = matched.createQuickEntry(),
            actionHandlers = actionHandlers,
            triggerSources = triggerSources,
            readEntity = readControl,
            readGear = {
                sess.telemetry().first().gear
            },
        ).also { it.start() }
        // Session Boot may already have been consumed by memory — deliver explicitly.
        shortcuts?.onBoot()
        // Manifest wake (FlymeWakeReceiver / BootReceiver) may have arrived while we
        // were still matching — flush now that shortcuts exist.
        flushPendingScreen()
        // If DVR mode is enabled and we are already awake (no queued edge), start now.
        scope.launch(Dispatchers.IO) {
            if (dvr?.mode() == DvrController.MODE_DVR) {
                dvr?.onVehicleWake("runtime_ready")
            }
        }

        val hubClient = HubClient(
            context = app,
            session = sess,
            prefs = OaaPrefs.ui(app),
            debug = debug,
            installer = installer!!,
            dvr = dvr,
        )
        hub = hubClient

        web = OaaWebServer(
            context = app,
            session = sess,
            debug = debug,
            memory = memory,
            installer = installer!!,
            dvr = dvr!!,
            probe = probe!!,
            obd2 = obd2,
            capabilities = capabilities.map { it.name }.toSet(),
            variantId = variant.id,
            androidSettings = androidSettings,
            locationTracker = locationTracker,
            history = history,
            shortcuts = shortcuts,
            plugins = plugins,
            integrationIds = registry.all().map { it.id },
            getIntegrationOverride = { integrationOverride() },
            setIntegrationOverride = { setIntegrationOverride(it) },
            hub = hubClient,
        ).also { it.start() }
        hubClient.start()

        // Warm probe in background (non-blocking for UI)
        scope.launch(Dispatchers.IO) {
            runCatching { probe?.run(force = false) }
            runCatching { obd2?.run(force = false) }
        }

        ensureService()
        _ready.value = true
        LogRingBuffer.append("Runtime ready integration=${matched.id} variant=${variant.id}")
    }

    fun notifyScreenOn(source: String = "runtime") {
        Log.i(TAG, "notifyScreenOn shortcuts=${shortcuts != null} source=$source")
        val s = shortcuts
        if (s != null) {
            s.onScreenOn(source)
            scope.launch(Dispatchers.IO) { memory?.reapplyOnWake() }
            scope.launch(Dispatchers.IO) { dvr?.onVehicleWake(source) }
        } else {
            queuedScreen.set(QueuedScreen("on", source))
            PendingWake.persist(app, "on", source)
            Log.i(TAG, "notifyScreenOn queued (shortcuts not ready)")
        }
    }

    fun notifyScreenOff(source: String = "runtime") {
        Log.i(TAG, "notifyScreenOff shortcuts=${shortcuts != null} source=$source")
        val s = shortcuts
        if (s != null) {
            s.onScreenOff(source)
            scope.launch(Dispatchers.IO) { dvr?.onVehicleSleep(source) }
        } else {
            queuedScreen.set(QueuedScreen("off", source))
            PendingWake.persist(app, "off", source)
            Log.i(TAG, "notifyScreenOff queued (shortcuts not ready)")
        }
    }

    /**
     * Deliver wake/sleep that arrived via manifest receivers before shortcuts
     * were ready (STR / cold start). Uses in-memory queue first, then [PendingWake].
     */
    private fun flushPendingScreen() {
        val mem = queuedScreen.getAndSet(null)
        if (mem != null) {
            Log.i(TAG, "flush queued screen kind=${mem.kind} source=${mem.source}")
            deliverScreen(mem.kind, mem.source)
        }
        val edge = PendingWake.consume(app) ?: return
        if (mem != null && mem.kind == edge.kind) return
        Log.i(TAG, "flush prefs screen kind=${edge.kind} source=${edge.source}")
        deliverScreen(edge.kind, edge.source)
    }

    private fun deliverScreen(kind: String, source: String) {
        when (kind) {
            "on" -> {
                shortcuts?.onScreenOn(source)
                scope.launch(Dispatchers.IO) { memory?.reapplyOnWake() }
                scope.launch(Dispatchers.IO) { dvr?.onVehicleWake(source) }
            }
            "off" -> {
                shortcuts?.onScreenOff(source)
                scope.launch(Dispatchers.IO) { dvr?.onVehicleSleep(source) }
            }
        }
    }

    /** Platform vendor wake/sleep broadcast actions (empty until an integration is matched). */
    fun wakeSignals(): WakeSignals = integration?.wakeSignals() ?: WakeSignals()

    fun integrationOverride(): String? =
        app.getSharedPreferences("oaa_runtime", Context.MODE_PRIVATE)
            .getString("integration_override", null)

    fun setIntegrationOverride(id: String?) {
        val prefs = app.getSharedPreferences("oaa_runtime", Context.MODE_PRIVATE).edit()
        if (id.isNullOrBlank()) {
            prefs.remove("integration_override")
        } else {
            prefs.putString("integration_override", id)
        }
        prefs.apply()
    }

    private fun ensureService() {
        try {
            PendingWake.startAssistantService(app)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureService failed: ${t.message}")
        }
    }

    private data class QueuedScreen(val kind: String, val source: String)

    private val queuedScreen = AtomicReference<QueuedScreen?>(null)

    companion object {
        private const val TAG = "OaaRuntime"
    }
}
