package cc.opencar.assistant.feature.web

import cc.opencar.assistant.feature.debug.CatalogProbe
import cc.opencar.assistant.feature.debug.ContributorDebugState
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.debug.Obd2Probe
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal fun Routing.registerDebugRoutes(deps: OaaWebDeps) {
    val session = deps.session
    val debug = deps.debug
    val probe = deps.probe
    val obd2 = deps.obd2
    val capabilities = deps.capabilities
    val variantId = deps.variantId

    get("/debug") {
        call.respondText(htmlDebug(debug), ContentType.Text.Html)
    }
    get("/debug/probe") {
        if (debug.contributorMode && !call.requireToken(debug)) return@get
        val force = call.request.queryParameters["force"] == "1"
        val report = withContext(Dispatchers.IO) { probe.run(force) }
        call.respond(
            mapOf(
                "summary" to report.summary(),
                "source" to "VHAL catalog (CarPropertyManager / gRPC)",
                "results" to report.results.map {
                    mapOf(
                        "name" to it.name,
                        "key" to it.key,
                        "id" to it.nativeIdHex,
                        "family" to it.family,
                        "writable" to it.writable,
                        "status" to it.status,
                        "permission" to it.permission,
                        "areaId" to it.areaId,
                        "value" to it.value,
                        "message" to it.message,
                        "entity" to it.entity,
                    )
                },
            ),
        )
    }
    get("/debug/obd2") {
        if (debug.contributorMode && !call.requireToken(debug)) return@get
        val force = call.request.queryParameters["force"] == "1"
        val o = obd2
        if (o == null) {
            call.respond(
                mapOf(
                    "summary" to mapOf("available" to false, "tip" to "OBD2 probe not wired"),
                    "source" to "OBD2_LIVE_FRAME / OBD2_FREEZE_FRAME (VHAL)",
                    "results" to emptyList<Any>(),
                ),
            )
            return@get
        }
        val report = withContext(Dispatchers.IO) { o.run(force) }
        call.respond(
            mapOf(
                "summary" to report.summary(),
                "source" to "OBD2_LIVE_FRAME / OBD2_FREEZE_FRAME (VHAL)",
                "results" to report.results.map {
                    mapOf(
                        "name" to it.name,
                        "key" to it.key,
                        "id" to it.nativeIdHex,
                        "family" to it.family,
                        "status" to it.status,
                        "permission" to it.permission,
                        "value" to it.value,
                        "message" to it.message,
                        "byteLength" to it.byteLength,
                    )
                },
            ),
        )
    }
    get("/debug/probe/full") {
        if (!call.requireToken(debug)) return@get
        val report = withContext(Dispatchers.IO) { probe.run(false) }
        call.respondText(report.toJsonArray().toString(), ContentType.Application.Json)
    }
    get("/debug/logs") {
        if (!call.requireToken(debug)) return@get
        call.respondText(LogRingBuffer.snapshot().joinToString("\n"))
    }
    get("/debug/integration") {
        call.respond(
            mapOf(
                "id" to session.integrationId,
                "variant" to variantId,
                "skuId" to session.variant.value.skuId,
                "profileId" to session.variant.value.id,
                "capabilities" to capabilities,
                "catalogSize" to session.catalog().size,
                "cameras" to session.cameras().map {
                    mapOf(
                        "id" to it.cameraId,
                        "entityId" to it.id,
                        "label" to it.label,
                        "role" to it.role,
                    )
                },
                "probe" to (probe.cached()?.summary()),
            ),
        )
    }
    get("/debug/props") {
        if (!call.requireToken(debug)) return@get
        val q = call.request.queryParameters["q"].orEmpty()
        val family = call.request.queryParameters["family"].orEmpty()
        val entries = session.catalog().filter {
            (q.isBlank() || it.name.contains(q, true)) &&
                (family.isBlank() || CatalogProbe.familyOf(it.name).equals(family, true))
        }.take(150)
        call.respond(
            entries.map {
                val outcome = session.diagnose(it.property)
                mapOf(
                    "name" to it.name,
                    "key" to it.property.key,
                    "id" to it.property.nativeId?.toString(16),
                    "writable" to it.writable,
                    "writeLocked" to it.writeLocked,
                    "family" to CatalogProbe.familyOf(it.name),
                    "areas" to it.areaIds,
                    "status" to when (outcome) {
                        is cc.opencar.assistant.api.ReadOutcome.Ok -> "ok"
                        is cc.opencar.assistant.api.ReadOutcome.Denied -> "denied"
                        is cc.opencar.assistant.api.ReadOutcome.Failed -> "failed"
                        is cc.opencar.assistant.api.ReadOutcome.Unavailable -> "unavailable"
                    },
                    "value" to (outcome as? cc.opencar.assistant.api.ReadOutcome.Ok)?.value?.display(),
                    "hex" to ((outcome as? cc.opencar.assistant.api.ReadOutcome.Ok)?.value
                        as? cc.opencar.assistant.api.PropertyValue.BytesVal)?.hex(64),
                    "permission" to (outcome as? cc.opencar.assistant.api.ReadOutcome.Denied)?.permission,
                    "entity" to it.property.nativeId?.let { id -> session.entityBindings()[id] },
                )
            },
        )
    }
    get("/debug/props/{key}") {
        if (!call.requireToken(debug)) return@get
        val key = call.parameters["key"] ?: return@get
        val entry = session.catalog().firstOrNull { it.name == key || it.property.key == key }
        if (entry == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
            return@get
        }
        val outcome = session.diagnose(entry.property)
        call.respond(
            mapOf(
                "name" to entry.name,
                "outcome" to outcome.toString(),
                "value" to (outcome as? cc.opencar.assistant.api.ReadOutcome.Ok)?.value?.display(),
                "hex" to ((outcome as? cc.opencar.assistant.api.ReadOutcome.Ok)?.value
                    as? cc.opencar.assistant.api.PropertyValue.BytesVal)?.hex(),
                "writable" to entry.writable,
                "writeLocked" to entry.writeLocked,
            ),
        )
    }
    post("/debug/props/{key}") {
        if (!call.requireToken(debug)) return@post
        val key = call.parameters["key"] ?: return@post
        val entry = session.catalog().firstOrNull { it.name == key || it.property.key == key }
        if (entry == null || !entry.writable) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not writable / not found"))
            return@post
        }
        val params = call.params()
        val raw = params["value"]
        if (raw == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "value required"))
            return@post
        }
        val pv = when {
            raw.equals("true", true) || raw.equals("false", true) ->
                cc.opencar.assistant.api.PropertyValue.BoolVal(raw.toBoolean())
            raw.contains('.') -> cc.opencar.assistant.api.PropertyValue.FloatVal(raw.toFloat())
            else -> cc.opencar.assistant.api.PropertyValue.IntVal(raw.toInt())
        }
        val result = session.set(entry.property, pv)
        call.respond(mapOf("ok" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
    }
    get("/debug/export") {
        if (!call.requireToken(debug)) return@get
        val snap = session.telemetry().first()
        val report = withContext(Dispatchers.IO) { probe.run(false) }
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
            put("logs.txt", LogRingBuffer.snapshot().joinToString("\n"))
            put("integration.json", """{"id":"${session.integrationId}","variant":"$variantId"}""")
            put("telemetry.json", snap.toString())
            put("identity.json", """{"ip":"${debug.wifiIp()}","fingerprint":"${debug.buildFingerprint()}"}""")
            put("probe_summary.json", report.summary().toString())
            put("probe_full.json", report.toJsonArray().toString())
        }
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"oaa-debug.zip\"")
        call.respondBytes(baos.toByteArray(), ContentType.Application.Zip)
    }
    get("/debug/adb-hint") {
        call.respond(
            mapOf(
                "ip" to debug.wifiIp(),
                "hint" to debug.adbHint(),
                "package" to "cc.opencar.assistant",
                "contributor" to debug.contributorMode,
                "tokenHint" to if (debug.contributorMode) debug.token else "(enable contributor mode)",
            ),
        )
    }

    get("/api/lab") {
        call.respond(labSnapshot(deps))
    }
    post("/api/lab/contributor") {
        val params = call.params()
        val raw = params["enabled"]
        if (raw == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "enabled required"))
            return@post
        }
        debug.contributorMode = parseBool(raw) == true
        call.respond(labSnapshot(deps) + ("ok" to true))
    }
    post("/api/lab/integration-override") {
        val params = call.params()
        val raw = params["id"]
        val id = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && id !in deps.integrationIds) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "unknown integration", "integrations" to deps.integrationIds),
            )
            return@post
        }
        deps.setIntegrationOverride(id)
        call.respond(
            labSnapshot(deps) + mapOf(
                "ok" to true,
                "restartRequired" to true,
                "hint" to "Force-stop the app or reboot the HU, then reopen Open Automotive Assistant to apply the override.",
            ),
        )
    }

    webSocket("/debug/logs/stream") {
        val token = call.request.queryParameters["token"]
        if (!debug.checkToken(token)) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "token required"))
            return@webSocket
        }
        LogRingBuffer.snapshot().forEach { send(Frame.Text(it)) }
        LogRingBuffer.live.collect { send(Frame.Text(it)) }
    }
}

