package cc.opencar.assistant.feature.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Routing.registerShortcutRoutes(deps: OaaWebDeps) {
    val shortcuts = deps.shortcuts

    get("/api/shortcuts") {
        if (shortcuts == null) {
            call.respond(
                mapOf(
                    "shortcuts" to emptyList<Any>(),
                    "routines" to emptyList<Any>(),
                    "scenes" to emptyList<Any>(),
                    "overlay" to mapOf("available" to false),
                ),
            )
            return@get
        }
        val list = shortcuts.listMaps()
        val overlayEnabled = shortcuts.store.isOverlayEnabled()
        call.respond(
            mapOf(
                "shortcuts" to list,
                "routines" to shortcuts.listRoutineMaps(),
                "scenes" to shortcuts.listSceneMaps(),
                "slots" to shortcuts.slotMaps(),
                "overlay" to shortcuts.overlayStatus() + mapOf("overlayEnabled" to overlayEnabled),
                "wheelKeys" to listOf(
                    "custom", "mute", "top", "left", "right", "bottom", "vr", "menu", "confirm",
                ),
            ),
        )
    }

    post("/api/shortcuts/slots") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val body = call.receive<Map<String, Any?>>()
        val slot = when (val v = body["slot"]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        } ?: return@post call.respond(mapOf("ok" to false, "error" to "missing slot"))
        val id = body["shortcutId"] as? String
        call.respond(shortcuts.setSlot(slot, id?.ifBlank { null }))
    }

    post("/api/shortcuts") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val body = call.receive<Map<String, Any?>>()
        call.respond(shortcuts.upsertFromMap(body))
    }

    delete("/api/shortcuts/{id}") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@delete
        }
        val id = call.parameters["id"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        call.respond(shortcuts.delete(id))
    }

    post("/api/shortcuts/{id}/run") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val id = call.parameters["id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        call.respond(shortcuts.run(id))
    }

    post("/api/shortcuts/overlay") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val body = call.receive<Map<String, Any?>>()
        val enabled = when (val v = body["enabled"]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> parseBool(v) == true
            else -> true
        }
        call.respond(shortcuts.setOverlayEnabled(enabled) + mapOf("ok" to true))
    }

    post("/api/shortcuts/overlay/request") {
        SetupActionBus.requestOverlayPermission()
        call.respond(mapOf("ok" to true))
    }

    // --- Routines ---

    get("/api/routines") {
        if (shortcuts == null) {
            call.respond(mapOf("routines" to emptyList<Any>()))
            return@get
        }
        call.respond(mapOf("routines" to shortcuts.listRoutineMaps()))
    }

    post("/api/routines") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val body = call.receive<Map<String, Any?>>()
        call.respond(shortcuts.upsertRoutineFromMap(body))
    }

    delete("/api/routines/{id}") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@delete
        }
        val id = call.parameters["id"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        call.respond(shortcuts.deleteRoutine(id))
    }

    post("/api/routines/{id}/run") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val id = call.parameters["id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        call.respond(shortcuts.runRoutine(id))
    }

    // --- Scenes ---

    get("/api/scenes") {
        if (shortcuts == null) {
            call.respond(mapOf("scenes" to emptyList<Any>()))
            return@get
        }
        call.respond(mapOf("scenes" to shortcuts.listSceneMaps()))
    }

    post("/api/scenes") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val body = call.receive<Map<String, Any?>>()
        call.respond(shortcuts.upsertSceneFromMap(body))
    }

    delete("/api/scenes/{id}") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@delete
        }
        val id = call.parameters["id"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        call.respond(shortcuts.deleteScene(id))
    }

    post("/api/scenes/{id}/set") {
        if (shortcuts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "error" to "unavailable"))
            return@post
        }
        val id = call.parameters["id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("ok" to false, "error" to "missing id"),
        )
        val body = call.receive<Map<String, Any?>>()
        val active = when (val v = body["active"]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> parseBool(v) == true
            else -> return@post call.respond(mapOf("ok" to false, "error" to "missing active"))
        }
        call.respond(shortcuts.setSceneActive(id, active))
    }

    get("/api/apps") {
        if (shortcuts == null) {
            call.respond(mapOf("apps" to emptyList<Any>()))
            return@get
        }
        call.respond(mapOf("apps" to shortcuts.launcher.listLaunchable().map { it.toMap() }))
    }

}
