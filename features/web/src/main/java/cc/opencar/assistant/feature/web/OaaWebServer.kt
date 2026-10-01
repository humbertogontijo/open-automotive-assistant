package cc.opencar.assistant.feature.web

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.api.plugin.PluginRegistry
import cc.opencar.assistant.feature.debug.CatalogProbe
import cc.opencar.assistant.feature.debug.Obd2Probe
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.history.EntityHistoryRecorder
import cc.opencar.assistant.feature.memory.SettingsMemoryController
import cc.opencar.assistant.feature.shortcuts.ShortcutsController
import cc.opencar.assistant.protocol.OaaPorts
import cc.opencar.assistant.support.OaaPrefs
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.util.concurrent.atomic.AtomicReference

class OaaWebServer(
    private val context: Context,
    private val session: VehicleSession,
    private val debug: ContributorDebugState,
    private val memory: SettingsMemoryController?,
    private val dvr: DvrController,
    private val probe: CatalogProbe,
    private val obd2: Obd2Probe? = null,
    private val capabilities: Set<String>,
    private val variantId: String,
    private val port: Int = OaaPorts.HUMAN_DEFAULT,
    private val androidSettings: AndroidSettingsController? = null,
    private val locationTracker: LocationTrackerController? = null,
    private val history: EntityHistoryRecorder? = null,
    private val shortcuts: ShortcutsController? = null,
    private val plugins: PluginRegistry? = null,
    private val sounds: SoundsController? = null,
    private val integrationIds: List<String> = emptyList(),
    private val getIntegrationOverride: () -> String? = { null },
    private val setIntegrationOverride: (String?) -> Unit = {},
    private val hub: HubClient? = null,
    private val auth: CarAuth,
) {
    private val engine = AtomicReference<ApplicationEngine?>(null)

    fun start() {
        if (engine.get() != null) return
        val prefs = OaaPrefs.ui(context)
        val deps = OaaWebDeps(
            context = context,
            session = session,
            debug = debug,
            memory = memory,
            dvr = dvr,
            probe = probe,
            obd2 = obd2,
            capabilities = capabilities,
            variantId = variantId,
            port = port,
            prefs = prefs,
            androidSettings = androidSettings,
            locationTracker = locationTracker,
            history = history,
            shortcuts = shortcuts,
            plugins = plugins,
            sounds = sounds ?: SoundsController(context),
            integrationIds = integrationIds,
            getIntegrationOverride = getIntegrationOverride,
            setIntegrationOverride = setIntegrationOverride,
            hub = hub,
            auth = auth,
        )
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { gson() }
            install(WebSockets)
            install(PartialContent)
            installCarAuth(auth)
            routing {
                registerStaticRoutes(deps)
                registerAuthRoutes(deps)
                registerCoreRoutes(deps)
                registerEventRoutes(deps)
                registerDvrRoutes(deps)
                registerWebRtcRoutes(deps)
                registerSoundRoutes(deps)
                registerDebugRoutes(deps)
                registerShortcutRoutes(deps)
                registerPluginRoutes(deps)
                registerHubRoutes(deps)
                registerSpaFallbackRoutes(deps)
            }
        }
        server.start(wait = false)
        engine.set(server)
        Log.i(TAG, "Open Automotive Assistant web listening on :$port")
        LogRingBuffer.append("Web server started on :$port")
    }

    fun stop() {
        engine.getAndSet(null)?.stop(1_000, 2_000)
    }

    companion object {
        private const val TAG = "OaaWeb"
    }
}
