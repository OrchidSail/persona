package persona.platform.phonetools.control

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
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
import kotlin.math.roundToInt

enum class OverlayDisplayMode {
    MINI_PILL,
    LIGHT_CARD,
    INLINE_INTERACTIVE,
    FULLSCREEN_BAR,
}

/**
 * Plan C non-intrusive multi-mode floating overlay mounted ONLY during active remote tasks:
 * 1. [OverlayDisplayMode.MINI_PILL] (default `minimized = true`): ~124dp x 32dp translucent edge pill.
 *    Switches to amber pulsing glow on `waiting_on_phone` (`PENDING_ATTENTION`) without expanding or blocking screen.
 * 2. [OverlayDisplayMode.LIGHT_CARD]: fixed 116dp x ~233dp live ~5fps mini-screen card opened when tapping the pill.
 * 3. [OverlayDisplayMode.INLINE_INTERACTIVE] (Plan C Mode A): centered modal (~86% W x ~76% H) with 45% scrim,
 *    60fps [TextureView] directly bound to the virtual display surface, real-time coordinate-mapped streaming
 *    touch injection, `setSystemGestureExclusionRects` edge protection, drag disabled, and 52dp bottom action bar.
 * 4. [OverlayDisplayMode.FULLSCREEN_BAR] (Plan C Mode B): collapses preview card into a compact 40dp top single-line
 *    action bar ("Done, return control" + "Stop") so middle/bottom sliders and buttons are never obscured.
 */
