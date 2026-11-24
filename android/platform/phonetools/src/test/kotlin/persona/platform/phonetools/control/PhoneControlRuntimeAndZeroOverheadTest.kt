package persona.platform.phonetools.control

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import persona.core.contracts.HubScope
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneControlCall
import persona.core.contracts.api.PhoneControlCancel
import persona.core.contracts.api.PhoneControlPermissions
import persona.core.contracts.api.PhoneControlRegistration
import persona.core.contracts.api.PhoneControlResult
import persona.core.contracts.api.PhoneControlTransport
import persona.platform.phonetools.PhoneToolSettings
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneControlRuntimeAndZeroOverheadTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        PhoneControlGateState.resetForShutdown()
    }

    @After
    fun tearDown() {
        PhoneControlGateState.resetForShutdown()
        scope.cancel()
    }

    private fun fakeConnection(state: MutableStateFlow<ConnectionState>): HubConnection =
        Proxy.newProxyInstance(HubConnection::class.java.classLoader, arrayOf(HubConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> state
                "getEvents" -> emptyFlow<Any>()
                else -> error("unexpected call: ${method.name}")
            }
        } as HubConnection

    private class FakeControlTransport : PhoneControlTransport {
        val callsFlow = MutableSharedFlow<PhoneControlCall>(extraBufferCapacity = 16)
        val cancelsFlow = MutableSharedFlow<PhoneControlCancel>(extraBufferCapacity = 16)
        override val calls = callsFlow
        override val cancels = cancelsFlow
        val registrations = mutableListOf<PhoneControlRegistration>()
        val results = mutableListOf<Pair<String, PhoneControlResult>>()

        override fun discardPending() = Unit

        override fun register(registration: PhoneControlRegistration): Boolean {
            val _ = registrations.add(registration)
            return true
        }

        override fun result(callId: String, result: PhoneControlResult): Boolean {
            val _ = results.add(callId to result)
            return true
        }
    }

    private fun readyDeviceProvider(masterEnabled: () -> Boolean) = DeviceControlStateProvider {
        DeviceControlSnapshot(
            masterEnabled = masterEnabled(),
            interactive = true,
            keyguardShowing = false,
            deviceLocked = false,
            secure = false,
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

    private suspend fun drainMain(waitMs: Long = 30L) {
        delay(waitMs)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(waitMs))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun defaultsToMasterOffWithZeroBackgroundOverheadAndShortCircuit() = runBlocking {
        val connState = MutableStateFlow<ConnectionState>(ConnectionState.Online)
        val transport = FakeControlTransport()
        val settings = PhoneToolSettings(app, HubScope("hub-${UUID.randomUUID()}", "p1"), scope)
        val keepAliveEvents = mutableListOf<Boolean>()

        val runtime = PhoneControlRuntime(
            context = app,
            deviceId = "dev-zero",
            settings = settings,
            connection = fakeConnection(connState),
            transport = transport,
            parentScope = scope,
            onKeepAliveLeaseChanged = { val _ = keepAliveEvents.add(it) },
            deviceStateProviderOverride = readyDeviceProvider { settings.remoteControlEnabled.value },
            overlayHandlerOverride = {},
        )
        drainMain(50)

        assertFalse(settings.remoteControlEnabled.value)
        assertTrue(settings.allowNotificationOtpAutoLogin.value)
        assertTrue(settings.blockRealPayment.value)
        assertFalse(PhoneControlGateState.isMasterEnabled())
        assertFalse(PhoneControlGateState.isOtpIngestionActive())
        assertFalse(runtime.isKeepAliveHeld.value)
        assertFalse(runtime.isOverlayActive)
        assertFalse(runtime.isVirtualDisplayActive)
        assertTrue(keepAliveEvents.isEmpty())
        assertTrue("OFF state should not send register_phone_control", transport.registrations.isEmpty())

        // Notification ingestion short-circuits with zero entries buffered when OFF.
        assertFalse(PhoneControlGateState.isOtpIngestionActive())
        assertNull(PhoneControlGateState.notificationBuffer.findLatestOtp())

        runtime.close()
    }

    @Test
    fun keepAliveLeaseAcquiredOnlyDuringActiveSessionAndReleasedAfterTerminalAndDisconnect() = runBlocking {
        val connState = MutableStateFlow<ConnectionState>(ConnectionState.Online)
        val transport = FakeControlTransport()
        val settings = PhoneToolSettings(app, HubScope("hub-${UUID.randomUUID()}", "p2"), scope)
        val keepAliveEvents = mutableListOf<Boolean>()
        val overlays = mutableListOf<OverlayTaskSummary>()

        val runtime = PhoneControlRuntime(
            context = app,
            deviceId = "dev-life",
            settings = settings,
            connection = fakeConnection(connState),
            transport = transport,
            parentScope = scope,
            onKeepAliveLeaseChanged = { val _ = keepAliveEvents.add(it) },
            deviceStateProviderOverride = readyDeviceProvider { settings.remoteControlEnabled.value },
            overlayHandlerOverride = { val _ = overlays.add(it) },
            terminalReleaseDelayMs = 60L,
            disconnectGraceMs = 80L,
            disconnectPauseLingerMs = 60L,
        )

        // Enable master switch -> registers online, STILL zero keepalive lease when idle.
        settings.setRemoteControlEnabled(true)
        drainMain(80)
        assertTrue(PhoneControlGateState.isMasterEnabled())
        assertTrue(PhoneControlGateState.isOtpIngestionActive())
        assertTrue(transport.registrations.any { it.enabled && it.deviceId == "dev-life" })
        assertFalse("Idle ON state must not acquire keepalive lease", runtime.isKeepAliveHeld.value)
        assertTrue(keepAliveEvents.isEmpty())

        // Start active task session -> acquires keepalive lease.
        val emittedStart = transport.callsFlow.tryEmit(
            PhoneControlCall(
                callId = "s-start",
                idempotencyKey = "",
                op = "session",
                deadlineMs = 15_000L,
                args = buildJsonObject {
                    put("task_id", "t-1")
                    put("status", "running")
                    put("step", 2)
                    put("summary", "Checking page")
                },
            ),
        )
        assertTrue(emittedStart)
        drainMain(60)
        assertTrue(runtime.isKeepAliveHeld.value)
        assertEquals(listOf(true), keepAliveEvents)

        // Terminal status -> releases lease after terminalReleaseDelayMs.
        val emittedDone = transport.callsFlow.tryEmit(
            PhoneControlCall(
                callId = "s-done",
                idempotencyKey = "",
                op = "session",
                deadlineMs = 15_000L,
                args = buildJsonObject {
                    put("task_id", "t-1")
                    put("status", "done")
                    put("step", 2)
                    put("summary", "Finished")
                },
            ),
        )
        assertTrue(emittedDone)
        drainMain(120)
        assertFalse(runtime.isKeepAliveHeld.value)
        assertEquals(listOf(true, false), keepAliveEvents)

        // Start second task, then drop connection beyond grace + linger -> auto pause then auto cleanup.
        val emittedStart2 = transport.callsFlow.tryEmit(
            PhoneControlCall(
                callId = "s-start-2",
                idempotencyKey = "",
                op = "session",
                deadlineMs = 15_000L,
                args = buildJsonObject {
                    put("task_id", "t-2")
                    put("status", "running")
                    put("step", 4)
                    put("summary", "Working")
                },
            ),
        )
        assertTrue(emittedStart2)
        drainMain(50)
        assertTrue(runtime.isKeepAliveHeld.value)

        connState.value = ConnectionState.WaitingForNetwork
        drainMain(220)
        assertTrue("Disconnect grace should publish paused overlay", overlays.any { it.status == "paused" && it.step == 4 })
        assertFalse("Disconnect cleanup should release keepalive lease", runtime.isKeepAliveHeld.value)
        assertFalse(runtime.executor.hasActiveSession)

        // Turning master OFF sends enabled=false registration frame.
        connState.value = ConnectionState.Online
        drainMain(50)
        settings.setRemoteControlEnabled(false)
        drainMain(60)
        assertFalse(PhoneControlGateState.isMasterEnabled())
        assertTrue(transport.registrations.last().enabled.not())

        runtime.close()
    }

    @Test
    fun cancelFrameCancelsInFlightCallAndEmitsCancelledResult() = runBlocking {
        val connState = MutableStateFlow<ConnectionState>(ConnectionState.Online)
        val transport = FakeControlTransport()
        val settings = PhoneToolSettings(app, HubScope("hub-${UUID.randomUUID()}", "p3"), scope)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val hangingScreen = object : ScreenPort {
            override suspend fun captureSnapshot(includeScreenshot: Boolean, screenshotMaxEdge: Int, screenshotQuality: Int): ScreenSnapshot {
                val _ = started.complete(Unit)
                gate.await()
                error("should have been cancelled")
            }

            override suspend fun tapRef(ref: String, snapshotId: String) = ActionOutcome.Ok("")
            override suspend fun tapXY(x: Int, y: Int) = ActionOutcome.Ok("")
            override suspend fun doubleTapRef(ref: String, snapshotId: String) = ActionOutcome.Ok("")
            override suspend fun longPressRef(ref: String, snapshotId: String) = ActionOutcome.Ok("")
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionOutcome.Ok("")
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionOutcome.Ok("")
            override suspend fun inputText(ref: String?, snapshotId: String?, text: String, clearFirst: Boolean) = ActionOutcome.Ok("")
            override suspend fun pressBack() = ActionOutcome.Ok("")
            override suspend fun pressHome() = ActionOutcome.Ok("")
            override suspend fun pressRecents() = ActionOutcome.Ok("")
            override suspend fun launchApp(packageOrName: String, preferVirtualDisplay: Boolean) = ActionOutcome.Ok("")
            override suspend fun listLaunchableApps(): List<LaunchableApp> = emptyList()
            override suspend fun findLatestOtp(windowMs: Long, sinceMs: Long, appHint: String?, otpId: String?): ExtractedOtp? = null
            override suspend fun consumeOtp(windowMs: Long, sinceMs: Long, appHint: String?, otpId: String?, consume: Boolean): ExtractedOtp? = null
            override suspend fun fillVerificationCode(
                ref: String?,
                snapshotId: String?,
                otpId: String?,
                appHint: String?,
                sinceMs: Long,
                clearFirst: Boolean,
            ) = ActionOutcome.Ok("")

            override suspend fun waitMs(ms: Long) = Unit
        }

        val runtime = PhoneControlRuntime(
            context = app,
            deviceId = "dev-cancel",
            settings = settings,
            connection = fakeConnection(connState),
            transport = transport,
            parentScope = scope,
            screenFactoryOverride = { hangingScreen },
            deviceStateProviderOverride = readyDeviceProvider { settings.remoteControlEnabled.value },
            overlayHandlerOverride = {},
        )
        settings.setRemoteControlEnabled(true)
        drainMain(50)

        val _ = transport.callsFlow.tryEmit(
            PhoneControlCall(
                callId = "c-hang",
                idempotencyKey = "",
                op = "observe",
                deadlineMs = 30_000L,
                args = buildJsonObject {},
            ),
        )
        drainMain(20)
        started.await()
        val _ = transport.cancelsFlow.tryEmit(PhoneControlCancel("c-hang", "user_cancelled"))
        drainMain(60)

        val cancelResult = transport.results.firstOrNull { it.first == "c-hang" }?.second
        assertNotNull(cancelResult)
        assertFalse(cancelResult!!.ok)
        assertEquals("cancelled", cancelResult.error?.code)

        runtime.close()
    }

    @Test
    fun screenshotEncoderScalesProportionallyAndEnforcesSizeLimit() {
        val big = Bitmap.createBitmap(1440, 2880, Bitmap.Config.ARGB_8888)
        big.eraseColor(Color.DKGRAY)
        try {
            val encoded = ScreenshotEncoder.encodeJpeg(big, requestedMaxEdge = 1080, requestedQuality = 70)
            val payload = encoded.payload
            assertNotNull(payload)
            assertNull(encoded.omittedReason)
            assertEquals("image/jpeg", payload!!.mimeType)
            assertEquals(1080, payload.height)
            assertEquals(540, payload.width)
            assertTrue(payload.bytes <= ScreenshotEncoder.MAX_BYTES)
            assertTrue(payload.dataBase64.isNotEmpty())

            // Impossibly tiny maxBytes forces size_limit omission after fallback retry.
            val omitted = ScreenshotEncoder.encodeJpeg(big, requestedMaxEdge = 1080, requestedQuality = 70, maxBytes = 10)
            assertNull(omitted.payload)
            assertEquals("size_limit", omitted.omittedReason)
        } finally {
            big.recycle()
        }
    }
}
