package cc.opencar.assistant.feature.web

import cc.opencar.assistant.api.EntityContract
import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import cc.opencar.assistant.support.I18nBundle
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first

internal fun Routing.registerCoreRoutes(deps: OaaWebDeps) {
    val context = deps.context
    val session = deps.session
    val debug = deps.debug
    val memory = deps.memory
    val capabilities = deps.capabilities
    val variantId = deps.variantId
    val port = deps.port
    val prefs = deps.prefs

    get(OaaPaths.STATUS) {
        val snap = session.telemetry().first()
        val i18n = I18nBundle.load(context, session.integrationId)
        call.respond(
            mapOf(
                "role" to OaaRoles.LOCAL,
                "version" to OaaBuild.VERSION,
                "nodeId" to deps.hub?.nodeId,
                "integration" to session.integrationId,
                "variant" to variantId,
                "capabilities" to capabilities,
                "locale" to i18n.locale,
                "locales" to I18nBundle.SUPPORTED,
                "remote" to (call.caller?.remote ?: true),
                "telemetry" to telemetryPayload(snap),
                "setup" to SetupStatus.snapshot(context, session, prefs),
                "plugins" to deps.pluginDetailMaps(),
                "dvr" to deps.dvr.status(),
                "storage" to mapOf(
                    "volumes" to deps.dvr.volumeStats(),
                ),
                "webPort" to port,
                "theme" to (prefs.getString("theme", "dark") ?: "dark"),
                "adb" to WirelessAdbController(context, debug).status(),
                "android" to (deps.androidSettings?.status() ?: emptyMap<String, Any?>()),
                "hub" to deps.hub?.status(),
            ),
        )
    }
    get(OaaPaths.I18N) {
        val i18n = I18nBundle.load(context, session.integrationId)
        call.respond(
            mapOf(
                "locale" to i18n.locale,
                "locales" to I18nBundle.SUPPORTED,
                "integration" to session.integrationId,
                "strings" to i18n.dictionary(),
                "valueMaps" to i18n.valueMapsSnapshot(),
            ),
        )
    }
    post("/api/locale") {
        val params = call.params()
        val raw = params["locale"]
        val loc = I18nBundle.normalize(raw)
        prefs.edit().putString(I18nBundle.PREF_LOCALE, loc).apply()
        I18nBundle.invalidateCache()
        val i18n = I18nBundle.load(context, session.integrationId, loc)
        call.respond(
            mapOf(
                "ok" to true,
                "locale" to i18n.locale,
                "strings" to i18n.dictionary(),
                "valueMaps" to i18n.valueMapsSnapshot(),
            ),
        )
    }
    /** Enriched rows with the `hidden` flag, built once per cache window for every catalog route. */
    suspend fun catalog(): ControlCatalog.Built = CatalogResponseCache.get {
        val hidden = deps.entityVisibility.hiddenIds()
        val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
        fun rows(list: List<Map<String, Any?>>) = (list + virtual).map { row ->
            val id = row["id"] as? String
            EntityContract.enrich(row) + ("hidden" to (id != null && id in hidden))
        }
        val built = ControlCatalog.build(session, context, memory, deps.androidSettings, deps.locationTracker, deps.dvr)
        ControlCatalog.Built(rows(built.controls), rows(built.entities))
    }

    get("/api/controls") {
        val all = catalog().controls
        val includeHidden = call.request.queryParameters["includeHidden"] == "1"
        call.respond(if (includeHidden) all else all.filter { it["hidden"] != true })
    }
    get("/api/entities") {
        val all = catalog().entities
        val includeHidden = call.request.queryParameters["includeHidden"] == "1"
        call.respond(if (includeHidden) all else all.filter { it["hidden"] != true })
    }
    get("/api/entities/hidden") {
        val hidden = catalog().entities.filter { it["hidden"] == true }
        call.respond(
            mapOf(
                "ids" to deps.entityVisibility.hiddenIds().toList().sorted(),
                "entities" to hidden,
            ),
        )
    }
    get("/api/entities/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false))
        val row = catalog().entities.firstOrNull { it["id"] == id }
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "not found"))
        call.respond(row)
    }
    post("/api/entities/{id}/visibility") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false))
        val params = call.params()
        val raw = params["hidden"]
        val hide = when (raw?.lowercase()) {
            "1", "true", "yes", "hide" -> true
            "0", "false", "no", "unhide", "show" -> false
            else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "hidden required"))
        }
        deps.entityVisibility.setHidden(id, hide)
        WebEventHub.emitCatalog("visibility")
        call.respond(mapOf("ok" to true, "id" to id, "hidden" to hide, "ids" to deps.entityVisibility.hiddenIds().toList().sorted()))
    }
    get("/api/setup") {
        call.respond(SetupStatus.snapshot(context, session, prefs))
    }
    post("/api/setup") {
        val params = call.params()
        val edit = prefs.edit()
        if (params["dismiss"] == "1" || params["dismiss"] == "true") {
            edit.putBoolean("setup_dismissed", true)
        }
        if (params["reset"] == "1" || params["reset"] == "true") {
            edit.remove("setup_dismissed")
        }
        edit.apply()
        call.respond(SetupStatus.snapshot(context, session, prefs))
    }
    post("/api/setup/actions/request-runtime") {
        SetupActionBus.requestRuntimePermissions()
        call.respond(mapOf("ok" to true, "message" to "Solicitando permissões no HU"))
    }
    post("/api/system/open-android-settings") {
        SetupActionBus.requestOpenAndroidSettings()
        call.respond(mapOf("ok" to true, "message" to "Opening Android Settings"))
    }
    get("/api/history") {
        val history = deps.history
        if (history == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "history unavailable"))
            return@get
        }
        call.respond(mapOf("entities" to history.entitiesTracked()))
    }
    get("/api/history/{entityId}") {
        val history = deps.history
        if (history == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "history unavailable"))
            return@get
        }
        val entityId = call.parameters["entityId"] ?: return@get
        val end = call.request.queryParameters["end"]?.toLongOrNull()
            ?: System.currentTimeMillis()
        val start = call.request.queryParameters["start"]?.toLongOrNull()
            ?: (end - 24L * 60 * 60 * 1000)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 2000
        val entityMeta = catalog().entities.firstOrNull { it["id"] == entityId }
        call.respond(
            mapOf(
                "entityId" to entityId,
                "start" to start,
                "end" to end,
                "entity" to entityMeta,
                "points" to history.query(entityId, start, end, limit),
            ),
        )
    }
    post("/api/controls/{id}") {
        val id = call.parameters["id"] ?: return@post
        val params = call.params()
        val value = params["value"]
        if (value == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "value required"))
            return@post
        }
        val android = deps.androidSettings
        if (android != null && id in android.writableIds) {
            val ok = android.apply(id, value)
            if (ok) {
                WebEventHub.emitCatalog("android_write")
                deps.shortcuts?.onControlWritten(id)
            }
            call.respond(mapOf("ok" to ok, "error" to if (ok) null else "apply failed"))
            return@post
        }
        val virtual = deps.shortcuts?.handleVirtualWrite(id, value)
        if (virtual != null) {
            WebEventHub.emitCatalog("virtual_write")
            call.respond(
                mapOf(
                    "ok" to (virtual["ok"] == true),
                    "error" to virtual["error"],
                ),
            )
            return@post
        }
        val result = ControlCatalog.set(session, id, value, context)
        if (result.isSuccess) {
            WebEventHub.emitCatalog("control_write")
            // Composites refresh via catalog only — never patch product value with
            // structured write tokens (temperature:22) or attr-raw.
            val product = EntityRegistry.byId(id)
            if (product == null || !product.isComposite) {
                WebEventHub.emitEntity(id, value, status = "ok")
            }
            deps.shortcuts?.onControlWritten(id)
        }
        call.respond(
            mapOf(
                "ok" to result.isSuccess,
                "error" to result.exceptionOrNull()?.message,
            ),
        )
    }
    post("/api/controls/{id}/persist") {
        val id = call.parameters["id"] ?: return@post
        if (memory == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "memory unavailable"))
            return@post
        }
        val params = call.params()
        val enabled = parseBool(params["enabled"])
        val value = params["value"]
        call.respond(memory.setPersist(id, enabled, value))
    }
    get("/api/prefs") {
        val loc = deps.locationTracker
        val home = loc?.homeSnapshot() ?: emptyMap()
        call.respond(
            mapOf(
                "theme" to (prefs.getString("theme", "dark") ?: "dark"),
                "locale" to I18nBundle.normalize(prefs.getString(I18nBundle.PREF_LOCALE, null)),
                "locales" to I18nBundle.SUPPORTED,
                "units" to normalizeUnits(prefs.getString("units", null)),
                "homeLat" to home["homeLat"],
                "homeLon" to home["homeLon"],
                "homeRadiusM" to home["homeRadiusM"],
            ),
        )
    }
    post("/api/prefs") {
        val params = call.params()
        val edit = prefs.edit()
        val theme = params["theme"]
        if (theme != null && theme in setOf("dark", "light", "contrast")) {
            edit.putString("theme", theme)
        }
        val locale = params["locale"]
        if (locale != null) {
            edit.putString(I18nBundle.PREF_LOCALE, I18nBundle.normalize(locale))
        }
        val units = params["units"]
        if (units != null) {
            edit.putString("units", unitsToStorage(units))
        }
        val loc = deps.locationTracker
        val homeLat = params["homeLat"]
        val homeLon = params["homeLon"]
        val homeRadius = params["homeRadiusM"]
        val clearHome = params["clearHome"] == "1" || params["clearHome"] == "true"
        var homeChanged = false
        if (clearHome) {
            if (loc != null) {
                loc.clearHome()
            } else {
                edit.remove(LocationTrackerController.PREF_HOME_LAT)
                edit.remove(LocationTrackerController.PREF_HOME_LON)
            }
            homeChanged = true
        } else if (homeLat != null && homeLon != null) {
            val lat = homeLat.toDoubleOrNull()
            val lon = homeLon.toDoubleOrNull()
            if (lat != null && lon != null) {
                if (loc != null) {
                    loc.setHome(lat, lon)
                } else {
                    edit.putString(LocationTrackerController.PREF_HOME_LAT, lat.toString())
                    edit.putString(LocationTrackerController.PREF_HOME_LON, lon.toString())
                }
                homeChanged = true
            }
        }
        if (homeRadius != null) {
            val r = homeRadius.toFloatOrNull()
            if (r != null) {
                if (loc != null) {
                    loc.setHomeRadius(r)
                } else {
                    edit.putFloat(
                        LocationTrackerController.PREF_HOME_RADIUS_M,
                        r.coerceIn(
                            LocationTrackerController.MIN_RADIUS_M,
                            LocationTrackerController.MAX_RADIUS_M,
                        ),
                    )
                }
                homeChanged = true
            }
        }
        edit.apply()
        if (homeChanged) {
            WebEventHub.emitCatalog("home")
        }
        val home = loc?.homeSnapshot() ?: emptyMap()
        call.respond(
            mapOf(
                "ok" to true,
                "theme" to (prefs.getString("theme", "dark") ?: "dark"),
                "locale" to I18nBundle.normalize(prefs.getString(I18nBundle.PREF_LOCALE, null)),
                "units" to normalizeUnits(prefs.getString("units", null)),
                "homeLat" to home["homeLat"],
                "homeLon" to home["homeLon"],
                "homeRadiusM" to home["homeRadiusM"],
            ),
        )
    }
    post("/api/location/home/here") {
        val loc = deps.locationTracker
        if (loc == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "location unavailable"))
            return@post
        }
        val result = loc.setHomeHere()
        WebEventHub.emitCatalog("home")
        call.respond(result)
    }
    get("/api/adb") {
        call.respond(WirelessAdbController(context, debug).status())
    }
    post("/api/adb") {
        val params = call.params()
        val enabled = parseBool(params["enabled"])
        if (enabled == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "enabled=1|0 required"))
            return@post
        }
        val adbPort = (params["port"])?.toIntOrNull() ?: 5566
        call.respond(
            WirelessAdbController(context, debug).setEnabled(enabled, adbPort),
        )
    }
}

