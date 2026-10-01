package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.plugin.ShortcutActionHandler
import cc.opencar.assistant.protocol.OaaServices
import kotlinx.coroutines.delay

/**
 * Executes a flow or routine action sequence. Service calls resolve to control writes that
 * go through [setControl]; scenes via [setScene]; nested routines via [runRoutine] with recursion guard.
 */
class ShortcutRunner(
    private val context: Context,
    private val setControl: suspend (entityId: String, value: String) -> Result<Unit>,
    private val launcher: AppLauncher = AppLauncher(context),
    private val actionHandlers: Map<String, ShortcutActionHandler> = emptyMap(),
    private val setScene: (suspend (sceneId: String, active: Boolean?) -> Map<String, Any?>)? = null,
    private val getRoutine: (suspend (routineId: String) -> Routine?)? = null,
    private val readEntity: (suspend (String) -> String?)? = null,
    private val readGear: (suspend () -> Int?)? = null,
    private val readWifiSsid: (() -> String?)? = null,
    /** Entity row as `/api/entities` serves it; services that toggle or cycle read it. */
    private val readEntityRow: (suspend (String) -> Map<String, Any?>?)? = null,
) {
    private val runningRoutines = ThreadLocal.withInitial { mutableSetOf<String>() }

    suspend fun run(shortcut: Shortcut): Map<String, Any?> {
        if (!shortcut.enabled) {
            return mapOf("ok" to false, "error" to "disabled")
        }
        return runActions(shortcut.id, shortcut.actions, kind = "flow")
    }

    suspend fun runRoutine(routine: Routine): Map<String, Any?> {
        if (!routine.enabled) {
            return mapOf("ok" to false, "error" to "disabled")
        }
        if (!ConditionEvaluator.conditionsPass(
                conditions = routine.conditions,
                readEntity = readEntity,
                readGear = readGear,
                readWifiSsid = readWifiSsid,
            )
        ) {
            return mapOf("ok" to false, "error" to "conditions not met", "id" to routine.id)
        }
        val stack = runningRoutines.get()!!
        if (routine.id in stack) {
            return mapOf("ok" to false, "error" to "routine recursion: ${routine.id}")
        }
        stack.add(routine.id)
        return try {
            runActions(routine.id, routine.actions, kind = "routine")
        } finally {
            stack.remove(routine.id)
        }
    }

    private suspend fun runActions(
        id: String,
        actions: List<ShortcutAction>,
        kind: String,
    ): Map<String, Any?> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((index, action) in actions.withIndex()) {
            val step = runAction(action)
            results.add(mapOf("index" to index, "action" to action.toMap()) + step)
            if (step["ok"] != true) {
                Log.w(TAG, "$kind $id step $index failed: ${step["error"]}")
                return mapOf(
                    "ok" to false,
                    "id" to id,
                    "error" to step["error"],
                    "steps" to results,
                )
            }
        }
        return mapOf("ok" to true, "id" to id, "steps" to results)
    }

    private suspend fun runAction(action: ShortcutAction): Map<String, Any?> {
        return when (action) {
            is ShortcutAction.CallService -> callService(action)
            is ShortcutAction.LaunchApp -> {
                val ok = launcher.launch(action.packageName)
                if (ok) mapOf("ok" to true)
                else mapOf("ok" to false, "error" to "launch failed")
            }
            is ShortcutAction.DelayMs -> {
                delay(action.ms.coerceIn(0L, ShortcutAction.MAX_DELAY_MS))
                mapOf("ok" to true)
            }
            is ShortcutAction.Plugin -> {
                val handler = actionHandlers[action.pluginId]
                    ?: return mapOf("ok" to false, "error" to "plugin not available: ${action.pluginId}")
                if (action.action !in handler.actionTypes) {
                    return mapOf("ok" to false, "error" to "unsupported action: ${action.action}")
                }
                val r = handler.run(action.action, action.params)
                if (r.isSuccess) mapOf("ok" to true)
                else mapOf("ok" to false, "error" to (r.exceptionOrNull()?.message ?: "plugin action failed"))
            }
            is ShortcutAction.SetScene -> {
                val fn = setScene
                    ?: return mapOf("ok" to false, "error" to "scenes unavailable")
                val result = fn(action.sceneId, action.active)
                if (result["ok"] == true) mapOf("ok" to true, "scene" to result)
                else mapOf("ok" to false, "error" to (result["error"] ?: "set_scene failed"))
            }
            is ShortcutAction.RunRoutine -> {
                val getter = getRoutine
                    ?: return mapOf("ok" to false, "error" to "routines unavailable")
                val routine = getter(action.routineId)
                    ?: return mapOf("ok" to false, "error" to "routine not found: ${action.routineId}")
                val result = runRoutine(routine)
                if (result["ok"] == true) mapOf("ok" to true, "routine" to result)
                else mapOf("ok" to false, "error" to (result["error"] ?: "run_routine failed"))
            }
        }
    }

    private suspend fun callService(action: ShortcutAction.CallService): Map<String, Any?> {
        val needsRow = OaaServices.find(action.service)?.readsEntity == true
        val row = if (needsRow) readEntityRow?.invoke(action.entityId) else null
        val writes = OaaServices.resolve(action.service, action.entityId, action.data, row)
            .getOrElse { return mapOf("ok" to false, "error" to (it.message ?: "bad service call")) }
        for (value in writes) {
            val r = setControl(action.entityId, value)
            if (r.isFailure) {
                return mapOf("ok" to false, "error" to (r.exceptionOrNull()?.message ?: "set failed"), "writes" to writes)
            }
        }
        return mapOf("ok" to true, "writes" to writes)
    }

    companion object {
        private const val TAG = "ShortcutRunner"
    }
}
