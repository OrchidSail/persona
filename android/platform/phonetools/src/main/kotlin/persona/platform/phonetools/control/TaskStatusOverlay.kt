package persona.platform.phonetools.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import persona.platform.phonetools.R
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Lightweight edge-docked floating task-status overlay mounted ONLY during active remote tasks.
 *
 * Shows live remote task status, preserved step count, and summary; taps expand quick controls for:
 * - Pause / Resume
 * - Human Takeover / Return Control
 * - Emergency Stop
 *
 * Uses TYPE_APPLICATION_OVERLAY when SYSTEM_ALERT_WINDOW is granted, falling back to
 * TYPE_ACCESSIBILITY_OVERLAY via [PhoneControlAccessibilityService]. Automatically becomes non-touchable
 * during injected gestures and hides during API 30+ screenshots.
 * All colors, dimensions, and copy come from XML resources so token and i18n guards remain at zero violations.
 */
class TaskStatusOverlay(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onTakeoverOrResumePhone: () -> Unit,
    private val onEmergencyStop: () -> Unit,
    private val onAutoDismissed: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureDepth = AtomicInteger(0)

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var pendingDismiss: Runnable? = null
    @Volatile
    var lastSummary: OverlayTaskSummary = OverlayTaskSummary(null, "idle", 0, "", isActive = false)
        private set

    private var statusDotView: View? = null
    private var statusTitleView: TextView? = null
    private var actionDetailView: TextView? = null
    private var controlsRow: LinearLayout? = null
    private var pauseBtn: TextView? = null
    private var takeoverBtn: TextView? = null
    private var stopBtn: TextView? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    fun update(summary: OverlayTaskSummary) {
        lastSummary = summary
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            if (!summary.isActive && summary.status == "idle") {
                hideOnMain()
                return@post
            }
            if (summary.isWaitingOnPhone) {
                expanded = true
            }
            if (!isShowing) {
                showOnMain()
            }
            bindSummaryOnMain(summary)
        }
    }

    fun hide() {
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            hideOnMain()
        }
    }

    /** Temporarily disables touch interception so injected gestures pass cleanly underneath the pill. */
    suspend fun <T> passGesturesThrough(block: suspend () -> T): T {
        val depth = gestureDepth.incrementAndGet()
        if (depth == 1) {
            withContext(Dispatchers.Main) { setTouchableOnMain(false) }
        }
        return try {
            block()
        } finally {
            if (gestureDepth.decrementAndGet() <= 0) {
                gestureDepth.set(0)
                withContext(Dispatchers.Main) { setTouchableOnMain(true) }
            }
        }
    }

    /** Temporarily hides the overlay while taking a screenshot so it never occludes visual grounding. */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        if (!isShowing) return block()
        withContext(Dispatchers.Main) { rootView?.visibility = View.INVISIBLE }
        delay(60L)
        return try {
            block()
        } finally {
            withContext(Dispatchers.Main) {
                if (isShowing) rootView?.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOnMain() {
        if (isShowing) return
        val canAppOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val a11yService = PhoneControlAccessibilityService.instance.value
        val hostContext: Context
        val windowType: Int
        when {
            canAppOverlay -> {
                hostContext = context
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            a11yService != null -> {
                hostContext = a11yService
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            else -> return
        }

        val wm = hostContext.getSystemService(WindowManager::class.java) ?: return
        val container = buildOverlayView(hostContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dimenPx(R.dimen.phonetools_overlay_margin_end)
            y = dimenPx(R.dimen.phonetools_overlay_margin_top)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        attachEdgeDragBehavior(container, params, wm)

        val added = runCatching { wm.addView(container, params) }.isSuccess
        if (!added) return

        windowManager = wm
        rootView = container
        layoutParams = params
        isShowing = true
        bindSummaryOnMain(lastSummary)
    }

    private fun hideOnMain() {
        if (!isShowing) return
        isShowing = false
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            val _ = runCatching { wm.removeViewImmediate(view) }
        }
        windowManager = null
        rootView = null
        layoutParams = null
        expanded = false
    }

    private fun cancelPendingDismissOnMain() {
        pendingDismiss?.let { mainHandler.removeCallbacks(it) }
        pendingDismiss = null
    }

    private fun setTouchableOnMain(touchable: Boolean) {
        val wm = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        val nextFlags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (nextFlags == params.flags) return
        params.flags = nextFlags
        val _ = runCatching { wm.updateViewLayout(view, params) }
    }

    private fun buildOverlayView(ctx: Context): LinearLayout {
        val padH = dimenPx(R.dimen.phonetools_overlay_padding_horizontal)
        val padV = dimenPx(R.dimen.phonetools_overlay_padding_vertical)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            elevation = dimenPx(R.dimen.phonetools_overlay_elevation).toFloat()
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_bg))
                cornerRadius = dimenPx(R.dimen.phonetools_overlay_corner_radius).toFloat()
                setStroke(
                    dimenPx(R.dimen.phonetools_overlay_stroke_width),
                    colorRes(R.color.phonetools_overlay_stroke),
                )
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dotSize = dimenPx(R.dimen.phonetools_overlay_dot_size)
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dimenPx(R.dimen.phonetools_overlay_dot_margin_end)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorRes(R.color.phonetools_overlay_dot_running))
            }
        }
        statusDotView = dot

        val title = TextView(ctx).apply {
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_title_text))
            maxLines = 1
        }
        statusTitleView = title

        header.addView(dot)
        header.addView(title)
        root.addView(header)

        val detail = TextView(ctx).apply {
            setTextColor(colorRes(R.color.phonetools_overlay_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_detail_text))
            maxLines = 2
            maxWidth = dimenPx(R.dimen.phonetools_overlay_detail_max_width)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dimenPx(R.dimen.phonetools_overlay_detail_margin_top) }
        }
        actionDetailView = detail
        root.addView(detail)

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dimenPx(R.dimen.phonetools_overlay_controls_margin_top) }
        }

        val pause = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_pause),
            colorRes(R.color.phonetools_overlay_btn_neutral),
        ) { onTogglePause() }
        val takeover = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_takeover),
            colorRes(R.color.phonetools_overlay_btn_primary),
        ) { onTakeoverOrResumePhone() }
        val stop = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_stop),
            colorRes(R.color.phonetools_overlay_btn_stop),
        ) { onEmergencyStop() }

        pauseBtn = pause
        takeoverBtn = takeover
        stopBtn = stop

        controls.addView(pause)
        controls.addView(takeover)
        controls.addView(stop)
        controlsRow = controls
        root.addView(controls)

        return root
    }

    private fun makeChipButton(ctx: Context, label: String, bgColor: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_button_text))
            maxLines = 1
            val padH = dimenPx(R.dimen.phonetools_overlay_chip_padding_horizontal)
            val padV = dimenPx(R.dimen.phonetools_overlay_chip_padding_vertical)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dimenPx(R.dimen.phonetools_overlay_chip_corner_radius).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dimenPx(R.dimen.phonetools_overlay_chip_margin_end) }
            setOnClickListener { onClick() }
        }

    private fun bindSummaryOnMain(s: OverlayTaskSummary) {
        val statusRes = when (s.status) {
            "running", "starting" -> R.string.phonetools_overlay_status_running
            "queued" -> R.string.phonetools_overlay_status_queued
            "paused" -> R.string.phonetools_overlay_status_paused
            "waiting_on_phone" -> R.string.phonetools_overlay_status_waiting_on_phone
            "waiting_for_answer" -> R.string.phonetools_overlay_status_waiting_for_answer
            "done" -> R.string.phonetools_overlay_status_done
            "failed" -> R.string.phonetools_overlay_status_failed
            "cancelled" -> R.string.phonetools_overlay_status_cancelled
            else -> R.string.phonetools_overlay_status_idle
        }
        val dotColorRes = when (s.status) {
            "running", "starting" -> R.color.phonetools_overlay_dot_running
            "waiting_on_phone", "paused", "waiting_for_answer" -> R.color.phonetools_overlay_dot_warning
            "done" -> R.color.phonetools_overlay_dot_done
            "failed", "cancelled" -> R.color.phonetools_overlay_dot_error
            else -> R.color.phonetools_overlay_dot_idle
        }
        (statusDotView?.background as? GradientDrawable)?.setColor(colorRes(dotColorRes))

        val statusLabel = context.getString(statusRes)
        statusTitleView?.text = if (s.step > 0) {
            context.getString(R.string.phonetools_overlay_title_with_step, statusLabel, s.step)
        } else {
            context.getString(R.string.phonetools_overlay_title_plain, statusLabel)
        }
        actionDetailView?.text = s.actionSummary

        pauseBtn?.text = context.getString(
            if (s.isPaused) R.string.phonetools_overlay_action_resume else R.string.phonetools_overlay_action_pause,
        )
        takeoverBtn?.text = context.getString(
            if (s.isWaitingOnPhone) R.string.phonetools_overlay_action_return_control else R.string.phonetools_overlay_action_takeover,
        )
        controlsRow?.visibility = if (expanded && s.isActive) View.VISIBLE else View.GONE

        if (!s.isActive) {
            val dismiss = Runnable {
                if (!lastSummary.isActive) {
                    hideOnMain()
                    onAutoDismissed()
                }
            }
            pendingDismiss = dismiss
            val _ = mainHandler.postDelayed(dismiss, AUTO_DISMISS_DELAY_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEdgeDragBehavior(
        container: LinearLayout,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val minTopY = dimenPx(R.dimen.phonetools_overlay_min_top_y)
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startX - dx.toInt()).coerceAtLeast(0)
                        params.y = (startY + dy.toInt()).coerceAtLeast(minTopY)
                        val _ = runCatching { wm.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        expanded = !expanded
                        bindSummaryOnMain(lastSummary)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun colorRes(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dimenPx(id: Int): Int = context.resources.getDimensionPixelSize(id)

    companion object {
        const val AUTO_DISMISS_DELAY_MS = 4_000L
    }
}