private fun normalizeUnits(raw: String?): Any {
    if (raw.isNullOrBlank()) return defaultUnitPrefs()
    val trimmed = raw.trim()
    if (trimmed.equals("imperial", ignoreCase = true)) {
        return mapOf(
            "temperature" to "fahrenheit",
            "distance" to "mi",
            "speed" to "mph",
            "fuel_economy" to "mpg",
            "energy_economy" to "kwh_100km",
        )
    }
    if (trimmed.equals("metric", ignoreCase = true)) {
        return defaultUnitPrefs()
    }
    if (trimmed.startsWith("{")) {
        return try {
            org.json.JSONObject(trimmed).let { json ->
                val out = defaultUnitPrefs().toMutableMap()
                for (key in listOf("temperature", "distance", "speed", "fuel_economy", "energy_economy")) {
                    if (json.has(key)) out[key] = json.getString(key)
                }
                out
            }
        } catch (_: Exception) {
            defaultUnitPrefs()
        }
    }
    return defaultUnitPrefs()
}

private fun defaultUnitPrefs(): Map<String, String> = mapOf(
    "temperature" to "celsius",
    "distance" to "km",
    "speed" to "km_h",
    "fuel_economy" to "l_100km",
    "energy_economy" to "kwh_100km",
)

private fun unitsToStorage(unitsParam: String): String {
    val normalized = normalizeUnits(unitsParam)
    return when (normalized) {
        is Map<*, *> -> org.json.JSONObject(normalized).toString()
        else -> org.json.JSONObject(defaultUnitPrefs()).toString()
    }
}