internal fun labSnapshot(deps: OaaWebDeps): Map<String, Any?> {
    val debug = deps.debug
    val override = deps.getIntegrationOverride()
    return mapOf(
        "contributor" to debug.contributorMode,
        "token" to if (debug.contributorMode) debug.token else null,
        "tokenHint" to if (debug.contributorMode) debug.token else "(enable contributor mode)",
        "integration" to deps.session.integrationId,
        "integrationOverride" to override,
        "integrations" to deps.integrationIds,
        "adbHint" to debug.adbHint(),
        "wifiIp" to debug.wifiIp(),
    )
}

internal fun htmlDebug(debug: ContributorDebugState): String = """
    <!doctype html><html data-theme="dark"><head><meta charset=utf-8><title>Open Automotive Assistant Debug</title>
    <style>body{padding:24px;font-family:system-ui,sans-serif;background:#111;color:#e1e1e1}a{color:#03a9f4}code{color:#9b9b9b}</style></head><body>
    <h1>Contributor debug</h1>
    <p>Token: <code>${if (debug.contributorMode) debug.token else "(disabled)"}</code></p>
    <p>ADB: <code>${debug.adbHint()}</code></p>
    <p>Lab UI: <a href="/lab">/lab</a> · <a href="/api/lab">/api/lab</a></p>
    <ul>
      <li><a href="/">Product UI</a></li>
      <li><a href="/debug/adb-hint">/debug/adb-hint</a></li>
      <li><a href="/debug/integration">/debug/integration</a></li>
      <li><a href="/debug/probe?token=${debug.token}">/debug/probe</a></li>
      <li><a href="/debug/logs?token=${debug.token}">/debug/logs</a></li>
      <li><a href="/debug/export?token=${debug.token}">/debug/export</a></li>
    </ul>
    </body></html>
""".trimIndent()
