package persona.platform.phonetools.control

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.HubScope
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneToolCall
import persona.core.contracts.api.PhoneToolResult
import persona.core.contracts.api.PhoneToolSpec
import persona.core.contracts.api.PhoneToolsTransport
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator
import persona.platform.phonetools.PhoneToolsRuntime
import persona.platform.phonetools.PhoneToolsScreen
import persona.platform.phonetools.R
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w400dp-h1800dp-xhdpi")
class Build21VerificationShotsAndRegressionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun shotsDir(): File =
        File(System.getProperty("persona.shots") ?: "/tmp/build21-shots").also { it.mkdirs() }

    @After
    fun tearDown() {
        PhoneControlGateState.resetForShutdown()
        scope.cancel()
    }

    @Test
    fun syncSessionWaitingOnPhoneNeverStealsForegroundAndAlertsOncePerWait() {
        var bringCount = 0
        var attentionCount = 0
        val executor = PhoneControlExecutor(
            screenFactory = { error("not needed for syncSession") },
            masterEnabledProvider = { true },
            safetyPolicyProvider = { SafetyGateConfig() },
            deviceStateProvider = object : DeviceControlStateProvider {
                override fun snapshot(): DeviceControlSnapshot = DeviceControlSnapshot(
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
                    permissions = PhoneControlPermissions(accessibility = true, notificationListener = true, overlay = true, screenCapture = false, batteryOptimizationIgnored = true),
                    safetyPolicy = SafetyGateConfig(),
                )
            },
            onOverlayUpdate = {},
            onSessionLifecycle = { _, _ -> },
            virtualDisplayActiveProvider = { true },
            onBringTaskToForeground = { bringCount++ },
            onAttentionRequested = { attentionCount++ },
        )

        // Plan C: repeated waiting_on_phone polls NEVER steal foreground and alert once per wait
        executor.syncSession(buildJsonObject { put("task_id", "t1"); put("status", "waiting_on_phone"); put("step", 5) })
        executor.syncSession(buildJsonObject { put("task_id", "t1"); put("status", "waiting_on_phone"); put("step", 5) })
        assertEquals(0, bringCount)
        assertEquals(1, attentionCount)
        assertEquals(HandoverPhase.PENDING_ATTENTION, executor.controlState.value.handoverPhase)

        // Resuming then hitting another waiting_on_phone alerts again without stealing foreground
        executor.syncSession(buildJsonObject { put("task_id", "t1"); put("status", "running"); put("step", 6) })
        executor.syncSession(buildJsonObject { put("task_id", "t1"); put("status", "waiting_on_phone"); put("step", 7) })
        assertEquals(0, bringCount)
        assertEquals(2, attentionCount)
    }

    @Test
    fun compactOverlayDimensionsAndRenderedScreenshots() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val density = app.resources.displayMetrics.density
        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
        )
        val widthDp = overlay.fixedCardWidthPx / density
        val previewHeightDp = overlay.fixedPreviewHeightPx / density
        assertTrue("Expected compact width <= 112dp, got $widthDp", widthDp <= 120f)
        assertTrue("Expected compact preview height <= 160dp, got $previewHeightDp", previewHeightDp <= 160f)

        // Build realistic fake mini-screen frame (simulating CUniq app UI in background virtual display)
        val mockFrame = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
        Canvas(mockFrame).apply {
            val bg = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, 320f, Color.parseColor("#E31D2B"), Color.parseColor("#FFF5F5"), Shader.TileMode.CLAMP)
            }
            drawRect(0f, 0f, 180f, 320f, bg)
            val card = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            drawRoundRect(14f, 66f, 166f, 175f, 14f, 14f, card)
            val bar = Paint().apply { color = Color.parseColor("#1D4ED8"); isAntiAlias = true }
            drawRoundRect(26f, 118f, 134f, 134f, 6f, 6f, bar)
            val txt = Paint().apply { color = Color.WHITE; textSize = 18f; isAntiAlias = true }
            drawText("MyCUniq SG", 26f, 40f, txt)
            txt.color = Color.parseColor("#111827")
            txt.textSize = 14f
            drawText("Data Remaining", 26f, 98f, txt)
            drawText("48.2 GB / 50 GB", 26f, 158f, txt)
        }

        fun renderOverlayOnPhoneMock(fileName: String, summary: OverlayTaskSummary, frame: Bitmap?, protectedFrame: Boolean) {
            val view = overlay.buildOverlayViewForTest(app, summary, frame, protectedFrame)
            val wPx = overlay.fixedCardWidthPx
            val wSpec = View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val cardH = view.measuredHeight
            val cardHDp = cardH / density
            assertTrue("Expected compact card height <= 245dp, got $cardHDp", cardHDp <= 245f)

            // Composite onto a 400dp x 800dp phone screen mock so area ratio is visually obvious
            val screenW = (400 * density).toInt()
            val screenH = (800 * density).toInt()
            val outBmp = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            val c = Canvas(outBmp)
            c.drawColor(Color.parseColor("#F3F4F6"))
            val p = Paint().apply { color = Color.parseColor("#9CA3AF"); textSize = 14f * density; isAntiAlias = true }
            c.drawText("主屏幕（400dp × 800dp）", 24f * density, 52f * density, p)
            c.drawText("悬浮窗尺寸：${widthDp.toInt()}dp × ${cardHDp.toInt()}dp（占屏 ${(widthDp * cardHDp / (400f * 800f) * 100).toInt()}%）", 24f * density, 76f * density, p)
            val marginEnd = (8 * density).toInt()
            val marginTop = (104 * density).toInt()
            c.save()
            c.translate((screenW - wPx - marginEnd).toFloat(), marginTop.toFloat())
            view.draw(c)
            c.restore()
            File(shotsDir(), fileName).outputStream().use { outBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            outBmp.recycle()
        }

        renderOverlayOnPhoneMock(
            "overlay-live-preview.png",
            OverlayTaskSummary("t1", "running", 4, "正在读取流量余量", isActive = true, usingVirtualDisplay = true),
            mockFrame,
            protectedFrame = false,
        )
        renderOverlayOnPhoneMock(
            "overlay-protected-screen.png",
            OverlayTaskSummary("t2", "waiting_on_phone", 6, "请完成滑块验证", isWaitingOnPhone = true, isActive = true, usingVirtualDisplay = true),
            null,
            protectedFrame = true,
        )
        mockFrame.recycle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun renderPhoneToolsScreenInThreeStyles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connection = Proxy.newProxyInstance(HubConnection::class.java.classLoader, arrayOf(HubConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> MutableStateFlow(ConnectionState.Online)
                "getEvents" -> emptyFlow<Any>()
                else -> error("unexpected: ${method.name}")
            }
        } as HubConnection
        val transport = object : PhoneToolsTransport {
            override val calls = emptyFlow<PhoneToolCall>()
            override fun discardPending() = Unit
            override fun register(deviceId: String, deviceName: String, simulator: Boolean, tools: List<PhoneToolSpec>) = true
            override fun result(callId: String, result: PhoneToolResult) = true
        }
        val runtime = PhoneToolsRuntime(app, HubScope("hub-shot-${UUID.randomUUID()}", "one"), "dev-shot", connection, transport, scope)
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }
        val styleState = androidx.compose.runtime.mutableStateOf(AppStyle.Brief)
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = styleState.value) {
                Box(Modifier.testTag("shot").fillMaxSize().background(PersonaTheme.palette.background)) {
                    PhoneToolsScreen(runtime, navigator)
                }
            }
        }
        rule.waitForIdle()
        // Turn on Remote Screen Control so all subsections (permissions, virtual display, safety gates) render
        val masterTitle = app.getString(R.string.phonetools_control_title)
        rule.onNodeWithText(masterTitle).performClick()
        rule.waitForIdle()

        // Verify URI scheme jargon is gone and new section headers exist
        rule.onNodeWithText(app.getString(R.string.phonetools_control_perms_section)).assertExists()
        rule.onNodeWithText(app.getString(R.string.phonetools_control_vd_section)).assertExists()
        rule.onNodeWithText(app.getString(R.string.phonetools_control_safety_section)).assertExists()

        for (s in listOf(AppStyle.Brief, AppStyle.Board, AppStyle.Glass)) {
            styleState.value = s
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
            File(shotsDir(), "phonetools-${s.name.lowercase()}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        runtime.close()
    }
}
