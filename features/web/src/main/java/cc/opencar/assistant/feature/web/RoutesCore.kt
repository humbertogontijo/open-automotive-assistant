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
import io.ktor.server.request.receiveParameters
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
        val host = call.request.local.remoteHost
        val viaHub = call.request.header(OaaHeaders.VIA_HUB) != null
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
                "remote" to (viaHub || (host != "127.0.0.1" && host != "localhost" && host != "::1")),
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
        val params = call.receiveParameters()
        val raw = params["locale"] ?: call.request.queryParameters["locale"]
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
    get("/api/controls") {
        val hidden = deps.entityVisibility.hiddenIds()
        val includeHidden = call.request.queryParameters["includeHidden"] == "1"
        val all = CatalogResponseCache.controls {
            val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
            (ControlCatalog.snapshot(session, context, memory) + virtual).map { row ->
                val id = row["id"] as? String
                EntityContract.enrich(row) + ("hidden" to (id != null && id in hidden))
            }
        }
        call.respond(if (includeHidden) all else all.filter { it["hidden"] != true })
    }
    get("/api/entities") {
        val hidden = deps.entityVisibility.hiddenIds()
        val includeHidden = call.request.queryParameters["includeHidden"] == "1"
        val all = CatalogResponseCache.entities {
            val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
            (ControlCatalog.entities(session, context, memory, deps.androidSettings, deps.locationTracker, deps.dvr) + virtual).map { row ->
                val id = row["id"] as? String
                EntityContract.enrich(row) + ("hidden" to (id != null && id in hidden))
            }
        }
        call.respond(if (includeHidden) all else all.filter { it["hidden"] != true })
    }
    get("/api/entities/hidden") {
        val hidden = deps.entityVisibility.hiddenIds()
        val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
        val all = ControlCatalog.entities(session, context, memory, deps.androidSettings, deps.locationTracker, deps.dvr) + virtual
        call.respond(
            mapOf(
                "ids" to hidden.toList().sorted(),
                "entities" to all.filter { (it["id"] as? String) in hidden }.map {
                    EntityContract.enrich(it) + ("hidden" to true)
                },
            ),
        )
    }
    get("/api/entities/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false))
        val hidden = deps.entityVisibility.hiddenIds()
        val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
        val row = (ControlCatalog.entities(session, context, memory, deps.androidSettings, deps.locationTracker, deps.dvr) + virtual)
            .firstOrNull { it["id"] == id }
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "not found"))
        call.respond(EntityContract.enrich(row) + ("hidden" to (id in hidden)))
    }
    post("/api/entities/{id}/visibility") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false))
        val params = call.receiveParameters()
        val raw = params["hidden"] ?: call.request.queryParameters["hidden"]
        val hide = when (raw?.lowercase()) {
            "1", "true", "yes", "hide" -> true
            "0", "false", "no", "unhide", "show" -> false
            else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "hidden required"))
        }
        deps.entityVisibility.setHidden(id, hide)
        CatalogResponseCache.invalidate()
        WebEventHub.emitCatalog("visibility")
        call.respond(mapOf("ok" to true, "id" to id, "hidden" to hide, "ids" to deps.entityVisibility.hiddenIds().toList().sorted()))
    }
    get("/api/setup") {
        call.respond(SetupStatus.snapshot(context, session, prefs))
    }
    post("/api/setup") {
        val params = call.receiveParameters()
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
    get("/api/android") {
        val android = deps.androidSettings
        if (android == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "android settings unavailable"))
            return@get
        }
        call.respond(android.status())
    }
    post("/api/android/{id}") {
        val android = deps.androidSettings
        if (android == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "android settings unavailable"))
            return@post
        }
        val id = call.parameters["id"] ?: return@post
        val params = call.receiveParameters()
        val value = params["value"] ?: call.request.queryParameters["value"]
        if (value == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "value required"))
            return@post
        }
        val result = when (id) {
            AndroidSettingsController.ID_WIFI,
            AndroidSettingsController.ID_BT,
            -> {
                val on = value == "1" || value.equals("true", true) || value.equals("on", true)
                if (id == AndroidSettingsController.ID_WIFI) {
                    android.setWifi(on)
                } else {
                    android.setBluetooth(on)
                }
            }
            AndroidSettingsController.ID_BRIGHTNESS -> {
                val n = value.toIntOrNull()
                if (n == null) mapOf("ok" to false, "error" to "integer value required")
                else android.setBrightness(n)
            }
            AndroidSettingsController.ID_MEDIA_PLAYER -> {
                val ok = android.apply(id, value)
                mapOf("ok" to ok, "error" to if (ok) null else "apply failed", "status" to android.status())
            }
            else -> mapOf("ok" to false, "error" to "unknown id")
        }
        call.respond(result)
    }
    post("/api/android/media-listener/enable") {
        val android = deps.androidSettings
        if (android == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "android settings unavailable"))
            return@post
        }
        val enabled = android.tryEnableMediaListener()
        if (!enabled) {
            android.openMediaListenerSettings()
        }
        call.respond(
            mapOf(
                "ok" to enabled,
                "mediaListenerEnabled" to (android.status()["mediaListenerEnabled"] == true),
                "openedSettings" to !enabled,
            ),
        )
    }
    post("/api/android/write-settings/open") {
        val android = deps.androidSettings
        if (android == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "android settings unavailable"))
            return@post
        }
        call.respond(android.openWriteSettings())
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
        val virtual = deps.shortcuts?.virtualEntityMaps().orEmpty()
        val entityMeta = (ControlCatalog.entities(session, context, memory, deps.androidSettings, deps.locationTracker, deps.dvr) + virtual)
            .firstOrNull { it["id"] == entityId }
            ?.let { EntityContract.enrich(it) }
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
        val params = call.receiveParameters()
        val value = params["value"] ?: call.request.queryParameters["value"]
        if (value == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "value required"))
            return@post
        }
        val android = deps.androidSettings
        if (android != null && id in android.writableIds) {
            val ok = android.apply(id, value)
            if (ok) {
                CatalogResponseCache.invalidate()
                WebEventHub.emitCatalog("android_write")
                deps.shortcuts?.onControlWritten(id)
            }
            call.respond(mapOf("ok" to ok, "error" to if (ok) null else "apply failed"))
            return@post
        }
        val virtual = deps.shortcuts?.handleVirtualWrite(id, value)
        if (virtual != null) {
            CatalogResponseCache.invalidate()
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
            CatalogResponseCache.invalidate()
            WebEventHub.emitCatalog("control_write")
            // Composites refresh via catalog only — never patch product value with
            // structured write tokens (temperature:22) or attr-raw.
            val product = EntityRegistry.resolve(id)
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
        val params = call.receiveParameters()
        val enabledRaw = params["enabled"] ?: call.request.queryParameters["enabled"]
        val enabled = when (enabledRaw) {
            null -> null
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> null
        }
        val value = params["value"] ?: call.request.queryParameters["value"]
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
        val params = call.receiveParameters()
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
            CatalogResponseCache.invalidate()
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
        CatalogResponseCache.invalidate()
        WebEventHub.emitCatalog("home")
        call.respond(result)
    }
    get("/api/location/home") {
        val loc = deps.locationTracker
        if (loc == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "location unavailable"))
            return@get
        }
        call.respond(mapOf("ok" to true) + loc.homeSnapshot())
    }
    post("/api/memory/capture") {
        if (memory == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "memory unavailable"))
            return@post
        }
        val captured = memory.captureFromVehicle()
        call.respond(mapOf("ok" to true, "prefs" to captured))
    }
    post("/api/memory/reapply") {
        memory?.reapply()
        call.respond(mapOf("ok" to true))
    }
    get("/api/adb") {
        call.respond(WirelessAdbController(context, debug).status())
    }
    post("/api/adb") {
        val params = call.receiveParameters()
        val enabled = when (params["enabled"] ?: call.request.queryParameters["enabled"]) {
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> null
        }
        if (enabled == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "enabled=1|0 required"))
            return@post
        }
        val adbPort = (params["port"] ?: call.request.queryParameters["port"])?.toIntOrNull() ?: 5566
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
