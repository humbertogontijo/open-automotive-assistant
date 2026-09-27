package cc.opencar.assistant.feature.shortcuts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.opencar.assistant.api.EntityContract
import cc.opencar.assistant.api.QuickEntry
import cc.opencar.assistant.api.VehicleSession
import cc.opencar.assistant.api.plugin.ShortcutActionHandler
import cc.opencar.assistant.api.plugin.ShortcutTriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.UUID

/**
 * Facade: flows + routines + scenes + runner + trigger engine + platform [QuickEntry].
 */
class ShortcutsController(
    private val context: Context,
    session: VehicleSession,
    private val setControl: suspend (entityId: String, value: String) -> Result<Unit>,
    private val mainActivityClass: Class<*>,
    quickEntry: QuickEntry? = null,
    actionHandlers: Map<String, ShortcutActionHandler> = emptyMap(),
    triggerSources: List<ShortcutTriggerSource> = emptyList(),
    private val readEntity: (suspend (String) -> String?)? = null,
    private val readGear: (suspend () -> Int?)? = null,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val ioScope = parentScope + Dispatchers.IO
    val store = ShortcutStore.get(context)
    val sceneStore = SceneStore.get(context)
    val launcher = AppLauncher(context)

    val sceneEngine = SceneEngine(
        store = sceneStore,
        setControl = setControl,
        readEntity = { id -> readEntity?.invoke(id) },
    )

    /** Writes from flows/routines — may conflict with an active scene. */
    private suspend fun trackedSetControl(entityId: String, value: String): Result<Unit> {
        val result = setControl(entityId, value)
        if (result.isSuccess) {
            sceneEngine.onExternalWrite(entityId)
        }
        return result
    }

    private lateinit var wifiMonitor: WifiSsidMonitor

    val runner = ShortcutRunner(
        context = context,
        setControl = ::trackedSetControl,
        launcher = launcher,
        actionHandlers = actionHandlers,
        setScene = { sceneId, active ->
            if (active == null) sceneEngine.toggle(sceneId)
            else sceneEngine.setActive(sceneId, active)
        },
        getRoutine = { id -> store.getRoutine(id) },
        readEntity = readEntity,
        readGear = readGear,
        readWifiSsid = {
            if (::wifiMonitor.isInitialized) wifiMonitor.currentSsid() else null
        },
    )

    private var entityWatcher: EntityValueWatcher? = null
    val engine: ShortcutTriggerEngine

    init {
        val monitor = WifiSsidMonitor(context, store, onChanged = { ssid -> engine.onWifiSsid(ssid) }, scope = ioScope)
        wifiMonitor = monitor
        engine = ShortcutTriggerEngine(
            session = session,
            store = store,
            runner = runner,
            triggerSources = triggerSources,
            readEntity = readEntity,
            readWifiSsid = { monitor.currentSsid() },
            readGear = readGear,
            scope = ioScope,
        )
        if (readEntity != null) {
            entityWatcher = EntityValueWatcher(
                store = store,
                readEntity = readEntity,
                onChanged = { id, value -> engine.onEntityChanged(id, value) },
                events = session.events(),
                scope = ioScope,
            )
        }
    }

    private val scope = parentScope + Dispatchers.Main.immediate
    private val entry: QuickEntry = quickEntry ?: FloatChipQuickEntry()
    private var menu: QuickEntryMenu? = null
    private var entryStarted = false
    private var visibilityJob: Job? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            store.ensureSeeded()
            sceneStore.list() // seeds sentinel
        }
        engine.start()
        wifiMonitor.start()
        entityWatcher?.start()
    }

    fun stop() {
        entityWatcher?.stop()
        wifiMonitor.stop()
        engine.stop()
        detachOverlay()
    }

    fun onBoot() {
        scope.launch(Dispatchers.IO) {
            try {
                sceneEngine.restoreAllActive()
            } catch (t: Throwable) {
                Log.w(TAG, "boot scene restore failed: ${t.message}")
            }
            // Await boot flows so they cannot start until scene off-path finishes.
            engine.onBoot()
        }
    }

    /**
     * Notify that [entityId] was written outside the scene engine (web UI, etc.).
     * Active scenes that list this entity as a target are deactivated.
     */
    fun onControlWritten(entityId: String) {
        if (entityId.isBlank()) return
        if (entityId.startsWith("shortcut_")) return
        scope.launch(Dispatchers.IO) {
            runCatching { sceneEngine.onExternalWrite(entityId) }
                .onFailure { Log.w(TAG, "scene conflict check failed: ${it.message}") }
        }
    }

    fun onScreenOn(source: String = "direct") {
        engine.onScreenOn(source)
    }

    fun onScreenOff(source: String = "direct") {
        engine.onScreenOff(source)
    }

    fun attachOverlay(hostContext: Context = context) {
        if (entryStarted) return
        val m = QuickEntryMenu(
            context = hostContext,
            store = store,
            onRunShortcut = { id -> engine.runById(id) },
            onOpenOaa = { section -> sendActivity(hostContext, QuickEntryMenu.ACTION_OPEN_SECTION, section) },
            onExitOaa = { sendActivity(hostContext, QuickEntryMenu.ACTION_EXIT, null) },
            onBackgroundOaa = { sendActivity(hostContext, QuickEntryMenu.ACTION_BACKGROUND, null) },
            scope = scope,
        )
        menu = m
        m.start()
        entry.start(hostContext, QuickEntry.Listener { anchor -> m.onActivated(anchor) })
        entryStarted = true
        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            entry.setVisible(store.isOverlayEnabled())
            store.overlayEnabled.distinctUntilChanged().collect { enabled ->
                entry.setVisible(enabled)
            }
        }
    }

    fun detachOverlay() {
        visibilityJob?.cancel()
        visibilityJob = null
        entry.stop()
        menu?.stop()
        menu = null
        entryStarted = false
    }

    fun refreshOverlay() {
        scope.launch {
            entry.setVisible(store.isOverlayEnabled())
        }
    }

    fun overlayStatus(): Map<String, Any?> {
        return entry.status() + mapOf(
            "canDrawOverlays" to android.provider.Settings.canDrawOverlays(context),
        )
    }

    suspend fun setOverlayEnabled(enabled: Boolean): Map<String, Any?> {
        store.setOverlayEnabled(enabled)
        entry.setVisible(enabled)
        return overlayStatus() + mapOf("overlayEnabled" to enabled)
    }

    fun decorateNotification(context: Context, notification: Notification, visible: Boolean) {
        entry.decorateNotification(context, notification, visible)
    }

    fun notificationContentIntent(context: Context): PendingIntent? =
        entry.notificationContentIntent(context)

    suspend fun listMaps(): List<Map<String, Any?>> {
        store.ensureSeeded()
        return store.list().map { it.toMap() }
    }

    suspend fun listRoutineMaps(): List<Map<String, Any?>> {
        store.ensureSeeded()
        return store.listRoutines().map { it.toMap() }
    }

    suspend fun listSceneMaps(): List<Map<String, Any?>> {
        val active = sceneStore.activeIds()
        return sceneStore.list().map { it.toMap(active = it.id in active) }
    }

    suspend fun slotMaps(): Map<String, String?> {
        val map = store.slotMap()
        return (0 until 8).associate { i -> i.toString() to map[i] }
    }

    suspend fun setSlot(slot: Int, shortcutId: String?): Map<String, Any?> {
        store.setSlot(slot, shortcutId)
        return mapOf("ok" to true, "slots" to slotMaps())
    }

    suspend fun upsertFromMap(body: Map<String, Any?>): Map<String, Any?> {
        store.ensureSeeded()
        val existingId = body["id"] as? String
        val base = if (existingId != null) store.get(existingId) else null
        val name = body["name"] as? String ?: base?.name ?: "Shortcut"
        val icon = body["icon"] as? String ?: base?.icon ?: "drive"
        val enabled = body["enabled"] as? Boolean ?: base?.enabled ?: true
        @Suppress("UNCHECKED_CAST")
        val actionsRaw = body["actions"] as? List<Map<*, *>>
        @Suppress("UNCHECKED_CAST")
        val triggersRaw = body["triggers"] as? List<Map<*, *>>
        @Suppress("UNCHECKED_CAST")
        val conditionsRaw = body["conditions"] as? List<Map<*, *>>
        val actions = actionsRaw?.mapNotNull { ShortcutAction.fromMap(it) }
            ?: base?.actions
            ?: emptyList()
        val triggers = triggersRaw?.mapNotNull { ShortcutTrigger.fromMap(it) }
            ?: base?.triggers
            ?: emptyList()
        val conditions = conditionsRaw?.mapNotNull { ShortcutCondition.fromMap(it) }
            ?: base?.conditions
            ?: emptyList()
        val id = existingId ?: UUID.randomUUID().toString().take(8)
        val saved = store.upsert(
            Shortcut(
                id = id,
                name = name,
                icon = icon,
                enabled = enabled,
                actions = actions.take(ShortcutAction.MAX_ACTIONS),
                triggers = triggers,
                conditions = conditions,
            ),
        )
        return mapOf("ok" to true, "shortcut" to saved.toMap())
    }

    suspend fun upsertRoutineFromMap(body: Map<String, Any?>): Map<String, Any?> {
        store.ensureSeeded()
        val existingId = body["id"] as? String
        val base = if (existingId != null) store.getRoutine(existingId) else null
        val name = body["name"] as? String ?: base?.name ?: "Routine"
        val icon = body["icon"] as? String ?: base?.icon ?: "drive"
        val enabled = body["enabled"] as? Boolean ?: base?.enabled ?: true
        @Suppress("UNCHECKED_CAST")
        val actionsRaw = body["actions"] as? List<Map<*, *>>
        val actions = actionsRaw?.mapNotNull { ShortcutAction.fromMap(it) }
            ?: base?.actions
            ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val conditionsRaw = body["conditions"] as? List<Map<*, *>>
        val conditions = conditionsRaw?.mapNotNull { ShortcutCondition.fromMap(it) }
            ?: base?.conditions
            ?: emptyList()
        val uiCard = if (body.containsKey("uiCard")) {
            UiCardSpec.fromAny(body["uiCard"])
        } else {
            base?.uiCard
        }
        val id = existingId ?: UUID.randomUUID().toString().take(8)
        val saved = store.upsertRoutine(
            Routine(
                id = id,
                name = name,
                icon = icon,
                enabled = enabled,
                actions = actions.take(ShortcutAction.MAX_ACTIONS),
                conditions = conditions,
                uiCard = uiCard,
            ),
        )
        return mapOf("ok" to true, "routine" to saved.toMap())
    }

    suspend fun upsertSceneFromMap(body: Map<String, Any?>): Map<String, Any?> {
        val existingId = body["id"] as? String
        val base = if (existingId != null) sceneStore.get(existingId) else null
        val name = body["name"] as? String ?: base?.name ?: "Scene"
        val icon = body["icon"] as? String ?: base?.icon ?: "climate"
        val enabled = body["enabled"] as? Boolean ?: base?.enabled ?: true
        @Suppress("UNCHECKED_CAST")
        val targetsRaw = body["targets"] as? List<Map<*, *>>
        val targets = targetsRaw?.mapNotNull { SceneTarget.fromMap(it) }
            ?: base?.targets
            ?: emptyList()
        val uiCard = if (body.containsKey("uiCard")) {
            UiCardSpec.fromAny(body["uiCard"])
        } else {
            base?.uiCard
        }
        val id = existingId ?: UUID.randomUUID().toString().take(8)
        val builtin = base?.builtin == true || id == Scene.SENTINEL_ID
        val saved = sceneStore.upsert(
            Scene(
                id = id,
                name = name,
                icon = icon,
                enabled = enabled,
                builtin = builtin,
                targets = targets.take(Scene.MAX_TARGETS),
                uiCard = uiCard,
            ),
        )
        val active = sceneStore.isActive(saved.id)
        return mapOf("ok" to true, "scene" to saved.toMap(active = active))
    }

    suspend fun delete(id: String): Map<String, Any?> {
        val ok = store.delete(id)
        return mapOf("ok" to ok)
    }

    suspend fun deleteRoutine(id: String): Map<String, Any?> {
        val ok = store.deleteRoutine(id)
        return mapOf("ok" to ok)
    }

    suspend fun deleteScene(id: String): Map<String, Any?> {
        val ok = sceneStore.delete(id)
        val scene = sceneStore.get(id)
        return if (scene != null) {
            mapOf("ok" to true, "reset" to scene.builtin, "scene" to scene.toMap(active = sceneStore.isActive(id)))
        } else {
            mapOf("ok" to ok)
        }
    }

    suspend fun setSceneActive(id: String, active: Boolean): Map<String, Any?> =
        sceneEngine.setActive(id, active)

    suspend fun run(id: String): Map<String, Any?> = engine.runById(id)

    suspend fun runRoutine(id: String): Map<String, Any?> {
        val routine = store.getRoutine(id) ?: return mapOf("ok" to false, "error" to "not found")
        return runner.runRoutine(routine)
    }

    /**
     * Virtual control rows from flows that include a [ShortcutTrigger.UiCard] trigger.
     * - Flow with a [ShortcutAction.SetScene] → bool card bound to that scene
     * - Otherwise → command card that runs the flow
     */
    suspend fun virtualEntityMaps(): List<Map<String, Any?>> {
        store.ensureSeeded()
        val out = mutableListOf<Map<String, Any?>>()
        val active = sceneStore.activeIds()
        for (flow in store.list()) {
            if (!flow.enabled) continue
            val ui = flow.triggers.filterIsInstance<ShortcutTrigger.UiCard>().firstOrNull()
                ?: continue
            val setScene = flow.actions.filterIsInstance<ShortcutAction.SetScene>().firstOrNull()
            if (setScene != null) {
                val scene = sceneStore.get(setScene.sceneId)
                val isOn = setScene.sceneId in active
                out.add(
                    EntityContract.enrich(
                        mapOf(
                            "id" to "shortcut_${flow.id}",
                            "group" to ui.group,
                            "domain" to "extra",
                            "label" to flow.name,
                            "input" to "bool",
                            "value" to if (isOn) "1" else "0",
                            "binary" to true,
                            "writable" to true,
                            "status" to "ok",
                            "stale" to false,
                            "icon" to (scene?.icon ?: flow.icon),
                            "writeOnly" to false,
                            "virtual" to true,
                            "virtualKind" to "shortcut_scene",
                            "virtualRef" to flow.id,
                            "sceneId" to setScene.sceneId,
                        ),
                    ),
                )
            } else {
                out.add(
                    EntityContract.enrich(
                        mapOf(
                            "id" to "shortcut_${flow.id}",
                            "group" to ui.group,
                            "domain" to "extra",
                            "label" to flow.name,
                            "input" to "command",
                            "value" to null,
                            "options" to listOf(mapOf("labelKey" to "common.run", "value" to "1")),
                            "writable" to true,
                            "status" to "ok",
                            "stale" to false,
                            "icon" to flow.icon,
                            "writeOnly" to true,
                            "virtual" to true,
                            "virtualKind" to "shortcut",
                            "virtualRef" to flow.id,
                        ),
                    ),
                )
            }
        }
        return out
    }

    /** Handle writes to virtual `shortcut_*` control ids. */
    suspend fun handleVirtualWrite(id: String, raw: String): Map<String, Any?>? {
        if (!id.startsWith("shortcut_")) return null
        val flowId = id.removePrefix("shortcut_")
        val flow = store.get(flowId) ?: return mapOf("ok" to false, "error" to "not found")
        val setScene = flow.actions.filterIsInstance<ShortcutAction.SetScene>().firstOrNull()
        if (setScene != null) {
            val on = raw == "1" || raw.equals("true", true) || raw == "on"
            return sceneEngine.setActive(setScene.sceneId, on)
        }
        return run(flowId)
    }

    private fun sendActivity(host: Context, action: String, section: String?) {
        val intent = Intent(host, mainActivityClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            this.action = action
            if (!section.isNullOrBlank()) {
                putExtra(QuickEntryMenu.EXTRA_SECTION, section)
            }
        }
        try {
            host.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "activity $action failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ShortcutsCtrl"
    }
}
