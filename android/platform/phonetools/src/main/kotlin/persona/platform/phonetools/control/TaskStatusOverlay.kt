package persona.platform.phonetools.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Fixed-geometry live mini-screen floating overlay mounted ONLY during active remote tasks.
 *
 * Design guarantees (eliminating layout jump & screenshot flicker):
 * - Fixed outer window width and fixed child heights so changing step counts or narration lengths
 *   NEVER resize or shift the floating card.
 * - Embedded fixed-aspect vertical mini-screen [ImageView] rendering live throttled frames from
 *   the background virtual display (~5fps deduplicated) with zero WindowManager relayout per frame.
 * - Always-visible compact controls row (Pause/Resume, Takeover/Return, Stop) plus a collapse toggle
 *   to shrink into a fixed-size status pill when desired.
 * - In virtual-display mode, [passGesturesThrough] and [hiddenWhile] short-circuit with zero flicker.
 * - Drag anywhere with automatic left/right edge snapping on release.
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
    private var minimized = false
    private var pendingDismiss: Runnable? = null

    @Volatile
    var lastSummary: OverlayTaskSummary = OverlayTaskSummary(null, "idle", 0, "", isActive = false)
        private set

    @Volatile
    var usingVirtualDisplayMode: Boolean = false
        private set

    @Volatile
    private var latestPreviewBitmap: Bitmap? = null

    private var statusDotView: View? = null
    private var statusTitleView: TextView? = null
    private var collapseBtn: TextView? = null
    private var previewContainer: FrameLayout? = null
    private var previewImageView: ImageView? = null
    private var previewPlaceholderView: TextView? = null
    private var previewModeBadgeView: TextView? = null
    private var actionDetailView: TextView? = null
    private var controlsRow: LinearLayout? = null
    private var pauseBtn: TextView? = null
    private var takeoverBtn: TextView? = null
    private var stopBtn: TextView? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    internal val fixedCardWidthPx: Int
        get() = dimenPx(R.dimen.phonetools_overlay_card_width)

    internal val fixedPreviewHeightPx: Int
        get() = dimenPx(R.dimen.phonetools_overlay_preview_height)

    internal val currentLayoutParams: WindowManager.LayoutParams?
        get() = layoutParams

    fun update(summary: OverlayTaskSummary) {
        lastSummary = summary
        if (summary.usingVirtualDisplay) {
            usingVirtualDisplayMode = true
        }
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            if (!summary.isActive && summary.status == "idle") {
                hideOnMain()
                return@post
            }
            if (summary.isWaitingOnPhone && minimized) {
                minimized = false
                applyMinimizedStateOnMain(relayout = true)
            }
            if (!isShowing) {
                showOnMain()
            }
            bindSummaryOnMain(summary)
        }
    }

    /**
     * Updates the mini-screen bitmap in place without touching WindowManager layout params,
     * keeping the floating window completely steady while live frames stream at ~5fps.
     */
    fun updatePreviewFrame(bitmap: Bitmap?, usingVirtualDisplay: Boolean) {
        usingVirtualDisplayMode = usingVirtualDisplay
        latestPreviewBitmap = bitmap
        val _ = mainHandler.post {
            if (!isShowing) return@post
            bindPreviewOnMain(bitmap, usingVirtualDisplay)
        }
    }

    fun hide() {
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            hideOnMain()
        }
    }

    /**
     * Temporarily disables touch interception so injected gestures pass cleanly underneath.
     * No-op when running on a background virtual display (main-screen touches are never injected).
     */
    suspend fun <T> passGesturesThrough(block: suspend () -> T): T {
        if (usingVirtualDisplayMode || !isShowing) return block()
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

    /**
     * Temporarily hides the overlay during foreground API 30+ accessibility screenshots.
     * No-op in background virtual-display mode because frames come directly from ImageReader.
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        if (usingVirtualDisplayMode || !isShowing) return block()
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
        val initialWidth = if (minimized) dimenPx(R.dimen.phonetools_overlay_pill_width) else fixedCardWidthPx
        val initialHeight = if (minimized) dimenPx(R.dimen.phonetools_overlay_pill_height) else computeExpandedCardHeightPx()
        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
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
        applyMinimizedStateOnMain(relayout = false)
        bindPreviewOnMain(latestPreviewBitmap, usingVirtualDisplayMode)
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
        minimized = false
        usingVirtualDisplayMode = false
        latestPreviewBitmap = null
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

    private fun computeExpandedCardHeightPx(): Int {
        val padV = dimenPx(R.dimen.phonetools_overlay_padding_vertical)
        val headerH = dimenPx(R.dimen.phonetools_overlay_header_height)
        val previewMarginTop = dimenPx(R.dimen.phonetools_overlay_preview_margin_top)
        val previewH = fixedPreviewHeightPx
        val detailMarginTop = dimenPx(R.dimen.phonetools_overlay_detail_margin_top)
        val detailH = dimenPx(R.dimen.phonetools_overlay_summary_height)
        val controlsMarginTop = dimenPx(R.dimen.phonetools_overlay_controls_margin_top)
        val controlsH = dimenPx(R.dimen.phonetools_overlay_controls_height)
        return padV * 2 + headerH + previewMarginTop + previewH + detailMarginTop + detailH + controlsMarginTop + controlsH
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

        // Fixed-height single-line header row: dot + ellipsized title + collapse toggle
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dimenPx(R.dimen.phonetools_overlay_header_height),
            )
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
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTitleView = title

        val toggleSize = dimenPx(R.dimen.phonetools_overlay_toggle_size)
        val toggle = TextView(ctx).apply {
            text = context.getString(R.string.phonetools_overlay_collapse_symbol)
            contentDescription = context.getString(R.string.phonetools_overlay_collapse_cd)
            gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.phonetools_overlay_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_button_text))
            layoutParams = LinearLayout.LayoutParams(toggleSize, toggleSize).apply {
                marginStart = dimenPx(R.dimen.phonetools_overlay_chip_margin_end)
            }
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_btn_neutral))
                cornerRadius = (toggleSize / 2f)
            }
            setOnClickListener {
                minimized = !minimized
                applyMinimizedStateOnMain(relayout = true)
            }
        }
        collapseBtn = toggle

        header.addView(dot)
        header.addView(title)
        header.addView(toggle)
        root.addView(header)

        // Fixed-aspect mini-screen live preview frame
        val previewRadius = dimenPx(R.dimen.phonetools_overlay_preview_corner_radius).toFloat()
        val previewFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                fixedPreviewHeightPx,
            ).apply { topMargin = dimenPx(R.dimen.phonetools_overlay_preview_margin_top) }
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_screen_bg))
                cornerRadius = previewRadius
                setStroke(
                    dimenPx(R.dimen.phonetools_overlay_stroke_width),
                    colorRes(R.color.phonetools_overlay_stroke),
                )
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, previewRadius)
                }
            }
            clipToOutline = true
        }

        val imageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        previewImageView = imageView
        previewFrame.addView(imageView)

        val placeholder = TextView(ctx).apply {
            text = context.getString(R.string.phonetools_overlay_preview_foreground_placeholder)
            gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.phonetools_overlay_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_detail_text))
            val innerPad = dimenPx(R.dimen.phonetools_overlay_padding_horizontal)
            setPadding(innerPad, innerPad, innerPad, innerPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        previewPlaceholderView = placeholder
        previewFrame.addView(placeholder)

        val badgePadH = dimenPx(R.dimen.phonetools_overlay_badge_padding_horizontal)
        val badgePadV = dimenPx(R.dimen.phonetools_overlay_badge_padding_vertical)
        val badgeMargin = dimenPx(R.dimen.phonetools_overlay_badge_margin)
        val modeBadge = TextView(ctx).apply {
            text = context.getString(R.string.phonetools_overlay_mode_foreground)
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_badge_text))
            isSingleLine = true
            setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_badge_bg))
                cornerRadius = dimenPx(R.dimen.phonetools_overlay_chip_corner_radius).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(badgeMargin, badgeMargin, badgeMargin, badgeMargin)
            }
        }
        previewModeBadgeView = modeBadge
        previewFrame.addView(modeBadge)

        previewContainer = previewFrame
        root.addView(previewFrame)

        // Fixed single-line ellipsized narration row (zero height reflow)
        val detail = TextView(ctx).apply {
            setTextColor(colorRes(R.color.phonetools_overlay_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_detail_text))
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dimenPx(R.dimen.phonetools_overlay_summary_height),
            ).apply { topMargin = dimenPx(R.dimen.phonetools_overlay_detail_margin_top) }
        }
        actionDetailView = detail
        root.addView(detail)

        // Fixed-height always-visible 3-button controls row (equal weights)
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dimenPx(R.dimen.phonetools_overlay_controls_height),
            ).apply { topMargin = dimenPx(R.dimen.phonetools_overlay_controls_margin_top) }
        }

        val pause = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_pause),
            colorRes(R.color.phonetools_overlay_btn_neutral),
            trailingGap = true,
        ) { onTogglePause() }
        val takeover = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_takeover),
            colorRes(R.color.phonetools_overlay_btn_primary),
            trailingGap = true,
        ) { onTakeoverOrResumePhone() }
        val stop = makeChipButton(
            ctx,
            context.getString(R.string.phonetools_overlay_action_stop),
            colorRes(R.color.phonetools_overlay_btn_stop),
            trailingGap = false,
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

    private fun makeChipButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        trailingGap: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(colorRes(R.color.phonetools_overlay_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_button_text))
        isSingleLine = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val padH = dimenPx(R.dimen.phonetools_overlay_chip_padding_horizontal)
        val padV = dimenPx(R.dimen.phonetools_overlay_chip_padding_vertical)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dimenPx(R.dimen.phonetools_overlay_chip_corner_radius).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            if (trailingGap) marginEnd = dimenPx(R.dimen.phonetools_overlay_chip_margin_end)
        }
        setOnClickListener { onClick() }
    }

    private fun applyMinimizedStateOnMain(relayout: Boolean) {
        val visible = if (minimized) View.GONE else View.VISIBLE
        previewContainer?.visibility = visible
        actionDetailView?.visibility = visible
        controlsRow?.visibility = visible
        collapseBtn?.text = context.getString(
            if (minimized) R.string.phonetools_overlay_expand_symbol else R.string.phonetools_overlay_collapse_symbol,
        )
        collapseBtn?.contentDescription = context.getString(
            if (minimized) R.string.phonetools_overlay_expand_cd else R.string.phonetools_overlay_collapse_cd,
        )
        if (!relayout) return
        val wm = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        params.width = if (minimized) dimenPx(R.dimen.phonetools_overlay_pill_width) else fixedCardWidthPx
        params.height = if (minimized) dimenPx(R.dimen.phonetools_overlay_pill_height) else computeExpandedCardHeightPx()
        val _ = runCatching { wm.updateViewLayout(view, params) }
    }

    private fun bindPreviewOnMain(bitmap: Bitmap?, isVirtual: Boolean) {
        previewModeBadgeView?.text = context.getString(
            if (isVirtual) R.string.phonetools_overlay_mode_background else R.string.phonetools_overlay_mode_foreground,
        )
        if (isVirtual && bitmap != null && !bitmap.isRecycled) {
            previewImageView?.setImageBitmap(bitmap)
            previewImageView?.visibility = View.VISIBLE
            previewPlaceholderView?.visibility = View.GONE
        } else {
            previewImageView?.setImageBitmap(null)
            previewImageView?.visibility = View.GONE
            previewPlaceholderView?.visibility = View.VISIBLE
            previewPlaceholderView?.text = context.getString(
                if (isVirtual) R.string.phonetools_overlay_preview_connecting_placeholder
                else R.string.phonetools_overlay_preview_foreground_placeholder,
            )
        }
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
        val controlsEnabled = s.isActive
        pauseBtn?.isEnabled = controlsEnabled
        takeoverBtn?.isEnabled = controlsEnabled
        stopBtn?.isEnabled = controlsEnabled
        controlsRow?.alpha = if (controlsEnabled) 1f else 0.5f

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
        val edgeMargin = dimenPx(R.dimen.phonetools_overlay_margin_end)
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        container.setOnTouchListener { _, event ->
            val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val maxOffsetX = (screenWidth - params.width - edgeMargin).coerceAtLeast(edgeMargin)
            val maxOffsetY = (screenHeight - params.height - minTopY).coerceAtLeast(minTopY)
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
                        params.x = (startX - dx.toInt()).coerceIn(edgeMargin, maxOffsetX)
                        params.y = (startY + dy.toInt()).coerceIn(minTopY, maxOffsetY)
                        val _ = runCatching { wm.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Snap cleanly to the closer screen edge without resizing.
                        val mid = (edgeMargin + maxOffsetX) / 2
                        params.x = if (params.x > mid) maxOffsetX else edgeMargin
                        val _ = runCatching { wm.updateViewLayout(container, params) }
                    } else if (minimized) {
                        minimized = false
                        applyMinimizedStateOnMain(relayout = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dimenPx(id: Int): Int = context.resources.getDimensionPixelSize(id)

    private fun colorRes(id: Int): Int = ContextCompat.getColor(context, id)

    companion object {
        const val AUTO_DISMISS_DELAY_MS = 4_000L
    }
}
