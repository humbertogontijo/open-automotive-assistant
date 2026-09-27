package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import cc.opencar.assistant.api.QuickEntry
import kotlin.math.abs

/**
 * Default [QuickEntry] for non-Flyme platforms: a WindowManager float chip
 * placed *below* the status bar so it stays touchable.
 */
class FloatChipQuickEntry : QuickEntry {
    override val style: String = STYLE

    private var appContext: Context? = null
    private var listener: QuickEntry.Listener? = null
    private var wm: WindowManager? = null
    private var chipView: View? = null
    private var chipParams: WindowManager.LayoutParams? = null
    private var visibleWanted = true

    override fun start(context: Context, listener: QuickEntry.Listener) {
        this.appContext = context.applicationContext
        this.listener = listener
        this.wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (visibleWanted) attach()
    }

    override fun stop() {
        detach()
        listener = null
        appContext = null
        wm = null
    }

    override fun setVisible(visible: Boolean) {
        visibleWanted = visible
        if (visible) attach() else detach()
    }

    override fun status(): Map<String, Any?> = mapOf(
        "attached" to (chipView != null),
        "style" to style,
        "canDrawOverlays" to (appContext?.let { Settings.canDrawOverlays(it) } ?: false),
        "sizeDp" to SIZE_DP,
    )

    private fun attach() {
        if (chipView != null) return
        val ctx = appContext ?: return
        val windowManager = wm ?: return
        val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val sizePx = (SIZE_DP * density).toInt().coerceAtLeast(72)
        val statusBarH = statusBarHeightPx(ctx).coerceAtLeast((96 * density).toInt())
        val chip = ChipView(ctx, sizePx)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            // Below status bar so touches aren't eaten by TopCarSystemBar
            y = statusBarH + (8 * density).toInt()
        }
        bindTap(chip, params)
        try {
            windowManager.addView(chip, params)
            chipView = chip
            chipParams = params
            Log.i(TAG, "float chip attached size=${sizePx}px y=${params.y}")
        } catch (t: Throwable) {
            Log.w(TAG, "float chip attach failed: ${t.message}")
        }
    }

    private fun detach() {
        val chip = chipView ?: return
        try {
            wm?.removeView(chip)
        } catch (_: Throwable) {
        }
        chipView = null
        chipParams = null
    }

    private fun bindTap(chip: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false
        chip.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > CLICK_THRESHOLD || abs(dy) > CLICK_THRESHOLD) dragging = true
                    if (dragging) {
                        params.x = (initialX - dx).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceAtLeast(0)
                        try {
                            wm?.updateViewLayout(chip, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val loc = IntArray(2)
                        chip.getLocationOnScreen(loc)
                        val anchor = Rect(
                            loc[0],
                            loc[1],
                            loc[0] + chip.width,
                            loc[1] + chip.height,
                        )
                        listener?.onActivated(anchor)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun statusBarHeightPx(ctx: Context): Int {
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) return ctx.resources.getDimensionPixelSize(resId)
        return (96 * ctx.resources.displayMetrics.density).toInt()
    }

    private class ChipView(context: Context, private val size: Int) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.07f
            color = Color.WHITE
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.30f
            isFakeBoldText = true
        }
        private val corner = size * 0.22f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bg.color = ACCENT
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), corner, corner, bg)
            val cx = size / 2f
            val cy = size / 2f - size * 0.08f
            canvas.drawCircle(cx, cy, size * 0.20f, ring)
            canvas.drawText("OAA", cx, size * 0.78f, label)
        }
    }

    companion object {
        private const val TAG = "FloatChipEntry"
        const val STYLE = "float_chip"
        private const val SIZE_DP = 96
        private const val CLICK_THRESHOLD = 12
        private val ACCENT = Color.parseColor("#1E88E5")
    }
}
