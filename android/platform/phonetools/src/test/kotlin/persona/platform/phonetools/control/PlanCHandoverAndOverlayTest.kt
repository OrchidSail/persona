package persona.platform.phonetools.control

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.platform.phonetools.control.shell.PhoneControlShellService
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w400dp-h800dp-xhdpi")
class PlanCHandoverAndOverlayTest {

    private class FakePlanCScreenPort(
        var snapshot: ScreenSnapshot,
        var usingVd: Boolean = true,
    ) : ScreenPort {
        var syncedDisplayIds = mutableListOf<Int>()
        override suspend fun captureSnapshot(includeScreenshot: Boolean, screenshotMaxEdge: Int, screenshotQuality: Int) = snapshot
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionOutcome.Ok("ok")
        override suspend fun tapXY(x: Int, y: Int) = ActionOutcome.Ok("ok")
        override suspend fun doubleTapRef(ref: String, snapshotId: String) = ActionOutcome.Ok("ok")
        override suspend fun longPressRef(ref: String, snapshotId: String) = ActionOutcome.Ok("ok")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionOutcome.Ok("ok")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionOutcome.Ok("ok")
        override suspend fun inputText(ref: String?, snapshotId: String?, text: String, clearFirst: Boolean) = ActionOutcome.Ok("ok")
        override suspend fun pressBack() = ActionOutcome.Ok("ok")
        override suspend fun pressHome() = ActionOutcome.Ok("ok")
        override suspend fun pressRecents() = ActionOutcome.Ok("ok")
        override suspend fun launchApp(packageOrName: String, preferVirtualDisplay: Boolean) = ActionOutcome.Ok("ok")
        override suspend fun listLaunchableApps(): List<LaunchableApp> = emptyList()
        override suspend fun findLatestOtp(windowMs: Long, sinceMs: Long, appHint: String?, otpId: String?): ExtractedOtp? = null
        override suspend fun consumeOtp(windowMs: Long, sinceMs: Long, appHint: String?, otpId: String?, consume: Boolean): ExtractedOtp? = null
        override suspend fun fillVerificationCode(ref: String?, snapshotId: String?, otpId: String?, appHint: String?, sinceMs: Long, clearFirst: Boolean) = ActionOutcome.Ok("ok")
        override suspend fun waitMs(ms: Long) = Unit
        override fun syncActiveDisplay(displayId: Int) {
            val _ = syncedDisplayIds.add(displayId)
            usingVd = displayId > 0
        }
        override fun isActuallyUsingVirtualDisplay(): Boolean = usingVd
    }

    private fun readyProvider() = DeviceControlStateProvider {
        DeviceControlSnapshot(
            masterEnabled = true,
            interactive = true,
            keyguardShowing = false,
            deviceLocked = false,
            secure = false,
            helperAvailable = true,
            userUnlocked = true,
            virtualDisplayActive = true,
            overlayActive = true,
            residentConnection = true,
            permissions = PhoneControlPermissions(
                accessibility = true,
                notificationListener = true,
                overlay = true,
                screenCapture = false,
                batteryOptimizationIgnored = true,
            ),
            safetyPolicy = SafetyGateConfig(blockRealPayment = true),
        )
    }

    @Test
    fun paymentGateAndWaitingOnPhoneNeverAutoStealForeground() = runTest {
        val snap = ScreenSnapshot(
            snapshotId = "snap-pay",
            displayId = 2,
            width = 1080,
            height = 2340,
            topPackage = "uni.MyCUniqSGDev",
            elements = listOf(
                UiElement(
                    ref = "@e1",
                    role = "Button",
                    name = "立即支付", // i18n-ignore: test third-party payment button
                    centerX = 540,
                    centerY = 1800,
                    bounds = listOf(100, 1700, 980, 1900),
                    clickable = true,
                ),
            ),
            visibleTexts = listOf("收银台", "立即支付"), // i18n-ignore: test third-party screen
            hasPasswordField = false,
            uiHash = 99L,
            usingVirtualDisplay = true,
            imeVisible = true,
            windowDiagnostics = "ok",
        )
        var fullscreenEntries = 0
        val attentionReqs = mutableListOf<HandoverRequest>()
        val overlays = mutableListOf<OverlayTaskSummary>()

        val executor = PhoneControlExecutor(
            screenFactory = { FakePlanCScreenPort(snap, usingVd = true) },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig(blockRealPayment = true) },
            deviceStateProvider = readyProvider(),
            onOverlayUpdate = { val _ = overlays.add(it) },
            virtualDisplayActiveProvider = { true },
            protectedFrameProvider = { false },
            onEnterFullscreenHandover = { fullscreenEntries++ },
            onAttentionRequested = { val _ = attentionReqs.add(it) },
        )

