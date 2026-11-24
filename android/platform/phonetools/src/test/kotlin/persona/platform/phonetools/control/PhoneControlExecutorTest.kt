package persona.platform.phonetools.control

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.api.PhoneControlCall
import persona.core.contracts.api.PhoneControlPermissions

class PhoneControlExecutorTest {

    private class FakeScreenPort(
        var snapshot: ScreenSnapshot = sampleSnapshot(),
        val notifications: NotificationBuffer = NotificationBuffer(),
    ) : ScreenPort {
        var tapRefCount = 0
        var tapXYCount = 0
        var inputTextCalls = mutableListOf<String>()
        var filledCodes = mutableListOf<String>()
        var launchedApps = mutableListOf<Pair<String, Boolean>>()
        var virtualDisplayReleased = 0

        override suspend fun captureSnapshot(
            includeScreenshot: Boolean,
            screenshotMaxEdge: Int,
            screenshotQuality: Int,
        ): ScreenSnapshot = snapshot

        override suspend fun tapRef(ref: String, snapshotId: String): ActionOutcome {
            tapRefCount++
            return ActionOutcome.Ok("Tapped $ref")
        }

        override suspend fun tapXY(x: Int, y: Int): ActionOutcome {
            tapXYCount++
            return ActionOutcome.Ok("Tapped ($x,$y)")
        }

        override suspend fun doubleTapRef(ref: String, snapshotId: String) = ActionOutcome.Ok("Double tapped $ref")
        override suspend fun longPressRef(ref: String, snapshotId: String) = ActionOutcome.Ok("Long pressed $ref")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionOutcome.Ok("Swiped")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionOutcome.Ok("Scrolled")

        override suspend fun inputText(ref: String?, snapshotId: String?, text: String, clearFirst: Boolean): ActionOutcome {
            val _ = inputTextCalls.add(text)
            return ActionOutcome.Ok("Entered text")
        }

        override suspend fun pressBack() = ActionOutcome.Ok("Back")
        override suspend fun pressHome() = ActionOutcome.Ok("Home")
        override suspend fun pressRecents() = ActionOutcome.Ok("Recents")

        override suspend fun launchApp(packageOrName: String, preferVirtualDisplay: Boolean): ActionOutcome {
            val _ = launchedApps.add(packageOrName to preferVirtualDisplay)
            return ActionOutcome.Ok("Launched $packageOrName")
        }

        override suspend fun listLaunchableApps(): List<LaunchableApp> =
            listOf(LaunchableApp("com.example.shop", "Shop"))

        override suspend fun findLatestOtp(windowMs: Long, sinceMs: Long, appHint: String?, otpId: String?): ExtractedOtp? =
            notifications.findLatestOtp(windowMs, sinceMs, appHint, otpId)

        override suspend fun consumeOtp(
            windowMs: Long,
            sinceMs: Long,
            appHint: String?,
            otpId: String?,
            consume: Boolean,
        ): ExtractedOtp? = notifications.consumeOtp(windowMs, sinceMs, appHint, otpId, consume)

        override suspend fun fillVerificationCode(
            ref: String?,
            snapshotId: String?,
            otpId: String?,
            appHint: String?,
            sinceMs: Long,
            clearFirst: Boolean,
        ): ActionOutcome {
            val otp = notifications.consumeOtp(appHint = appHint, sinceMs = sinceMs, otpId = otpId, consume = true)
                ?: return ActionOutcome.Failed("No verification code found")
            val _ = filledCodes.add(otp.code)
            return ActionOutcome.Ok("Filled verification code (${otp.redactedPreview})")
        }

        override suspend fun waitMs(ms: Long) = Unit
        override fun releaseVirtualDisplay() {
            virtualDisplayReleased++
        }
    }

    private fun sampleDeviceState(masterEnabled: Boolean = true) = DeviceControlStateProvider {
        DeviceControlSnapshot(
            masterEnabled = masterEnabled,
            interactive = true,
            keyguardShowing = false,
            deviceLocked = false,
            secure = true,
            helperAvailable = true,
            userUnlocked = true,
            virtualDisplayActive = false,
            overlayActive = false,
            residentConnection = true,
            permissions = PhoneControlPermissions(
                accessibility = true,
                notificationListener = true,
                overlay = true,
                screenCapture = false,
                batteryOptimizationIgnored = true,
            ),
            safetyPolicy = SafetyGateConfig(),
        )
    }