class TaskStatusOverlay(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onTakeoverOrResumePhone: () -> Unit,
    private val onEmergencyStop: () -> Unit,
    private val onAutoDismissed: () -> Unit = {},
    private val onBringToForeground: () -> Unit = {},
    private val onOpenHandover: () -> Unit = {},
    private val onSwitchToFullscreen: () -> Unit = {},
    private val onCompleteHandback: () -> Unit = {},
    private val onAttachInteractiveSurface: (Surface) -> Boolean = { false },
    private val onDetachInteractiveSurface: () -> Unit = {},
    private val onInjectInteractiveMotion: (action: Int, downTime: Long, eventTime: Long, x: Float, y: Float, pressure: Float) -> Boolean = { _, _, _, _, _, _ -> false },
    private val virtualDisplaySizeProvider: () -> Pair<Int, Int> = { 1080 to 2340 },
    private val accessibilityHostProvider: () -> Context? = { PhoneControlAccessibilityService.instance.value },
    private val canDrawOverlaysProvider: () -> Boolean = { runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false) },
    private val windowManagerProvider: (Context) -> WindowManager? = { it.getSystemService(WindowManager::class.java) },
) {
    internal data class OverlayWindowHost(
        val hostContext: Context,
        val windowType: Int,
        val isAccessibilityHost: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureDepth = AtomicInteger(0)

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pendingDismiss: Runnable? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var activeInteractiveSurface: Surface? = null

    private var minimized: Boolean = true
    private var currentMode: OverlayDisplayMode = OverlayDisplayMode.MINI_PILL
    private var mountedHostContext: Context? = null
    private var mountedWindowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var failedAccessibilityHost: Context? = null

    @Volatile
    var lastSummary: OverlayTaskSummary = OverlayTaskSummary(null, "idle", 0, "", isActive = false)
        private set

    @Volatile
    var usingVirtualDisplayMode: Boolean = false
        private set

    @Volatile
    private var latestPreviewBitmap: Bitmap? = null

    @Volatile
    private var latestPreviewProtected: Boolean = false

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

    private var inlineHeaderTitle: TextView? = null
    private var inlineProtectedBanner: TextView? = null
    private var inlineTextureView: TextureView? = null

    private var fullscreenLabelView: TextView? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    val displayMode: OverlayDisplayMode
        get() = currentMode

    internal val fixedCardWidthPx: Int
        get() = dimenPx(R.dimen.phonetools_overlay_card_width)

    internal val fixedPreviewHeightPx: Int
        get() = dimenPx(R.dimen.phonetools_overlay_preview_height)

    internal val currentLayoutParams: WindowManager.LayoutParams?
        get() = layoutParams

    internal val activeWindowType: Int?
        get() = if (isShowing) mountedWindowType else null

    internal val activeHostContext: Context?
        get() = if (isShowing) mountedHostContext else null

    fun onAccessibilityServiceChanged(service: Context?) {
        val _ = mainHandler.post {
            handleAccessibilityServiceChangedOnMain(service)
        }
    }

    fun update(summary: OverlayTaskSummary) {
        lastSummary = summary
        usingVirtualDisplayMode = summary.usingVirtualDisplay
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            if (!summary.isActive && summary.status == "idle") {
                hideOnMain()
                return@post
            }
            val desiredMode = resolveDisplayMode(summary)
            if (!isShowing) {
                currentMode = desiredMode
                showOnMain(desiredMode)
            } else if (desiredMode != currentMode) {
                switchModeOnMain(desiredMode)
            } else if (needsHostMigrationOnMain()) {
                remountCurrentModeOnMain()
            }
            if (isShowing) {
                updateKeepScreenOnFlagOnMain(summary.keepScreenOnForeground && !usingVirtualDisplayMode)
                bindCurrentModeOnMain(summary)
            }
        }
    }

    fun updatePreviewFrame(bitmap: Bitmap?, usingVirtualDisplay: Boolean, protectedFrame: Boolean = false) {
        usingVirtualDisplayMode = usingVirtualDisplay
        latestPreviewBitmap = bitmap
        latestPreviewProtected = protectedFrame
        val _ = mainHandler.post {
            if (!isShowing) return@post
            updateKeepScreenOnFlagOnMain(lastSummary.keepScreenOnForeground && !usingVirtualDisplay)
            when (currentMode) {
                OverlayDisplayMode.LIGHT_CARD -> bindPreviewOnMain(bitmap, usingVirtualDisplay, protectedFrame)
                OverlayDisplayMode.INLINE_INTERACTIVE -> {
                    inlineProtectedBanner?.visibility = if (protectedFrame) View.VISIBLE else View.GONE
                }
                else -> Unit
            }
        }
    }

    fun hide() {
        val _ = mainHandler.post {
            cancelPendingDismissOnMain()
            hideOnMain()
        }
    }

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

    internal fun resolveDisplayMode(summary: OverlayTaskSummary): OverlayDisplayMode {
        if (!summary.isActive) return OverlayDisplayMode.MINI_PILL
        return when (summary.handoverPhase) {
            HandoverPhase.INLINE -> OverlayDisplayMode.INLINE_INTERACTIVE
            HandoverPhase.FULLSCREEN -> OverlayDisplayMode.FULLSCREEN_BAR
            HandoverPhase.RETURNING -> OverlayDisplayMode.MINI_PILL
            HandoverPhase.PENDING_ATTENTION -> OverlayDisplayMode.MINI_PILL
            HandoverPhase.NONE -> if (minimized) OverlayDisplayMode.MINI_PILL else OverlayDisplayMode.LIGHT_CARD
        }
    }

    internal fun resolveWindowCandidates(): List<OverlayWindowHost> = buildList {
        val a11yHost = accessibilityHostProvider()
        if (a11yHost != null && isDefaultDisplayAccessibilityHost(a11yHost)) {
            add(
                OverlayWindowHost(
                    hostContext = a11yHost,
                    windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    isAccessibilityHost = true,
                ),
            )
        }
        if (canDrawOverlaysProvider()) {
            add(
                OverlayWindowHost(
                    hostContext = resolveAppOverlayContext(),
                    windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    isAccessibilityHost = false,
                ),
            )
        }
    }

    internal fun isDefaultDisplayAccessibilityHost(host: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = runCatching { host.display?.displayId }.getOrNull()
            if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                return false
            }
        }
        return true
    }

    private fun viewContextFor(hostContext: Context): Context {
        val themeRes = runCatching { context.applicationInfo?.theme ?: 0 }.getOrDefault(0)
        return if (themeRes != 0) ContextThemeWrapper(hostContext, themeRes) else hostContext
    }

    private fun needsHostMigrationOnMain(): Boolean {
        if (!isShowing) return false
        val preferred = resolveWindowCandidates().firstOrNull() ?: return true
        return when (mountedWindowType) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> {
                !preferred.isAccessibilityHost || preferred.hostContext !== mountedHostContext
            }
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> {
                preferred.isAccessibilityHost && preferred.hostContext !== failedAccessibilityHost
            }
            else -> true
        }
    }

    private fun handleAccessibilityServiceChangedOnMain(service: Context?) {
        if (service == null || service !== failedAccessibilityHost) {
            failedAccessibilityHost = null
        }
        if (!lastSummary.isActive) {
            if (isShowing &&
                mountedWindowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                (service == null || service !== mountedHostContext)
            ) {
                hideOnMain()
            }
            return
        }
        if (isShowing) {
            if (needsHostMigrationOnMain()) {
                remountCurrentModeOnMain()
            }
        } else if (resolveWindowCandidates().isNotEmpty()) {
            val desiredMode = resolveDisplayMode(lastSummary)
            currentMode = desiredMode
            showOnMain(desiredMode)
            if (isShowing) {
                updateKeepScreenOnFlagOnMain(lastSummary.keepScreenOnForeground && !usingVirtualDisplayMode)
                bindCurrentModeOnMain(lastSummary)
            }
        }
    }

    private fun remountCurrentModeOnMain() {
        val targetMode = currentMode
        val preservedMinimized = minimized
        val preservedSummary = lastSummary
        val preservedVirtual = usingVirtualDisplayMode
        val preservedBitmap = latestPreviewBitmap
        val preservedProtected = latestPreviewProtected
        val prevWm = windowManager
        val prevView = rootView

        stopPulseOnMain()
        releaseInteractiveSurfaceOnMain()
        if (prevWm != null && prevView != null) {
            val _ = runCatching { prevWm.removeViewImmediate(prevView) }
        }
        clearViewRefs()
        windowManager = null
        mountedHostContext = null
        rootView = null
        layoutParams = null
        isShowing = false

        minimized = preservedMinimized
        lastSummary = preservedSummary
        usingVirtualDisplayMode = preservedVirtual
        latestPreviewBitmap = preservedBitmap
        latestPreviewProtected = preservedProtected
        currentMode = targetMode

        showOnMain(targetMode)
        if (isShowing) {
            updateKeepScreenOnFlagOnMain(preservedSummary.keepScreenOnForeground && !preservedVirtual)
            bindCurrentModeOnMain(preservedSummary)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOnMain(mode: OverlayDisplayMode) {
        if (isShowing) return
        val candidates = resolveWindowCandidates()
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            val wm = windowManagerProvider(candidate.hostContext) ?: continue
            val viewCtx = viewContextFor(candidate.hostContext)
            val (view, params) = buildViewAndParamsForMode(viewCtx, candidate.windowType, mode, wm)
            val mounted = try {
                wm.addView(view, params)
                true
            } catch (_: WindowManager.BadTokenException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: Throwable) {
                false
            }
            if (mounted) {
                windowManager = wm
                mountedHostContext = candidate.hostContext
                mountedWindowType = candidate.windowType
                if (candidate.isAccessibilityHost) {
                    failedAccessibilityHost = null
                }
                rootView = view
                layoutParams = params
                currentMode = mode
                isShowing = true
                return
            } else {
                if (candidate.isAccessibilityHost) {
                    failedAccessibilityHost = candidate.hostContext
                }
                releaseInteractiveSurfaceOnMain()
                clearViewRefs()
            }
        }
    }

    private fun switchModeOnMain(nextMode: OverlayDisplayMode) {
        val prevWm = windowManager ?: return
        val prevHost = mountedHostContext ?: return
        val prevView = rootView

        if (currentMode == OverlayDisplayMode.INLINE_INTERACTIVE) {
            releaseInteractiveSurfaceOnMain()
        }
        stopPulseOnMain()

        val preferred = resolveWindowCandidates().firstOrNull()
        val sameHost = preferred != null &&
            preferred.windowType == mountedWindowType &&
            (!preferred.isAccessibilityHost || preferred.hostContext === prevHost)

        if (sameHost &&
            ((currentMode == OverlayDisplayMode.MINI_PILL && nextMode == OverlayDisplayMode.LIGHT_CARD) ||
                (currentMode == OverlayDisplayMode.LIGHT_CARD && nextMode == OverlayDisplayMode.MINI_PILL))
        ) {
            if (prevView is LinearLayout && previewContainer != null) {
                currentMode = nextMode
                minimized = (nextMode == OverlayDisplayMode.MINI_PILL)
                applyCompactCardOrPillLayoutOnMain(relayout = true)
                return
            }
        }

        if (prevView != null) {
            val _ = runCatching { prevWm.removeViewImmediate(prevView) }
        }
        clearViewRefs()
        windowManager = null
        mountedHostContext = null
        rootView = null
        layoutParams = null
        isShowing = false
        currentMode = nextMode
        if (nextMode == OverlayDisplayMode.MINI_PILL || nextMode == OverlayDisplayMode.LIGHT_CARD) {
            minimized = (nextMode == OverlayDisplayMode.MINI_PILL)
        }
        showOnMain(nextMode)
    }

    private fun buildViewAndParamsForMode(
        ctx: Context,
        windowType: Int,
        mode: OverlayDisplayMode,
        wm: WindowManager,
    ): Pair<View, WindowManager.LayoutParams> {
        clearViewRefs()
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val keepFlag = if (lastSummary.keepScreenOnForeground && !usingVirtualDisplayMode) {
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            0
        }

        return when (mode) {
            OverlayDisplayMode.MINI_PILL, OverlayDisplayMode.LIGHT_CARD -> {
                val isPill = mode == OverlayDisplayMode.MINI_PILL
                val view = buildOverlayView(ctx)
                val w = if (isPill) currentPillWidthPx(lastSummary) else fixedCardWidthPx
                val h = if (isPill) dimenPx(R.dimen.phonetools_overlay_pill_height) else computeExpandedCardHeightPx()
                val params = WindowManager.LayoutParams(
                    w, h, windowType, baseFlags or keepFlag, PixelFormat.TRANSLUCENT,
                ).apply {
                    title = OVERLAY_WINDOW_TITLE
                    gravity = Gravity.TOP or Gravity.END
                    x = dimenPx(R.dimen.phonetools_overlay_margin_end)
                    y = dimenPx(R.dimen.phonetools_overlay_margin_top)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                attachEdgeDragBehavior(view, params, wm)
                applyCompactCardOrPillLayoutOnMain(relayout = false, forcePill = isPill)
                view to params
            }
            OverlayDisplayMode.INLINE_INTERACTIVE -> {
                val view = buildInlineInteractiveOverlay(ctx)
                val modalFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    keepFlag
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    modalFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    title = OVERLAY_WINDOW_TITLE
                    gravity = Gravity.CENTER
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                view to params
            }
            OverlayDisplayMode.FULLSCREEN_BAR -> {
                val view = buildFullscreenTopBar(ctx)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_height),
                    windowType,
                    baseFlags or keepFlag,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    title = OVERLAY_WINDOW_TITLE
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_top_margin)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                attachTopBarDragBehavior(view, params, wm)
                view to params
            }
        }
    }

    private fun hideOnMain() {
        if (!isShowing) {
            failedAccessibilityHost = null
            return
        }
        isShowing = false
        stopPulseOnMain()
        releaseInteractiveSurfaceOnMain()
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            val _ = runCatching { wm.removeViewImmediate(view) }
        }
        clearViewRefs()
        windowManager = null
        mountedHostContext = null
        mountedWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        failedAccessibilityHost = null
        rootView = null
        layoutParams = null
        minimized = true
        currentMode = OverlayDisplayMode.MINI_PILL
        usingVirtualDisplayMode = false
        latestPreviewBitmap = null
        latestPreviewProtected = false
    }

    private fun clearViewRefs() {
        statusDotView = null
        statusTitleView = null
        collapseBtn = null
        previewContainer = null
        previewImageView = null
        previewPlaceholderView = null
        previewModeBadgeView = null
        actionDetailView = null
        controlsRow = null
        pauseBtn = null
        takeoverBtn = null
        stopBtn = null
        inlineHeaderTitle = null
        inlineProtectedBanner = null
        inlineTextureView = null
        fullscreenLabelView = null
    }

    private fun releaseInteractiveSurfaceOnMain() {
        val surf = activeInteractiveSurface
        activeInteractiveSurface = null
        if (surf != null) {
            onDetachInteractiveSurface()
            val _ = runCatching { surf.release() }
        }
    }

    private fun updateKeepScreenOnFlagOnMain(keepOn: Boolean) {
        val wm = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        val nextFlags = if (keepOn) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (nextFlags != params.flags) {
            params.flags = nextFlags
            val _ = runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun resolveAppOverlayContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    context.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(context)
            }
        }
        return context
    }

    internal fun buildOverlayViewForTest(
        ctx: Context,
        summary: OverlayTaskSummary,
        frame: Bitmap?,
        protectedFrame: Boolean = false,
    ): LinearLayout {
        lastSummary = summary
        usingVirtualDisplayMode = summary.usingVirtualDisplay
        latestPreviewBitmap = frame
        latestPreviewProtected = protectedFrame
        currentMode = OverlayDisplayMode.LIGHT_CARD
        minimized = false
        val view = buildOverlayView(ctx)
        applyCompactCardOrPillLayoutOnMain(relayout = false, forcePill = false)
        bindPreviewOnMain(frame, summary.usingVirtualDisplay, protectedFrame)
        bindSummaryOnMain(summary)
        return view
    }

    internal fun buildMiniPillForTest(ctx: Context, summary: OverlayTaskSummary): LinearLayout {
        lastSummary = summary
        usingVirtualDisplayMode = summary.usingVirtualDisplay
        currentMode = OverlayDisplayMode.MINI_PILL
        minimized = true
        val view = buildOverlayView(ctx)
        rootView = view
        applyCompactCardOrPillLayoutOnMain(relayout = false, forcePill = true)
        bindSummaryOnMain(summary)
        stopPulseOnMain()
        rootView = null
        return view
    }

    internal fun buildInlineOverlayForTest(
        ctx: Context,
        summary: OverlayTaskSummary,
        protectedFrame: Boolean = false,
    ): FrameLayout {
        lastSummary = summary
        usingVirtualDisplayMode = summary.usingVirtualDisplay
        latestPreviewProtected = protectedFrame
        currentMode = OverlayDisplayMode.INLINE_INTERACTIVE
        val view = buildInlineInteractiveOverlay(ctx)
        bindInlineOnMain(summary)
        return view
    }

    internal fun buildFullscreenBarForTest(ctx: Context, summary: OverlayTaskSummary): LinearLayout {
        lastSummary = summary
        usingVirtualDisplayMode = summary.usingVirtualDisplay
        currentMode = OverlayDisplayMode.FULLSCREEN_BAR
        val view = buildFullscreenTopBar(ctx)
        bindFullscreenBarOnMain(summary)
        return view
    }

    private fun cancelPendingDismissOnMain() {
        pendingDismiss?.let { mainHandler.removeCallbacks(it) }
        pendingDismiss = null
    }

    private fun stopPulseOnMain() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusDotView?.alpha = 1f
    }

    private fun startPulseOnMain() {
        val dot = statusDotView ?: return
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1_100L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
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

    private fun currentPillWidthPx(summary: OverlayTaskSummary): Int =
        if (summary.isWaitingOnPhone) {
            dimenPx(R.dimen.phonetools_overlay_pill_attention_width)
        } else {
            dimenPx(R.dimen.phonetools_overlay_pill_width)
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
                if (lastSummary.isWaitingOnPhone && currentMode == OverlayDisplayMode.MINI_PILL) {
                    onOpenHandover()
                } else {
                    minimized = !minimized
                    currentMode = if (minimized) OverlayDisplayMode.MINI_PILL else OverlayDisplayMode.LIGHT_CARD
                    applyCompactCardOrPillLayoutOnMain(relayout = true)
                }
            }
        }
        collapseBtn = toggle

        header.addView(dot)
        header.addView(title)
        header.addView(toggle)
        root.addView(header)

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

        previewFrame.setOnClickListener {
            if (usingVirtualDisplayMode) {
                if (latestPreviewProtected) {
                    onBringToForeground()
                } else {
                    onOpenHandover()
                }
            }
        }
        previewContainer = previewFrame
        root.addView(previewFrame)

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

    @SuppressLint("ClickableViewAccessibility")
    private fun buildInlineInteractiveOverlay(ctx: Context): FrameLayout {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(320)
        val screenH = dm.heightPixels.coerceAtLeast(480)
        val (vdW, vdH) = virtualDisplaySizeProvider().let { (w, h) ->
            w.coerceAtLeast(1) to h.coerceAtLeast(1)
        }
        val aspect = vdW.toFloat() / vdH.toFloat()

        val headerH = dimenPx(R.dimen.phonetools_overlay_inline_header_height)
        val footerH = dimenPx(R.dimen.phonetools_overlay_inline_footer_height)
        val modalPad = dimenPx(R.dimen.phonetools_overlay_inline_padding)
        val maxCardW = (screenW * 0.86f).roundToInt()
        val maxCardH = (screenH * 0.84f).roundToInt()
        val maxContentH = ((screenH * 0.76f).roundToInt() - headerH - footerH - modalPad * 2).coerceAtLeast(240)
        val maxContentW = (maxCardW - modalPad * 2).coerceAtLeast(200)

        var surfaceW = maxContentW
        var surfaceH = (surfaceW / aspect).roundToInt()
        if (surfaceH > maxContentH) {
            surfaceH = maxContentH
            surfaceW = (surfaceH * aspect).roundToInt().coerceAtMost(maxContentW)
        }
        val cardW = (surfaceW + modalPad * 2).coerceAtMost(maxCardW)
        val cardH = (surfaceH + headerH + footerH + modalPad * 2).coerceAtMost(maxCardH)

        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(colorRes(R.color.phonetools_overlay_scrim))
            isClickable = true
            isFocusable = false
        }

        val modalRadius = dimenPx(R.dimen.phonetools_overlay_inline_corner_radius).toFloat()
        val modal = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(modalPad, modalPad, modalPad, modalPad)
            elevation = dimenPx(R.dimen.phonetools_overlay_elevation).toFloat() * 2f
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_bg))
                cornerRadius = modalRadius
                setStroke(
                    dimenPx(R.dimen.phonetools_overlay_stroke_width),
                    colorRes(R.color.phonetools_overlay_stroke_attention),
                )
            }
            layoutParams = FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH)
        }
        val dotSize = dimenPx(R.dimen.phonetools_overlay_dot_size)
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dimenPx(R.dimen.phonetools_overlay_inline_gap)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorRes(R.color.phonetools_overlay_dot_warning))
            }
        }
        statusDotView = dot

        val title = TextView(ctx).apply {
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_inline_header_text))
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inlineHeaderTitle = title
        header.addView(dot)
        header.addView(title)
        modal.addView(header)

        val viewport = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(surfaceW, 0, 1f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val r = dimenPx(R.dimen.phonetools_overlay_preview_corner_radius).toFloat()
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_screen_bg))
                cornerRadius = r
                setStroke(dimenPx(R.dimen.phonetools_overlay_stroke_width), colorRes(R.color.phonetools_overlay_stroke))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
        }

        val texture = TextureView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    val (dw, dh) = virtualDisplaySizeProvider()
                    if (dw > 0 && dh > 0) {
                        val _ = runCatching { st.setDefaultBufferSize(dw, dh) }
                    }
                    val surf = Surface(st)
                    activeInteractiveSurface = surf
                    if (!onAttachInteractiveSurface(surf)) {
                        activeInteractiveSurface = null
                        val _ = runCatching { surf.release() }
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    releaseInteractiveSurfaceOnMain()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                applySystemGestureExclusions(v)
            }
            setOnTouchListener { v, event ->
                val vw = v.width.coerceAtLeast(1).toFloat()
                val vh = v.height.coerceAtLeast(1).toFloat()
                val (dw, dh) = virtualDisplaySizeProvider()
                val mappedX = (event.x / vw * dw.toFloat()).coerceIn(0f, (dw - 1).coerceAtLeast(0).toFloat())
                val mappedY = (event.y / vh * dh.toFloat()).coerceIn(0f, (dh - 1).coerceAtLeast(0).toFloat())
                val masked = event.actionMasked
                if (masked == MotionEvent.ACTION_DOWN ||
                    masked == MotionEvent.ACTION_MOVE ||
                    masked == MotionEvent.ACTION_UP ||
                    masked == MotionEvent.ACTION_CANCEL
                ) {
                    val _ = onInjectInteractiveMotion(
                        masked,
                        event.downTime,
                        event.eventTime,
                        mappedX,
                        mappedY,
                        event.pressure,
                    )
                }
                true
            }
        }
        inlineTextureView = texture
        viewport.addView(texture)

        val protectedBanner = TextView(ctx).apply {
            text = context.getString(R.string.phonetools_overlay_inline_protected_banner)
            gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_inline_btn_text))
            setBackgroundColor(colorRes(R.color.phonetools_overlay_bg))
            val pad = dimenPx(R.dimen.phonetools_overlay_inline_padding)
            setPadding(pad, pad, pad, pad)
            visibility = if (latestPreviewProtected) View.VISIBLE else View.GONE
            setOnClickListener { onSwitchToFullscreen() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        inlineProtectedBanner = protectedBanner
        viewport.addView(protectedBanner)
        modal.addView(viewport)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, footerH)
        }
        val gap = dimenPx(R.dimen.phonetools_overlay_inline_gap)
        val btnH = dimenPx(R.dimen.phonetools_overlay_inline_btn_height)

        val fsBtn = makeModalButton(
            ctx,
            context.getString(R.string.phonetools_overlay_inline_switch_fullscreen),
            colorRes(R.color.phonetools_overlay_btn_neutral),
            weight = 0.85f,
            heightPx = btnH,
            marginEndPx = gap,
        ) { onSwitchToFullscreen() }

        val stopInlineBtn = makeModalButton(
            ctx,
            context.getString(R.string.phonetools_overlay_inline_stop),
            colorRes(R.color.phonetools_overlay_btn_stop),
            weight = 0.55f,
            heightPx = btnH,
            marginEndPx = gap,
        ) { onEmergencyStop() }

        val doneBtn = makeModalButton(
            ctx,
            context.getString(R.string.phonetools_overlay_inline_done_return),
            colorRes(R.color.phonetools_overlay_btn_success),
            weight = 1.65f,
            heightPx = btnH,
            marginEndPx = 0,
        ) { onCompleteHandback() }

        footer.addView(fsBtn)
        footer.addView(stopInlineBtn)
        footer.addView(doneBtn)
        modal.addView(footer)

        scrim.addView(modal)
        return scrim
    }

    private fun applySystemGestureExclusions(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        val edge = dimenPx(R.dimen.phonetools_overlay_gesture_exclusion_edge).coerceAtMost(w / 3)
        val bandH = dimenPx(R.dimen.phonetools_overlay_gesture_exclusion_height).coerceAtMost(h)
        val top = ((h - bandH) / 2).coerceAtLeast(0)
        val bottom = (top + bandH).coerceAtMost(h)
        view.systemGestureExclusionRects = listOf(
            Rect(0, top, edge, bottom),
            Rect(w - edge, top, w, bottom),
        )
    }

    private fun buildFullscreenTopBar(ctx: Context): LinearLayout {
        val padH = dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_padding_horizontal)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, 0)
            elevation = dimenPx(R.dimen.phonetools_overlay_elevation).toFloat()
            background = GradientDrawable().apply {
                setColor(colorRes(R.color.phonetools_overlay_bg))
                cornerRadius = dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_height) / 2f
                setStroke(
                    dimenPx(R.dimen.phonetools_overlay_stroke_width),
                    colorRes(R.color.phonetools_overlay_stroke_attention),
                )
            }
        }

        val dotSize = dimenPx(R.dimen.phonetools_overlay_dot_size)
        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dimenPx(R.dimen.phonetools_overlay_dot_margin_end)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorRes(R.color.phonetools_overlay_dot_warning))
            }
        }
        statusDotView = dot

        val label = TextView(ctx).apply {
            text = context.getString(R.string.phonetools_overlay_fullscreen_bar_label)
            setTextColor(colorRes(R.color.phonetools_overlay_text))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_inline_btn_text))
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dimenPx(R.dimen.phonetools_overlay_card_width)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dimenPx(R.dimen.phonetools_overlay_inline_gap)
            }
        }
        fullscreenLabelView = label

        val btnH = dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_btn_height)
        val btnPadH = dimenPx(R.dimen.phonetools_overlay_fullscreen_bar_btn_padding_horizontal)
        val gap = dimenPx(R.dimen.phonetools_overlay_inline_gap)

        val doneBtn = makeCompactTopButton(
            ctx,
            context.getString(R.string.phonetools_overlay_fullscreen_done_return),
            colorRes(R.color.phonetools_overlay_btn_success),
            btnH,
            btnPadH,
            marginEndPx = gap,
        ) { onCompleteHandback() }

        val stopBarBtn = makeCompactTopButton(
            ctx,
            context.getString(R.string.phonetools_overlay_inline_stop),
            colorRes(R.color.phonetools_overlay_btn_stop),
            btnH,
            btnPadH,
            marginEndPx = 0,
        ) { onEmergencyStop() }

        bar.addView(dot)
        bar.addView(label)
        bar.addView(doneBtn)
        bar.addView(stopBarBtn)
        return bar
    }

    private fun makeModalButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        weight: Float,
        heightPx: Int,
        marginEndPx: Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(colorRes(R.color.phonetools_overlay_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_inline_btn_text))
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val padH = dimenPx(R.dimen.phonetools_overlay_padding_horizontal)
        setPadding(padH, 0, padH, 0)
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dimenPx(R.dimen.phonetools_overlay_corner_radius).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, heightPx, weight).apply {
            marginEnd = marginEndPx
        }
        setOnClickListener { onClick() }
    }

    private fun makeCompactTopButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        heightPx: Int,
        padH: Int,
        marginEndPx: Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(colorRes(R.color.phonetools_overlay_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.phonetools_overlay_inline_btn_text))
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(padH, 0, padH, 0)
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = heightPx / 2f
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, heightPx).apply {
            marginEnd = marginEndPx
        }
        setOnClickListener { onClick() }
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
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
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

    private fun applyCompactCardOrPillLayoutOnMain(relayout: Boolean, forcePill: Boolean? = null) {
        val isPill = forcePill ?: (currentMode == OverlayDisplayMode.MINI_PILL)
        val visible = if (isPill) View.GONE else View.VISIBLE
        previewContainer?.visibility = visible
        actionDetailView?.visibility = visible
        controlsRow?.visibility = visible
        collapseBtn?.text = context.getString(
            if (isPill) R.string.phonetools_overlay_expand_symbol else R.string.phonetools_overlay_collapse_symbol,
        )
        collapseBtn?.contentDescription = context.getString(
            if (isPill) R.string.phonetools_overlay_expand_cd else R.string.phonetools_overlay_collapse_cd,
        )
        if (!relayout) return
        val wm = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        params.width = if (isPill) currentPillWidthPx(lastSummary) else fixedCardWidthPx
        params.height = if (isPill) dimenPx(R.dimen.phonetools_overlay_pill_height) else computeExpandedCardHeightPx()
        if (runCatching { wm.updateViewLayout(view, params) }.isFailure && lastSummary.isActive) {
            remountCurrentModeOnMain()
        }
    }

    private fun bindCurrentModeOnMain(summary: OverlayTaskSummary) {
        when (currentMode) {
            OverlayDisplayMode.MINI_PILL, OverlayDisplayMode.LIGHT_CARD -> {
                bindSummaryOnMain(summary)
                if (currentMode == OverlayDisplayMode.LIGHT_CARD) {
                    bindPreviewOnMain(latestPreviewBitmap, usingVirtualDisplayMode, latestPreviewProtected)
                }
            }
            OverlayDisplayMode.INLINE_INTERACTIVE -> bindInlineOnMain(summary)
            OverlayDisplayMode.FULLSCREEN_BAR -> bindFullscreenBarOnMain(summary)
        }
    }

    private fun bindInlineOnMain(s: OverlayTaskSummary) {
        inlineHeaderTitle?.text = if (!s.targetAppLabel.isNullOrBlank()) {
            context.getString(R.string.phonetools_overlay_inline_title_with_app, s.targetAppLabel)
        } else {
            context.getString(R.string.phonetools_overlay_inline_title)
        }
        inlineProtectedBanner?.visibility = if (latestPreviewProtected) View.VISIBLE else View.GONE
    }

    private fun bindFullscreenBarOnMain(s: OverlayTaskSummary) {
        fullscreenLabelView?.text = s.targetAppLabel?.take(12)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.phonetools_overlay_fullscreen_bar_label)
    }

    private fun bindPreviewOnMain(bitmap: Bitmap?, isVirtual: Boolean, protectedFrame: Boolean = false) {
        previewModeBadgeView?.text = context.getString(
            if (isVirtual) R.string.phonetools_overlay_mode_background else R.string.phonetools_overlay_mode_foreground,
        )
        if (isVirtual && protectedFrame) {
            previewImageView?.setImageBitmap(null)
            previewImageView?.visibility = View.GONE
            previewPlaceholderView?.visibility = View.VISIBLE
            previewPlaceholderView?.text = context.getString(R.string.phonetools_overlay_preview_protected_placeholder)
        } else if (isVirtual && bitmap != null && !bitmap.isRecycled) {
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

        val isPill = currentMode == OverlayDisplayMode.MINI_PILL
        val isAttentionPill = isPill && s.isWaitingOnPhone && s.isActive
        (rootView?.background as? GradientDrawable)?.let { bg ->
            bg.setColor(
                colorRes(
                    if (isAttentionPill) R.color.phonetools_overlay_pill_attention_bg
                    else R.color.phonetools_overlay_bg,
                ),
            )
            bg.setStroke(
                dimenPx(R.dimen.phonetools_overlay_stroke_width),
                colorRes(
                    if (isAttentionPill) R.color.phonetools_overlay_stroke_attention
                    else R.color.phonetools_overlay_stroke,
                ),
            )
        }
        if (isAttentionPill) {
            startPulseOnMain()
            val params = layoutParams
            val wm = windowManager
            val view = rootView
            val targetW = currentPillWidthPx(s)
            if (params != null && wm != null && view != null && params.width != targetW) {
                params.width = targetW
                val _ = runCatching { wm.updateViewLayout(view, params) }
            }
        } else {
            stopPulseOnMain()
            if (isPill) {
                val params = layoutParams
                val wm = windowManager
                val view = rootView
                val targetW = currentPillWidthPx(s)
                if (params != null && wm != null && view != null && params.width != targetW) {
                    params.width = targetW
                    val _ = runCatching { wm.updateViewLayout(view, params) }
                }
            }
        }

        val statusLabel = context.getString(statusRes)
        statusTitleView?.text = when {
            isAttentionPill -> context.getString(R.string.phonetools_overlay_pill_attention)
            isPill && s.handoverPhase == HandoverPhase.RETURNING -> context.getString(R.string.phonetools_overlay_pill_returning)
            isPill && s.isActive && s.step > 0 -> context.getString(R.string.phonetools_overlay_pill_running_step, s.step)
            isPill && s.isActive -> context.getString(R.string.phonetools_overlay_pill_running)
            s.step > 0 -> context.getString(R.string.phonetools_overlay_title_with_step, statusLabel, s.step)
            else -> context.getString(R.string.phonetools_overlay_title_plain, statusLabel)
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
                    val v = rootView
                    if (v != null && isShowing) {
                        v.animate().alpha(0f).setDuration(FADE_OUT_DURATION_MS).withEndAction {
                            if (!lastSummary.isActive) {
                                hideOnMain()
                                onAutoDismissed()
                            }
                        }.start()
                    } else {
                        hideOnMain()
                        onAutoDismissed()
                    }
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
                        val mid = (edgeMargin + maxOffsetX) / 2
                        params.x = if (params.x > mid) maxOffsetX else edgeMargin
                        val _ = runCatching { wm.updateViewLayout(container, params) }
                    } else if (currentMode == OverlayDisplayMode.MINI_PILL) {
                        if (lastSummary.isWaitingOnPhone) {
                            onOpenHandover()
                        } else {
                            minimized = false
                            currentMode = OverlayDisplayMode.LIGHT_CARD
                            applyCompactCardOrPillLayoutOnMain(relayout = true)
                            bindPreviewOnMain(latestPreviewBitmap, usingVirtualDisplayMode, latestPreviewProtected)
                            bindSummaryOnMain(lastSummary)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTopBarDragBehavior(
        bar: LinearLayout,
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

        bar.setOnTouchListener { _, event ->
            val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val maxY = (screenHeight / 2).coerceAtLeast(minTopY)
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
                        params.x = startX + dx.toInt()
                        params.y = (startY + dy.toInt()).coerceIn(minTopY, maxY)
                        val _ = runCatching { wm.updateViewLayout(bar, params) }
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
        const val AUTO_DISMISS_DELAY_MS = 2_500L
        const val OVERLAY_WINDOW_TITLE = "PersonaTaskStatusOverlay"
        private const val FADE_OUT_DURATION_MS = 220L
    }
}
