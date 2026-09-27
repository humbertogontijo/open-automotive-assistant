package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shared dropdown for [cc.opencar.assistant.api.QuickEntry] activation.
 * Platform-agnostic — only shows the panel; entry affordance is elsewhere.
 */
class QuickEntryMenu(
    context: Context,
    private val store: ShortcutStore,
    private val onRunShortcut: suspend (id: String) -> Unit,
    private val onOpenOaa: (section: String?) -> Unit,
    private val onExitOaa: () -> Unit,
    private val onBackgroundOaa: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val appContext = context.applicationContext
    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var menuRoot: LinearLayout? = null
    private var expanded = false
    private var observeJob: Job? = null
    private var slotsJob: Job? = null
    private var pinned: List<Shortcut> = emptyList()
    private var enabled = true

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            store.overlayEnabled.collect { on ->
                enabled = on
                if (!on) collapse()
            }
        }
        slotsJob = scope.launch {
            combine(store.shortcuts, store.slots) { list, slots ->
                (0 until 8).mapNotNull { slot ->
                    val id = slots[slot] ?: return@mapNotNull null
                    list.firstOrNull { it.id == id && it.enabled }
                }
            }.collect { pinned = it; if (expanded) rebuildMenu() }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        slotsJob?.cancel()
        slotsJob = null
        collapse()
    }

    fun onActivated(anchor: Rect?) {
        if (!enabled) {
            Log.i(TAG, "entry click ignored (disabled)")
            return
        }
        scope.launch {
            if (expanded) {
                collapse()
            } else {
                showMenu(anchor)
                expanded = true
            }
        }
    }

    private fun showMenu(anchor: Rect?) {
        if (menuRoot != null) return
        val density = appContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL_BG)
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt(),
            )
        }
        val scroll = ScrollView(appContext).apply { addView(panel) }
        menuRoot = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
        }
        rebuildMenuInto(panel)

        val width = (280 * density).toInt()
        val maxH = (420 * density).toInt()
        val statusBarH = statusBarHeightPx().coerceAtLeast((96 * density).toInt())
        val dm = appContext.resources.displayMetrics
        val screenW = dm.widthPixels

        val hasBounds = anchor != null && anchor.width() > 0 && anchor.height() > 0
        val menuX: Int
        val menuY: Int
        val gravity: Int
        if (hasBounds && anchor != null) {
            val iconCenterX = (anchor.left + anchor.right) / 2
            menuY = (anchor.bottom + (8 * density).toInt()).coerceAtLeast(statusBarH + 4)
            if (iconCenterX < screenW / 2) {
                gravity = Gravity.TOP or Gravity.START
                menuX = anchor.left.coerceAtLeast(0)
            } else {
                gravity = Gravity.TOP or Gravity.END
                menuX = (screenW - anchor.right).coerceAtLeast(0)
            }
        } else {
            gravity = Gravity.TOP or Gravity.END
            menuX = (16 * density).toInt()
            menuY = statusBarH + (8 * density).toInt()
        }

        val params = WindowManager.LayoutParams(
            width,
            maxH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = menuX
            y = menuY
        }
        try {
            wm.addView(menuRoot, params)
            Log.i(TAG, "menu shown at x=$menuX y=$menuY")
        } catch (t: Throwable) {
            Log.w(TAG, "menu attach failed: ${t.message}")
            menuRoot = null
            expanded = false
        }
    }

    private fun statusBarHeightPx(): Int {
        val resId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) return appContext.resources.getDimensionPixelSize(resId)
        return (96 * appContext.resources.displayMetrics.density).toInt()
    }

    private fun collapse() {
        expanded = false
        hideMenu()
    }

    private fun hideMenu() {
        val menu = menuRoot ?: return
        try {
            wm.removeView(menu)
        } catch (_: Throwable) {
        }
        menuRoot = null
    }

    private fun rebuildMenu() {
        val menu = menuRoot ?: return
        val scroll = menu.getChildAt(0) as? ScrollView ?: return
        val panel = scroll.getChildAt(0) as? LinearLayout ?: return
        rebuildMenuInto(panel)
    }

    private fun rebuildMenuInto(panel: LinearLayout) {
        panel.removeAllViews()
        addMenuItem(panel, "Open") { collapseAnd { onOpenOaa(null) } }
        addMenuItem(panel, "Cameras") { collapseAnd { onOpenOaa("cameras") } }
        addMenuItem(panel, "Shortcuts") { collapseAnd { onOpenOaa("shortcuts") } }
        addMenuItem(panel, "Background") { collapseAnd { onBackgroundOaa() } }
        addMenuItem(panel, "Exit") { collapseAnd { onExitOaa() } }
        if (pinned.isNotEmpty()) {
            addDivider(panel)
            for (s in pinned.take(8)) {
                addMenuItem(panel, s.name.ifBlank { "Shortcut" }) {
                    collapseAnd {
                        scope.launch(Dispatchers.IO) { onRunShortcut(s.id) }
                    }
                }
            }
        }
    }

    private fun collapseAnd(block: () -> Unit) {
        collapse()
        block()
    }

    private fun addMenuItem(panel: LinearLayout, label: String, onClick: () -> Unit) {
        val density = appContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val tv = TextView(appContext).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(
                (16 * density).toInt(),
                (18 * density).toInt(),
                (16 * density).toInt(),
                (18 * density).toInt(),
            )
            minHeight = (56 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }
        panel.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun addDivider(panel: LinearLayout) {
        val density = appContext.resources.displayMetrics.density.coerceAtLeast(1f)
        val line = View(appContext).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")) }
        panel.addView(
            line,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt(),
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            },
        )
    }

    companion object {
        private const val TAG = "QuickEntryMenu"
        private val PANEL_BG = Color.parseColor("#F0121820")

        const val ACTION_OPEN_SECTION = "cc.opencar.assistant.OPEN_SECTION"
        const val ACTION_EXIT = "cc.opencar.assistant.EXIT"
        const val ACTION_BACKGROUND = "cc.opencar.assistant.BACKGROUND"
        const val EXTRA_SECTION = "section"
    }
}