    @Test
    fun masterSwitchOffReturnsControlDisabledWithoutTouchingScreen() = runTest {
        var factoryCalled = false
        val executor = PhoneControlExecutor(
            screenFactory = {
                factoryCalled = true
                FakeScreenPort()
            },
            masterEnabledProvider = { false },
            safetyPolicyProvider = { SafetyGateConfig() },
            deviceStateProvider = sampleDeviceState(masterEnabled = false),
        )
        val res = executor.execute(testCall("c1", "observe"))
        assertFalse(res.ok)
        assertEquals("control_disabled", res.error?.code)
        assertFalse(factoryCalled)
    }

    @Test
    fun tapXyCannotBypassPaymentConfirmationGate() = runTest {
        val fake = FakeScreenPort(
            snapshot = ScreenSnapshot(
                snapshotId = "snap-pay",
                displayId = 0,
                width = 1080,
                height = 2400,
                topPackage = "com.example.shop",
                elements = listOf(
                    UiElement(
                        ref = "@e1",
                        role = "button",
                        name = "立即支付", // i18n-ignore: test third-party payment button
                        centerX = 540,
                        centerY = 2100,
                        bounds = listOf(100, 2000, 980, 2200),
                        clickable = true,
                    ),
                ),
                visibleTexts = listOf("收银台", "立即支付"), // i18n-ignore: test third-party payment screen
                hasPasswordField = false,
                uiHash = 42L,
            ),
        )
        val executor = PhoneControlExecutor(
            screenFactory = { fake },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig(blockRealPayment = true) },
            deviceStateProvider = sampleDeviceState(),
        )

        val obs = executor.execute(testCall("obs-1", "observe"))
        assertTrue(obs.ok)

