package persona.platform.phonetools.control

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import persona.platform.phonetools.control.shell.IPhoneControlShell
import persona.platform.phonetools.control.shell.PhoneControlShellService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Concrete Android implementation of [ScreenPort] combining:
 * - Privileged uid-2000 [IPhoneControlShell] + [PhoneControlVirtualDisplay] for true off-screen
 *   background execution, live mini-screen preview frames, and targeted touch/key injection
 *   (zero overlay flashing and zero main-screen focus stealing).
 * - [PhoneControlAccessibilityService] for UI hierarchy collection, text setting, and seamless
 *   foreground fallback whenever the privileged shell is not yet configured.
 * - [NotificationBuffer] for ephemeral in-memory OTP reading and one-time consumption.
 */
internal class AndroidScreenController(
    private val context: Context,
    private val virtualDisplay: PhoneControlVirtualDisplay,
    private val notifications: NotificationBuffer,
    private val shellProvider: () -> IPhoneControlShell? = { null },
) : ScreenPort {

    private val nodeCache = ConcurrentHashMap<String, List<CollectedNode>>()
    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY

    private val _activeOnVirtualDisplay = MutableStateFlow(false)
    val activeOnVirtualDisplay: StateFlow<Boolean> = _activeOnVirtualDisplay.asStateFlow()

    private fun requireA11y(): PhoneControlAccessibilityService =
        PhoneControlAccessibilityService.instance.value
            ?: throw PhoneControlException("accessibility_offline", "Accessibility service is not connected")

    private fun updateActiveDisplay(displayId: Int) {
        activeDisplayId = displayId
        val vdId = virtualDisplay.displayId.value
        _activeOnVirtualDisplay.value = vdId != null && displayId == vdId && vdId > Display.DEFAULT_DISPLAY
    }

    override fun syncActiveDisplay(displayId: Int) {
        updateActiveDisplay(displayId)
    }

    override fun isActuallyUsingVirtualDisplay(): Boolean = isOnVirtualDisplay()

    private fun isOnVirtualDisplay(): Boolean {
        val vdId = virtualDisplay.displayId.value ?: return false
        return activeDisplayId == vdId && vdId > Display.DEFAULT_DISPLAY
    }

    override suspend fun captureSnapshot(
        includeScreenshot: Boolean,
        screenshotMaxEdge: Int,
        screenshotQuality: Int,
    ): ScreenSnapshot = withContext(Dispatchers.IO) {
        val a11y = requireA11y()
        var targetDisplay: Int
        var usingVirtual: Boolean
        var width: Int
        var height: Int
        var collected: CollectedScreen
        var topPkg: String?

        var attempt = 0
        while (true) {
            val virtualId = virtualDisplay.displayId.value
            val vdTopPkg = if (virtualId != null) virtualDisplay.topTaskPackage() else null
            val hasVirtualTask = virtualId != null && (vdTopPkg != null || activeDisplayId == virtualId)

            targetDisplay = if (hasVirtualTask && virtualId != null) virtualId else Display.DEFAULT_DISPLAY
            var windows = a11y.windowsOnDisplay(targetDisplay)
            usingVirtual = virtualId != null && targetDisplay == virtualId

            // Filter SKIPPED_WINDOW_TYPES before checking emptiness so stale IME/overlay windows cannot trap us on an empty VD.
            val hasRenderable = ScreenTreeCollector.hasRenderableContentWindows(windows, context.packageName)
            if (!hasRenderable && usingVirtual && vdTopPkg == null) {
                targetDisplay = Display.DEFAULT_DISPLAY
                windows = a11y.windowsOnDisplay(targetDisplay)
                usingVirtual = false
            }
            updateActiveDisplay(targetDisplay)

            width = if (usingVirtual) virtualDisplay.currentWidth else context.resources.displayMetrics.widthPixels
            height = if (usingVirtual) virtualDisplay.currentHeight else context.resources.displayMetrics.heightPixels

            collected = ScreenTreeCollector.collect(
                windows = windows,
                width = width,
                height = height,
                ownPackage = context.packageName,
            )

            // Display 0 dual fallback: rootInActiveWindow + shell.topTaskPackage(0)
            if (!usingVirtual && collected.nodes.isEmpty()) {
                val activeRoot = runCatching { a11y.rootInActiveWindow }.getOrNull()
                if (activeRoot != null) {
                    val rootCollected = ScreenTreeCollector.collectFromRootNode(
                        root = activeRoot,
                        width = width,
                        height = height,
                        imeVisible = collected.imeVisible,
                    )
                    if (rootCollected.nodes.isNotEmpty() || rootCollected.topPackage != null) {
                        collected = rootCollected
                    }
                }
            }

            topPkg = collected.topPackage
                ?: if (usingVirtual) {
                    vdTopPkg ?: virtualDisplay.topTaskPackage()
                } else {
                    runCatching { a11y.rootInActiveWindow?.packageName?.toString() }.getOrNull()
                        ?: runCatching { shellProvider()?.topTaskPackage(Display.DEFAULT_DISPLAY) }.getOrNull()
                }

            val isEmpty = collected.nodes.isEmpty() && topPkg.isNullOrBlank()
            if (!isEmpty || attempt >= EMPTY_TREE_RETRY_COUNT) {
                break
            }
            attempt++
            delay(EMPTY_TREE_RETRY_DELAY_MS)
        }

        val snapshotId = "snap_${UUID.randomUUID().toString().take(8)}"
        nodeCache.clear()
        val _ = nodeCache.put(snapshotId, collected.nodes)

        val capture = if (includeScreenshot) {
            captureVisual(a11y, targetDisplay, usingVirtual, screenshotMaxEdge, screenshotQuality)
        } else {
            ScreenshotCaptureResult()
        }

        ScreenSnapshot(
            snapshotId = snapshotId,
            displayId = targetDisplay,
            width = width,
            height = height,
            topPackage = topPkg,
            elements = collected.nodes.map { it.element },
            visibleTexts = collected.visibleTexts,
            hasPasswordField = collected.hasPasswordField,
            uiHash = collected.uiHash,
            usingVirtualDisplay = usingVirtual,
            imeVisible = collected.imeVisible,
            windowDiagnostics = collected.diagnostics,
            screenshot = capture.payload,
            screenshotOmittedReason = capture.omittedReason,
        )
    }

    private suspend fun captureVisual(
        a11y: PhoneControlAccessibilityService,
        displayId: Int,
        usingVirtual: Boolean,
        maxEdge: Int,
        quality: Int,
    ): ScreenshotCaptureResult {
        if (usingVirtual) {
            virtualDisplay.latestFrameJpeg(maxEdge = maxEdge, quality = quality)?.let { return it }
            // Never hide the overlay when waiting briefly for the first virtual-display frame.
            delay(150L)
            virtualDisplay.latestFrameJpeg(maxEdge = maxEdge, quality = quality)?.let { return it }
            return ScreenshotCaptureResult(omittedReason = "virtual_frame_pending")
        }
        return a11y.captureScreenshotJpeg(displayId = displayId, maxEdge = maxEdge, quality = quality)
    }

    override suspend fun tapRef(ref: String, snapshotId: String): ActionOutcome = withContext(Dispatchers.IO) {
        val a11y = requireA11y()
        val entry = resolveNode(ref, snapshotId) ?: return@withContext ActionOutcome.Failed("ref_expired:$ref")
        val clickable = findClickableAncestor(entry.node)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            delay(220)
            return@withContext ActionOutcome.Ok("Clicked $ref (${entry.element.name.take(24)})")
        }
        val rect = Rect().also { entry.node.getBoundsInScreen(it) }
        val ok = dispatchTap(a11y, rect.centerX(), rect.centerY())
        delay(220)
        if (ok) ActionOutcome.Ok("Tapped $ref at (${rect.centerX()},${rect.centerY()})")
        else ActionOutcome.Failed("Failed to tap $ref")
    }

    override suspend fun tapXY(x: Int, y: Int): ActionOutcome = withContext(Dispatchers.IO) {
        val ok = dispatchTap(requireA11y(), x, y)
        delay(220)
        if (ok) ActionOutcome.Ok("Tapped ($x,$y)") else ActionOutcome.Failed("Gesture cancelled")
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionOutcome = withContext(Dispatchers.IO) {
        val entry = resolveNode(ref, snapshotId) ?: return@withContext ActionOutcome.Failed("ref_expired:$ref")
        val x = entry.element.centerX
        val y = entry.element.centerY
        val ok = if (isOnVirtualDisplay()) {
            val sh = shellProvider()
            val vdId = activeDisplayId
            if (sh != null && runCatching { sh.injectTap(vdId, x, y) }.getOrDefault(false)) {
                delay(80)
                runCatching { sh.injectTap(vdId, x, y) }.getOrDefault(false)
            } else {
                false
            }
        } else {
            requireA11y().doubleTap(activeDisplayId, x.toFloat(), y.toFloat())
        }
        delay(220)
        if (ok) ActionOutcome.Ok("Double tapped $ref") else ActionOutcome.Failed("Double tap cancelled")
    }

    override suspend fun longPressRef(ref: String, snapshotId: String): ActionOutcome = withContext(Dispatchers.IO) {
        val entry = resolveNode(ref, snapshotId) ?: return@withContext ActionOutcome.Failed("ref_expired:$ref")
        val x = entry.element.centerX
        val y = entry.element.centerY
        val ok = if (isOnVirtualDisplay()) {
            val sh = shellProvider()
            if (sh != null) {
                runCatching {
                    sh.injectStroke(activeDisplayId, intArrayOf(x, x), intArrayOf(y, y), longArrayOf(0L, 650L))
                }.getOrDefault(false)
            } else {
                false
            }
        } else {
            requireA11y().longPress(activeDisplayId, x.toFloat(), y.toFloat())
        }
        delay(250)
        if (ok) ActionOutcome.Ok("Long pressed $ref") else ActionOutcome.Failed("Long press cancelled")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionOutcome = withContext(Dispatchers.IO) {
        val ok = dispatchSwipe(requireA11y(), x1, y1, x2, y2, durationMs)
        delay(260)
        if (ok) ActionOutcome.Ok("Swiped ($x1,$y1)->($x2,$y2)") else ActionOutcome.Failed("Swipe cancelled")
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionOutcome = withContext(Dispatchers.IO) {
        val a11y = requireA11y()
        val entry = if (ref != null && snapshotId != null) resolveNode(ref, snapshotId) else null
        val scrollable = entry?.node?.let { findScrollableAncestor(it) }
        if (scrollable != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) {
                delay(260)
                return@withContext ActionOutcome.Ok("Scrolled ${if (down) "down" else "up"}")
            }
        }
        val w = if (isOnVirtualDisplay()) virtualDisplay.currentWidth else context.resources.displayMetrics.widthPixels
        val h = if (isOnVirtualDisplay()) virtualDisplay.currentHeight else context.resources.displayMetrics.heightPixels
        val cx = w / 2
        val (y1, y2) = if (down) (h * 0.72f).roundToInt() to (h * 0.28f).roundToInt() else (h * 0.28f).roundToInt() to (h * 0.72f).roundToInt()
        val ok = dispatchSwipe(a11y, cx, y1, cx, y2, 360L)
        delay(260)
        if (ok) ActionOutcome.Ok("Swiped to scroll ${if (down) "down" else "up"}") else ActionOutcome.Failed("Scroll failed")
    }

    override suspend fun inputText(
        ref: String?,
        snapshotId: String?,
        text: String,
        clearFirst: Boolean,
    ): ActionOutcome = withContext(Dispatchers.IO) {
        val a11y = requireA11y()
        val node = resolveEditableNode(ref, snapshotId)
            ?: return@withContext ActionOutcome.Failed("No editable field found")
        val ok = a11y.setNodeText(node, text, clearFirst)
        delay(180)
        if (ok) ActionOutcome.Ok("Entered text (${text.length} chars)")
        else ActionOutcome.Failed("Failed to set text on target field")
    }

    override suspend fun fillVerificationCode(
        ref: String?,
        snapshotId: String?,
        otpId: String?,
        appHint: String?,
        sinceMs: Long,
        clearFirst: Boolean,
    ): ActionOutcome = withContext(Dispatchers.IO) {
        val a11y = requireA11y()
        val node = resolveEditableNode(ref, snapshotId)
            ?: return@withContext ActionOutcome.Failed("No editable field found for verification code")
        val otp = notifications.consumeOtp(
            windowMs = 180_000L,
            sinceMs = sinceMs,
            appHint = appHint,
            otpId = otpId,
            consume = true,
        ) ?: return@withContext ActionOutcome.Failed("No unconsumed verification code found in notifications")
        val ok = a11y.setNodeText(node, otp.code, clearFirst)
        delay(180)
        if (ok) ActionOutcome.Ok("Filled verification code from ${otp.senderOrTitle} (${otp.packageName})")
        else ActionOutcome.Failed("Failed to fill verification code into target field")
    }

    override suspend fun pressBack(): ActionOutcome = withContext(Dispatchers.IO) {
        val ok = if (isOnVirtualDisplay()) {
            val sh = shellProvider()
            sh != null && runCatching { sh.injectKey(activeDisplayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false)
        } else {
            requireA11y().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        delay(220)
        if (ok) ActionOutcome.Ok("Pressed Back") else ActionOutcome.Failed("Back action failed")
    }

    override suspend fun pressHome(): ActionOutcome = withContext(Dispatchers.IO) {
        if (isOnVirtualDisplay()) {
            return@withContext ActionOutcome.Ok("Background virtual display keeps main Home screen untouched", uiChanged = false)
        }
        val ok = requireA11y().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(250)
        if (ok) ActionOutcome.Ok("Pressed Home") else ActionOutcome.Failed("Home action failed")
    }

    override suspend fun pressRecents(): ActionOutcome = withContext(Dispatchers.IO) {
        if (isOnVirtualDisplay()) {
            return@withContext ActionOutcome.Ok("Background virtual display keeps main Recents untouched", uiChanged = false)
        }
        val ok = requireA11y().performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        delay(250)
        if (ok) ActionOutcome.Ok("Opened Recents") else ActionOutcome.Failed("Recents action failed")
    }

    override suspend fun launchApp(packageOrName: String, preferVirtualDisplay: Boolean): ActionOutcome = withContext(Dispatchers.IO) {
        val query = packageOrName.trim()
        if (query.isEmpty()) {
            return@withContext ActionOutcome.Failed("launch_app requires params.app")
        }
        if (query.contains("://")) {
            return@withContext ActionOutcome.Failed("URI scheme deep links are forbidden; launch apps by package or app name only")
        }
        val pm = context.packageManager
        val resolvedPkg = if (pm.getLaunchIntentForPackage(query) != null) {
            query
        } else {
            listLaunchableApps().firstOrNull {
                it.label.equals(query, ignoreCase = true) ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.equals(query, ignoreCase = true)
            }?.packageName
        } ?: return@withContext ActionOutcome.Failed("App not found: $query")

        var launchedVirtual = false
        var fallbackNote = ""
        val shell = shellProvider()
        if (preferVirtualDisplay && shell != null) {
            val vdId = virtualDisplay.ensureCreated()
            if (vdId != null) {
                val code = runCatching { shell.startApp(vdId, resolvedPkg) }.getOrDefault(PhoneControlShellService.START_ERROR)
                if (code == PhoneControlShellService.START_OK) {
                    updateActiveDisplay(vdId)
                    launchedVirtual = true
                    // Wait briefly for the first window or frame on the virtual display.
                    val a11y = PhoneControlAccessibilityService.instance.value
                    repeat(6) {
                        delay(200)
                        if (virtualDisplay.previewFrames.value != null || (a11y != null && a11y.windowsOnDisplay(vdId).isNotEmpty())) {
                            return@repeat
                        }
                    }
                } else if (code == PhoneControlShellService.START_IN_USE_ON_MAIN) {
                    fallbackNote = ", fallback=in_use_on_main"
                } else {
                    fallbackNote = ", fallback=shell_start_code_$code"
                }
            }
        }

        if (!launchedVirtual) {
            val intent = pm.getLaunchIntentForPackage(resolvedPkg)
                ?: return@withContext ActionOutcome.Failed("App has no launcher activity: $resolvedPkg")
            val _ = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(intent) }
            started.exceptionOrNull()?.let {
                return@withContext ActionOutcome.Failed("Launch failed: ${it.message}")
            }
            updateActiveDisplay(Display.DEFAULT_DISPLAY)
            delay(700)
        }
        ActionOutcome.Ok("Launched $resolvedPkg (virtual=$launchedVirtual$fallbackNote)")
    }

    override suspend fun listLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { info ->
                LaunchableApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { info.activityInfo.packageName },
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun findLatestOtp(
        windowMs: Long,
        sinceMs: Long,
        appHint: String?,
        otpId: String?,
    ): ExtractedOtp? = notifications.findLatestOtp(windowMs = windowMs, sinceMs = sinceMs, appHint = appHint, otpId = otpId)

    override suspend fun consumeOtp(
        windowMs: Long,
        sinceMs: Long,
        appHint: String?,
        otpId: String?,
        consume: Boolean,
    ): ExtractedOtp? = notifications.consumeOtp(
        windowMs = windowMs,
        sinceMs = sinceMs,
        appHint = appHint,
        otpId = otpId,
        consume = consume,
    )

    override suspend fun waitMs(ms: Long) {
        delay(ms)
    }

    override fun clearCaches() {
        nodeCache.clear()
        updateActiveDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun releaseVirtualDisplay() {
        virtualDisplay.release()
        updateActiveDisplay(Display.DEFAULT_DISPLAY)
    }

    private suspend fun dispatchTap(a11y: PhoneControlAccessibilityService, x: Int, y: Int): Boolean {
        if (isOnVirtualDisplay()) {
            val sh = shellProvider()
            if (sh != null) {
                return runCatching { sh.injectTap(activeDisplayId, x, y) }.getOrDefault(false)
            }
        }
        return a11y.tap(activeDisplayId, x.toFloat(), y.toFloat())
    }

    private suspend fun dispatchSwipe(
        a11y: PhoneControlAccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): Boolean {
        if (isOnVirtualDisplay()) {
            val sh = shellProvider()
            if (sh != null) {
                val dur = durationMs.coerceIn(80L, 2_000L)
                val steps = 10
                val xs = IntArray(steps + 1) { i -> x1 + ((x2 - x1) * i) / steps }
                val ys = IntArray(steps + 1) { i -> y1 + ((y2 - y1) * i) / steps }
                val times = LongArray(steps + 1) { i -> (dur * i) / steps }
                return runCatching { sh.injectStroke(activeDisplayId, xs, ys, times) }.getOrDefault(false)
            }
        }
        return a11y.swipe(activeDisplayId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
    }

    private fun resolveNode(ref: String, snapshotId: String): CollectedNode? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        val withAt = if (trimmed.startsWith("@")) trimmed else "@$trimmed"
        val withoutAt = trimmed.removePrefix("@")
        val nodes = nodeCache[snapshotId] ?: nodeCache.values.firstOrNull() ?: return null
        return nodes.firstOrNull { it.element.ref == trimmed }
            ?: nodes.firstOrNull {
                it.element.ref.equals(withAt, ignoreCase = true) ||
                    it.element.ref.equals(withoutAt, ignoreCase = true) ||
                    it.element.ref.removePrefix("@").equals(withoutAt, ignoreCase = true)
            }
    }

    private fun resolveEditableNode(ref: String?, snapshotId: String?): AccessibilityNodeInfo? {
        if (ref != null && snapshotId != null) {
            val matched = resolveNode(ref, snapshotId)?.node
            if (matched != null) {
                if (matched.isEditable) return matched
                findEditableDescendant(matched)?.let { return it }
            }
        }
        val nodes = snapshotId?.let { nodeCache[it] } ?: nodeCache.values.firstOrNull()
        return nodes?.firstOrNull { it.element.editable && it.element.focused }?.node
            ?: nodes?.firstOrNull { it.element.editable }?.node
    }

    private fun findEditableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 32) {
            val cur = queue.removeFirst()
            if (cur.isEditable) return cur
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur.isScrollable) return cur
            cur = cur.parent
        }
        return null
    }

    private companion object {
        const val EMPTY_TREE_RETRY_COUNT = 2
        const val EMPTY_TREE_RETRY_DELAY_MS = 350L
    }
}