        // observe reports ime_visible=true
        val obsRes = executor.execute(PhoneControlCall("c-obs", "", "observe", 10_000L, buildJsonObject { put("task_id", "t-plan-c"); put("step", 1) }))
        assertTrue(obsRes.ok)
        val obsPayload = requireNotNull(obsRes.result)
        assertTrue(obsPayload["ime_visible"]!!.jsonPrimitive.boolean)
        assertEquals("ok", obsPayload["window_diagnostics"]!!.jsonPrimitive.content)

        // Action hitting payment gate MUST NOT auto-steal foreground!
        val payRes = executor.execute(
            PhoneControlCall(
                "c-pay",
                "idem-pay",
                "action",
                10_000L,
                buildJsonObject {
                    put("task_id", "t-plan-c")
                    put("step", 2)
                    put("action", "tap")
                    putJsonObject("params") { put("ref", "@e1") }
                },
            ),
        )
        assertTrue(payRes.ok)
        assertTrue(payRes.result!!["gated"]!!.jsonPrimitive.boolean)
        assertEquals(0, fullscreenEntries)
        assertEquals(1, attentionReqs.size)
        assertEquals(HandoverPhase.PENDING_ATTENTION, executor.controlState.value.handoverPhase)
        assertEquals(HandoverPhase.PENDING_ATTENTION, overlays.last().handoverPhase)

        // User taps capsule -> enters Mode A INLINE (still ZERO fullscreen steals!)
        assertTrue(executor.openHandoverFromUser())
        assertEquals(0, fullscreenEntries)
        assertEquals(HandoverPhase.INLINE, executor.controlState.value.handoverPhase)