        val tap = executor.execute(
            testCall(
                callId = "act-1",
                op = "action",
                args = buildJsonObject {
                    put("action", "tap_xy")
                    putJsonObject("params") {
                        put("x", 540)
                        put("y", 2100)
                    }
                },
            ),
        )
        assertTrue(tap.ok)
        val data = tap.result!!
        assertFalse(data["ok"]!!.jsonPrimitive.boolean)
        assertTrue(data["gated"]!!.jsonPrimitive.boolean)
        assertEquals("payment_confirmation", data["waiting_for"]!!.jsonPrimitive.content)
        assertEquals(0, fake.tapXYCount)
    }

    @Test
    fun inputTextDoesNotFalseTriggerPaymentGateOnSearchBox() = runTest {
        val fake = FakeScreenPort(
            snapshot = ScreenSnapshot(
                snapshotId = "snap-search",
                displayId = 0,
                width = 1080,
                height = 2400,
                topPackage = "com.example.shop",
                elements = listOf(
                    UiElement(
                        ref = "@e1",
                        role = "input",
                        name = "Search orders",
                        centerX = 540,
                        centerY = 200,
                        bounds = listOf(50, 140, 1030, 260),
                        editable = true,
                    ),
                ),
                visibleTexts = listOf("Search orders"),
                hasPasswordField = false,
                uiHash = 7L,
            ),
        )
        val executor = PhoneControlExecutor(
            screenFactory = { fake },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig(blockRealPayment = true) },
            deviceStateProvider = sampleDeviceState(),
        )
        val _ = executor.execute(testCall("obs-2", "observe"))

        val typed = executor.execute(
            testCall(
                callId = "act-input",
                op = "action",
                args = buildJsonObject {
                    put("action", "input_text")
                    putJsonObject("params") {
                        put("ref", "@e1")
                        put("snapshot_id", "snap-search")
                        put("text", "立即支付教程") // i18n-ignore: test search query containing payment word
                    }
                },
            ),
        )
        assertTrue(typed.ok)
        assertTrue(typed.result!!["ok"]!!.jsonPrimitive.boolean)
        assertFalse(typed.result!!["gated"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("立即支付教程"), fake.inputTextCalls) // i18n-ignore: test assertion
    }

    @Test
    fun launchAppRejectsUriSchemesUnconditionally() = runTest {
        val fake = FakeScreenPort()
        val executor = PhoneControlExecutor(
            screenFactory = { fake },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig(blockRealPayment = false) },
            deviceStateProvider = sampleDeviceState(),
        )
        val res = executor.execute(
            testCall(
                callId = "act-uri",
                op = "action",
                args = buildJsonObject {
                    put("action", "launch_app")
                    putJsonObject("params") {
                        put("app", "alipays://platformapi/startapp?appId=20000067")
                    }
                },
            ),
        )
        assertTrue(res.ok)
        assertFalse(res.result!!["ok"]!!.jsonPrimitive.boolean)
        assertTrue(res.result!!["error"]!!.jsonPrimitive.content.contains("URI scheme"))
        assertTrue(fake.launchedApps.isEmpty())
    }

    @Test
    fun observeRedactsOtpWhileFillVerificationCodeAndOtpConsumeAreOneTimeAndIdempotent() = runTest {
        val fake = FakeScreenPort()
        fake.notifications.record(
            NotificationEntry(
                id = "sms-99",
                packageName = "com.android.mms",
                title = "ShopApp",
                text = "Your verification code is 847291",
                postedAtMs = System.currentTimeMillis(),
            ),
        )
        val executor = PhoneControlExecutor(
            screenFactory = { fake },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig(allowNotificationOtpAutoLogin = true) },
            deviceStateProvider = sampleDeviceState(),
        )

        // observe exposes otp_meta with redacted preview ONLY — never plaintext 847291.
        val obs = executor.execute(testCall("obs-otp", "observe"))
        assertTrue(obs.ok)
        val obsJson = obs.result!!.toString()
        assertFalse("observe must never leak plaintext OTP", obsJson.contains("847291"))
        val otpMeta = obs.result!!["otp_meta"]!!.jsonObject
        assertTrue(otpMeta["available"]!!.jsonPrimitive.boolean)
        assertEquals("sms-99", otpMeta["otp_id"]!!.jsonPrimitive.content)

        // First otp_consume with idempotency_key returns code and consumes it.
        val consume1 = executor.execute(
            testCall(
                callId = "otp-c1",
                op = "otp_consume",
                idempotencyKey = "idem-otp-1",
                args = buildJsonObject { put("otp_id", "sms-99") },
            ),
        )
        assertTrue(consume1.ok)
        assertEquals("847291", consume1.result!!["code"]!!.jsonPrimitive.content)

        // Replay with SAME idempotency_key returns cached response cleanly.
        val replay = executor.execute(
            testCall(
                callId = "otp-c1-retry",
                op = "otp_consume",
                idempotencyKey = "idem-otp-1",
                args = buildJsonObject { put("otp_id", "sms-99") },
            ),
        )
        assertTrue(replay.ok)
        assertEquals("847291", replay.result!!["code"]!!.jsonPrimitive.content)

        // New call with different idempotency_key sees already-consumed OTP -> available=false.
        val consume2 = executor.execute(
            testCall(
                callId = "otp-c2",
                op = "otp_consume",
                idempotencyKey = "idem-otp-2",
                args = buildJsonObject { put("otp_id", "sms-99") },
            ),
        )
        assertTrue(consume2.ok)
        assertFalse(consume2.result!!["available"]!!.jsonPrimitive.boolean)

        // Record a second OTP and fill locally via fill_verification_code without exposing plaintext in response.
        fake.notifications.record(
            NotificationEntry(
                id = "sms-100",
                packageName = "com.android.mms",
                title = "ShopApp",
                text = "Your login code is 319044",
                postedAtMs = System.currentTimeMillis(),
            ),
        )
        val fillRes = executor.execute(
            testCall(
                callId = "act-fill",
                op = "action",
                args = buildJsonObject {
                    put("action", "fill_verification_code")
                    putJsonObject("params") { put("otp_id", "sms-100") }
                },
            ),
        )
        assertTrue(fillRes.ok)
        assertTrue(fillRes.result!!["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("319044"), fake.filledCodes)
        assertFalse("fill_verification_code response must not leak raw code", fillRes.result!!.toString().contains("319044"))
    }

    @Test
    fun overlayCallbacksPreserveLastKnownStepAndUpdateControlFlags() = runTest {
        val overlayUpdates = mutableListOf<OverlayTaskSummary>()
        val lifecycleEvents = mutableListOf<Pair<Boolean, Boolean>>()
        val executor = PhoneControlExecutor(
            screenFactory = { FakeScreenPort() },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig() },
            deviceStateProvider = sampleDeviceState(),
            onOverlayUpdate = { val _ = overlayUpdates.add(it) },
            onSessionLifecycle = { active, terminal -> val _ = lifecycleEvents.add(active to terminal) },
        )

        val start = executor.execute(
            testCall(
                callId = "sess-1",
                op = "session",
                args = buildJsonObject {
                    put("task_id", "task-42")
                    put("status", "running")
                    put("step", 7)
                    put("summary", "Opening cart")
                },
            ),
        )
        assertTrue(start.ok)
        assertEquals(7, overlayUpdates.last().step)
        assertEquals(true to false, lifecycleEvents.last())

        // Pause -> step MUST remain 7 (never reset to 0).
        assertTrue(executor.togglePauseFromOverlay())
        assertEquals(7, overlayUpdates.last().step)
        assertEquals("paused", overlayUpdates.last().status)
        assertTrue(executor.controlState.value.paused)

        // Takeover -> step stays 7.
        assertTrue(executor.requestTakeoverFromOverlay())
        assertEquals(7, overlayUpdates.last().step)
        assertEquals("waiting_on_phone", overlayUpdates.last().status)
        assertTrue(executor.controlState.value.takeoverRequested)

        // Return control -> step stays 7.
        assertTrue(executor.resumeFromPhoneOverlay())
        assertEquals(7, overlayUpdates.last().step)
        assertEquals("running", overlayUpdates.last().status)
        assertTrue(executor.controlState.value.resumedFromPhone)

        // Emergency stop -> step stays 7, triggers terminal lifecycle.
        assertTrue(executor.emergencyStopFromOverlay())
        assertEquals(7, overlayUpdates.last().step)
        assertEquals("cancelled", overlayUpdates.last().status)
        assertTrue(executor.controlState.value.stopRequested)
        assertEquals(false to true, lifecycleEvents.last())

        // Syncing terminal status releases virtual display and clears task.
        val done = executor.execute(
            testCall(
                callId = "sess-2",
                op = "session",
                args = buildJsonObject {
                    put("task_id", "task-42")
                    put("status", "done")
                    put("step", 7)
                    put("summary", "Completed")
                },
            ),
        )
        assertTrue(done.ok)
        assertNull(executor.controlState.value.taskId)
        assertFalse(executor.hasActiveSession)
    }

    @Test
    fun idempotencyDeduplicatesActionRetries() = runTest {
        val fake = FakeScreenPort()
        val executor = PhoneControlExecutor(
            screenFactory = { fake },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig() },
            deviceStateProvider = sampleDeviceState(),
        )
        val _ = executor.execute(testCall("obs-i", "observe"))

        val call1 = testCall(
            callId = "a1",
            op = "action",
            idempotencyKey = "tap-e1-once",
            args = buildJsonObject {
                put("action", "tap")
                putJsonObject("params") {
                    put("ref", "@e1")
                    put("snapshot_id", "snap-1")
                }
            },
        )
        val call2 = call1.copy(callId = "a2")

        val r1 = executor.execute(call1)
        val r2 = executor.execute(call2)
        assertTrue(r1.ok && r2.ok)
        assertEquals(1, fake.tapRefCount)
        assertNotNull(r2.result)
        assertEquals(r1.result!!["detail"], r2.result!!["detail"])
    }

    private companion object {
        fun testCall(
            callId: String,
            op: String,
            args: JsonObject = JsonObject(emptyMap()),
            idempotencyKey: String = "",
            deadlineMs: Long = 30_000L,
        ): PhoneControlCall = PhoneControlCall(
            callId = callId,
            idempotencyKey = idempotencyKey,
            op = op,
            deadlineMs = deadlineMs,
            args = args,
        )
        fun sampleSnapshot() = ScreenSnapshot(
            snapshotId = "snap-1",
            displayId = 0,
            width = 1080,
            height = 2400,
            topPackage = "com.example.shop",
            elements = listOf(
                UiElement(
                    ref = "@e1",
                    role = "button",
                    name = "Cart",
                    centerX = 200,
                    centerY = 300,
                    bounds = listOf(100, 250, 300, 350),
                    clickable = true,
                ),
            ),
            visibleTexts = listOf("Cart"),
            hasPasswordField = false,
            uiHash = 1234L,
        )
    }
}
