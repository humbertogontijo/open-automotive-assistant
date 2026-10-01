package cc.opencar.assistant.feature.web

import android.content.Context
import android.content.SharedPreferences
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.api.plugin.PluginRegistry
import cc.opencar.assistant.feature.debug.CatalogProbe
import cc.opencar.assistant.feature.debug.Obd2Probe
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.history.EntityHistoryRecorder
import cc.opencar.assistant.feature.memory.SettingsMemoryController
import cc.opencar.assistant.feature.shortcuts.ShortcutsController
import cc.opencar.assistant.feature.web.webrtc.CarWebRtc

internal data class OaaWebDeps(
    val context: Context,
    val session: VehicleSession,
    val debug: ContributorDebugState,
    val memory: SettingsMemoryController?,
    val dvr: DvrController,
    val probe: CatalogProbe,
    val obd2: Obd2Probe? = null,
    val capabilities: Set<String>,
    val variantId: String,
    val port: Int,
    val prefs: SharedPreferences,
    val androidSettings: AndroidSettingsController? = null,
    val locationTracker: LocationTrackerController? = null,
    val history: EntityHistoryRecorder? = null,
    val shortcuts: ShortcutsController? = null,
    val plugins: PluginRegistry? = null,
    val sounds: SoundsController? = null,
    /** Known ServiceLoader integration ids for Lab override dropdown. */
    val integrationIds: List<String> = emptyList(),
    val getIntegrationOverride: () -> String? = { null },
    val setIntegrationOverride: (String?) -> Unit = {},
    val hub: HubClient? = null,
    val auth: CarAuth,
) {
    /** Shared with the hub link so hub and local viewers count against the same cameras. */
    val webrtc by lazy { hub?.webrtc ?: CarWebRtc(dvr) }
    val entityVisibility by lazy { EntityVisibilityStore(prefs) }

    fun pluginStatusMaps(): List<Map<String, Any?>> =
        plugins?.all()?.map { it.status() } ?: emptyList()

    fun pluginDetailMaps(): List<Map<String, Any?>> =
        plugins?.all()?.map { plugin ->
            val schema = plugin.configSchema()
            mapOf(
                "id" to plugin.id,
                "displayName" to plugin.displayName,
                "status" to plugin.status(),
                "config" to plugin.configSnapshot(),
                "schema" to schema?.let { s ->
                    mapOf(
                        "fields" to s.fields.map { f ->
                            mapOf(
                                "key" to f.key,
                                "type" to f.type,
                                "label" to f.label,
                                "optional" to f.optional,
                                "placeholder" to f.placeholder,
                                "description" to f.description,
                            )
                        },
                    )
                },
            )
        } ?: emptyList()
}