        // User clicks "Switch to fullscreen" -> NOW enters Mode B FULLSCREEN!
        assertTrue(executor.beginFullscreenInteractionFromUser())
        assertEquals(1, fullscreenEntries)
        assertEquals(HandoverPhase.FULLSCREEN, executor.controlState.value.handoverPhase)
    }

    @Test
    fun modeAHandbackZeroRelocationAndModeBHandbackWithDegradedFallbackAndTerminalCleanup() {
        val overlays = mutableListOf<OverlayTaskSummary>()
        var handbackPrevPhases = mutableListOf<HandoverPhase>()
        var terminalCleanups = mutableListOf<Triple<String, String, String?>>()
        var degradeNext = false

        val executor = PhoneControlExecutor(
            screenFactory = { error("unused") },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig() },
            deviceStateProvider = readyProvider(),
            onOverlayUpdate = { val _ = overlays.add(it) },
            virtualDisplayActiveProvider = { !degradeNext },
            protectedFrameProvider = { false },
            onEnterFullscreenHandover = {},
            onHandbackControl = { prev, done ->
                val _ = handbackPrevPhases.add(prev)
                done(degradeNext)
            },
            onTerminalCleanup = { st, sum, pkg -> val _ = terminalCleanups.add(Triple(st, sum, pkg)) },
        )

        // Start session & trigger waiting_on_phone
        val _ = executor.syncSession(
            buildJsonObject {
                put("task_id", "t-hb")
                put("status", "waiting_on_phone")
                put("step", 4)
                put("summary", "请完成拼图验证") // i18n-ignore: test summary
                put("app", "uni.MyCUniqSGDev")
            },
        )
        assertEquals(HandoverPhase.PENDING_ATTENTION, executor.controlState.value.handoverPhase)

        // Mode A inline -> handback -> zero relocation, resumedFromPhone=true
        assertTrue(executor.openHandoverFromUser())
        assertEquals(HandoverPhase.INLINE, executor.controlState.value.handoverPhase)
        assertTrue(executor.resumeFromPhoneOverlay())
        assertEquals(HandoverPhase.INLINE, handbackPrevPhases.last())
        assertTrue(executor.controlState.value.resumedFromPhone)
        assertEquals(HandoverPhase.NONE, executor.controlState.value.handoverPhase)
        assertFalse(overlays.last().keepScreenOnForeground)

        // Mode B fullscreen -> handback refused (degraded=true) -> keeps screen on in foreground
        val _ = executor.syncSession(buildJsonObject { put("task_id", "t-hb"); put("status", "waiting_on_phone"); put("step", 5); put("requires_foreground", true) })
        assertTrue(executor.openHandoverFromUser())
        assertEquals(HandoverPhase.FULLSCREEN, executor.controlState.value.handoverPhase)
        degradeNext = true
        assertTrue(executor.resumeFromPhoneOverlay())
        assertEquals(HandoverPhase.FULLSCREEN, handbackPrevPhases.last())
        assertTrue(executor.controlState.value.resumedFromPhone)
        assertTrue(overlays.last().keepScreenOnForeground)

        // Terminal sync triggers terminal cleanup with target package
        val _ = executor.syncSession(buildJsonObject { put("task_id", "t-hb"); put("status", "done"); put("step", 6); put("summary", "完成") })
        assertEquals(1, terminalCleanups.size)
        assertEquals("done", terminalCleanups[0].first)
        assertEquals("uni.MyCUniqSGDev", terminalCleanups[0].third)
    }

    @Test
    fun shellReturnTokenParsingAndMonotonicTouchTimestampSanitization() {
        val raw = PhoneControlShellService.formatMainForegroundTask(42, "com.tencent.mm", false)
        val parsed = MainScreenReturnToken.parseCaptured(raw)
        assertEquals(42, parsed.prevMainTaskId)
        assertEquals("com.tencent.mm", parsed.prevMainPackage)
        assertFalse(parsed.wasHome)

        val homeRaw = PhoneControlShellService.formatMainForegroundTask(1, "com.sec.android.app.launcher", true)
        val parsedHome = MainScreenReturnToken.parseCaptured(homeRaw)
        assertTrue(parsedHome.wasHome)

        // Monotonicity check: backward client event time MUST NOT move below lastEventTime
        val t1 = PhoneControlShellService.sanitizeStreamEventTime(
            serverDownTime = 10_000L,
            lastEventTime = 10_050L,
            clientDownTime = 5_000L,
            clientEventTime = 5_010L, // earlier than lastEventTime offset!
            now = 10_060L,
        )
        assertEquals(10_050L, t1)

        val t2 = PhoneControlShellService.sanitizeStreamEventTime(
            serverDownTime = 10_000L,
            lastEventTime = 10_050L,
            clientDownTime = 5_000L,
            clientEventTime = 5_080L,
            now = 10_090L,
        )
        assertEquals(10_080L, t2)
    }

    @Test
    fun notificationsChannelsAndBridgeRouting() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notifs = PhoneControlNotifications(app)
        var inlineTask = ""
        var fsTask = ""
        var cancelTask = ""
        val handler = object : PhoneControlNotificationHandler {
            override fun onNotificationOpenInline(taskId: String) { inlineTask = taskId }
            override fun onNotificationOpenFullscreen(taskId: String) { fsTask = taskId }
            override fun onNotificationCancelTask(taskId: String) { cancelTask = taskId }
        }
        PhoneControlNotificationBridge.register(handler)
        try {
            val req = HandoverRequest(
                taskId = "t-n1",
                waitingFor = "hand_to_user",
                reason = "请完成滑块验证", // i18n-ignore: test notification text
                appPackage = "uni.MyCUniqSGDev",
                requiresForeground = false,
            )
            val _ = notifs.showAttentionBanner(req, appLabel = "MyCUniq", canInline = true)
            val nm = app.getSystemService(NotificationManager::class.java)
            assertNotNull(nm.getNotificationChannel(PhoneControlNotifications.CHANNEL_ATTENTION))
            assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(PhoneControlNotifications.CHANNEL_ATTENTION).importance)

            PhoneControlNotificationBridge.dispatch(PhoneControlNotifications.ACTION_OPEN_INLINE, "t-n1")
            PhoneControlNotificationBridge.dispatch(PhoneControlNotifications.ACTION_OPEN_FULLSCREEN, "t-n1")
            PhoneControlNotificationBridge.dispatch(PhoneControlNotifications.ACTION_CANCEL_TASK, "t-n1")
            assertEquals("t-n1", inlineTask)
            assertEquals("t-n1", fsTask)
            assertEquals("t-n1", cancelTask)

            notifs.cancelAttentionBanner()
            val _ = notifs.showResultNotification("done", "已完成") // i18n-ignore: test summary
            assertNotNull(nm.getNotificationChannel(PhoneControlNotifications.CHANNEL_RESULT))
        } finally {
            PhoneControlNotificationBridge.unregister(handler)
        }
    }

    @Test
    fun overlayDefaultsToMiniPillTracksVirtualDisplayTruthAndRendersAllFourModes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val density = app.resources.displayMetrics.density
        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
        )

        // Default mode on active task is MINI_PILL (minimized=true!)
        val runningSummary = OverlayTaskSummary("t1", "running", 5, "点击登录", isActive = true, usingVirtualDisplay = true)
        assertEquals(OverlayDisplayMode.MINI_PILL, overlay.resolveDisplayMode(runningSummary))

        // Verify usingVirtualDisplayMode is NOT sticky: flipping to false updates it immediately!
        overlay.update(runningSummary)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlay.usingVirtualDisplayMode)

        overlay.update(runningSummary.copy(usingVirtualDisplay = false, keepScreenOnForeground = true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(overlay.usingVirtualDisplayMode)

        // Verify display mode routing for all phases
        val attentionSummary = runningSummary.copy(status = "waiting_on_phone", isWaitingOnPhone = true, handoverPhase = HandoverPhase.PENDING_ATTENTION, targetAppLabel = "MyCUniq")
        val inlineSummary = attentionSummary.copy(handoverPhase = HandoverPhase.INLINE)
        val fullscreenSummary = attentionSummary.copy(handoverPhase = HandoverPhase.FULLSCREEN)
        assertEquals(OverlayDisplayMode.MINI_PILL, overlay.resolveDisplayMode(attentionSummary))
        assertEquals(OverlayDisplayMode.INLINE_INTERACTIVE, overlay.resolveDisplayMode(inlineSummary))
        assertEquals(OverlayDisplayMode.FULLSCREEN_BAR, overlay.resolveDisplayMode(fullscreenSummary))

        // Render pixel-verified PNG screenshots of all new Plan C UI modes
        val outDir = File(System.getProperty("persona.shots") ?: "/tmp/build22-shots").also { it.mkdirs() }
        val screenW = (400 * density).toInt()
        val screenH = (800 * density).toInt()

        fun saveShot(name: String, view: View, wPx: Int, hPx: Int, dx: Float, dy: Float, fullScreen: Boolean = false) {
            val wSpec = View.MeasureSpec.makeMeasureSpec(wPx, if (wPx > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST)
            val hSpec = View.MeasureSpec.makeMeasureSpec(hPx, if (hPx > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertTrue("$name measured width > 0", view.measuredWidth > 0)
            assertTrue("$name measured height > 0", view.measuredHeight > 0)
            val bmp = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.parseColor("#E5E7EB"))
            val p = Paint().apply { color = Color.parseColor("#6B7280"); textSize = 14f * density; isAntiAlias = true }
            c.drawText("主屏应用背景 (400×800dp)", 20f * density, 48f * density, p) // i18n-ignore: test screenshot watermark
            c.save()
            if (!fullScreen) c.translate(dx, dy)
            view.draw(c)
            c.restore()
            File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }

        val pillRunning = overlay.buildMiniPillForTest(app, runningSummary)
        saveShot("plan-c-mini-pill-running.png", pillRunning, (124 * density).toInt(), (32 * density).toInt(), screenW - 132 * density, 104 * density)

        val pillAttention = overlay.buildMiniPillForTest(app, attentionSummary)
        saveShot("plan-c-mini-pill-attention.png", pillAttention, (148 * density).toInt(), (32 * density).toInt(), screenW - 156 * density, 104 * density)

        val inlineModal = overlay.buildInlineOverlayForTest(app, inlineSummary, protectedFrame = false)
        saveShot("plan-c-inline-modal.png", inlineModal, screenW, screenH, 0f, 0f, fullScreen = true)

        val inlineProtected = overlay.buildInlineOverlayForTest(app, inlineSummary, protectedFrame = true)
        saveShot("plan-c-inline-protected.png", inlineProtected, screenW, screenH, 0f, 0f, fullScreen = true)

        val fsBar = overlay.buildFullscreenBarForTest(app, fullscreenSummary)
        fsBar.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - (24 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((40 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        val centeredDx = ((screenW - fsBar.measuredWidth) / 2f).coerceAtLeast(12f * density)
        saveShot("plan-c-fullscreen-bar.png", fsBar, fsBar.measuredWidth, (40 * density).toInt(), centeredDx, 36 * density)

        overlay.hide()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
