package persona.platform.phonetools.control

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import persona.core.designsystem.GroupPosition
import persona.core.designsystem.JDivider
import persona.core.designsystem.JGroupItem
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Tokens
import persona.core.designsystem.rowPadding
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator
import persona.platform.phonetools.KEY_POST_NOTIF_REQUESTED
import persona.platform.phonetools.POST_NOTIF_PREFS_NAME
import persona.platform.phonetools.PhoneToolsRuntime
import persona.platform.phonetools.PhoneToolsScreen
import persona.platform.phonetools.PostNotificationsPermissionRow
import persona.platform.phonetools.R
import persona.platform.phonetools.shouldFallbackToNotificationSettingsAfterDenial
import persona.platform.phonetools.shouldRequestPostNotificationsRuntime
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w400dp-h1800dp-xhdpi")
class Build24OverlayAndNotificationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun shotsDir(): File =
        File(System.getProperty("persona.shots") ?: "/tmp/build24-ui-shots").also { it.mkdirs() }

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val _ = app.getSharedPreferences(POST_NOTIF_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PhoneControlGateState.resetForShutdown()
        scope.cancel()
    }

    private class FakeWindowManagerSpy(
        private val delegate: WindowManager,
        var addViewError: (() -> Throwable)? = null,
    ) {
        val addedTypes = mutableListOf<Int>()
        val addedFlags = mutableListOf<Int>()
        val addedTitles = mutableListOf<CharSequence?>()
        val addedViews = mutableListOf<View>()
        val removedViews = mutableListOf<View>()

        val proxy: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    val view = args!![0] as View
                    val params = args[1] as WindowManager.LayoutParams
                    addViewError?.invoke()?.let { throw it }
                    val _ = addedViews.add(view)
                    val _ = addedTypes.add(params.type)
                    val _ = addedFlags.add(params.flags)
                    val _ = addedTitles.add(params.title)
                    Unit
                }
                "removeViewImmediate", "removeView" -> {
                    val view = args!![0] as View
                    val _ = removedViews.add(view)
                    Unit
                }
                "updateViewLayout" -> Unit
                else -> method.invoke(delegate, *(args ?: emptyArray()))
            }
        } as WindowManager
    }

    private class RecordingRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val inputs = mutableListOf<Any?>()
        private var lastRequestCode: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            val _ = inputs.add(input)
            lastRequestCode = requestCode
        }

        fun complete(granted: Boolean) {
            val code = lastRequestCode ?: error("No pending permission request")
            check(dispatchResult(code, granted))
            lastRequestCode = null
        }
    }

    private fun fakeHostContext(base: Context): Context = object : ContextWrapper(base) {}

    @Test
    fun overlayPrefersAccessibilityOverlayAcrossAllFourModesWhenConnectedOnDisplay0() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val baseWm = app.getSystemService(WindowManager::class.java)
        val a11yHost = fakeHostContext(app)
        val a11yWm = FakeWindowManagerSpy(baseWm)
        val appWm = FakeWindowManagerSpy(baseWm)

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { a11yHost },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { ctx -> if (ctx === a11yHost) a11yWm.proxy else appWm.proxy },
        )

        try {
            // 1. Default MINI_PILL prefers TYPE_ACCESSIBILITY_OVERLAY (2032) over TYPE_APPLICATION_OVERLAY (2038).
            val running = OverlayTaskSummary("t-a11y", "running", 1, "Reading settings", isActive = true, usingVirtualDisplay = true)
            overlay.update(running)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(overlay.isShowing)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertSame(a11yHost, overlay.activeHostContext)
            assertEquals(listOf(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY), a11yWm.addedTypes)
            assertEquals(listOf<CharSequence?>(TaskStatusOverlay.OVERLAY_WINDOW_TITLE), a11yWm.addedTitles)
            assertTrue("Fallback app overlay WindowManager should not be used when a11y overlay succeeds", appWm.addedTypes.isEmpty())

            // 2. INLINE_INTERACTIVE keeps TYPE_ACCESSIBILITY_OVERLAY and enables FLAG_HARDWARE_ACCELERATED for TextureView.
            val inline = running.copy(
                status = "waiting_on_phone",
                isWaitingOnPhone = true,
                handoverPhase = HandoverPhase.INLINE,
                targetAppLabel = "Settings",
            )
            overlay.update(inline)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.INLINE_INTERACTIVE, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertTrue(
                "INLINE_INTERACTIVE should set FLAG_HARDWARE_ACCELERATED",
                (a11yWm.addedFlags.last() and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0,
            )

            // 3. FULLSCREEN_BAR keeps TYPE_ACCESSIBILITY_OVERLAY (stays visible even over sfl=1 com.android.settings).
            val fullscreen = inline.copy(handoverPhase = HandoverPhase.FULLSCREEN)
            overlay.update(fullscreen)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.FULLSCREEN_BAR, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)

            // 4. Returning to MINI_PILL keeps TYPE_ACCESSIBILITY_OVERLAY.
            overlay.update(running.copy(step = 2))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertTrue(appWm.addedTypes.isEmpty())
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(overlay.isShowing)
            assertNull(overlay.activeWindowType)
        }
    }

    @Test
    fun overlayFallsBackCleanlyToApplicationOverlayWhenAccessibilityUnavailableOrThrows() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val baseWm = app.getSystemService(WindowManager::class.java)
        val running = OverlayTaskSummary("t-fb", "running", 1, "Working", isActive = true, usingVirtualDisplay = true)

        // Case A: Accessibility service not connected -> direct fallback to TYPE_APPLICATION_OVERLAY (2038).
        val appWmNoA11y = FakeWindowManagerSpy(baseWm)
        val overlayNoA11y = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { null },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { appWmNoA11y.proxy },
        )
        overlayNoA11y.update(running)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlayNoA11y.isShowing)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayNoA11y.activeWindowType)
        assertEquals(listOf(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY), appWmNoA11y.addedTypes)
        overlayNoA11y.hide()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Case B: Accessibility addView throws BadTokenException -> falls back cleanly without crash or flicker.
        val badTokenHost = fakeHostContext(app)
        val badTokenWm = FakeWindowManagerSpy(baseWm, addViewError = { WindowManager.BadTokenException("token invalid") })
        val fallbackWm1 = FakeWindowManagerSpy(baseWm)
        val overlayBadToken = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { badTokenHost },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { ctx -> if (ctx === badTokenHost) badTokenWm.proxy else fallbackWm1.proxy },
        )
        overlayBadToken.update(running)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlayBadToken.isShowing)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlayBadToken.activeWindowType)
        assertEquals(1, fallbackWm1.addedTypes.size)
        // Subsequent update with same broken host must NOT tear down & flicker!
        overlayBadToken.update(running.copy(step = 2))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fallbackWm1.addedTypes.size)
        overlayBadToken.hide()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Case C: Accessibility addView throws SecurityException -> falls back cleanly without crash.
        val secHost = fakeHostContext(app)
        val secWm = FakeWindowManagerSpy(baseWm, addViewError = { SecurityException("permission denied") })
        val fallbackWm2 = FakeWindowManagerSpy(baseWm)
        val overlaySec = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { secHost },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { ctx -> if (ctx === secHost) secWm.proxy else fallbackWm2.proxy },
        )
        overlaySec.update(running)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlaySec.isShowing)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlaySec.activeWindowType)
        overlaySec.hide()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun overlayMigratesCleanlyAcrossAccessibilityDisconnectAndReconnect() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val baseWm = app.getSystemService(WindowManager::class.java)
        val host1 = fakeHostContext(app)
        val host2 = fakeHostContext(app)
        val wm1 = FakeWindowManagerSpy(baseWm)
        val wm2 = FakeWindowManagerSpy(baseWm)
        val appWm = FakeWindowManagerSpy(baseWm)
        var currentA11yHost: Context? = host1
        var canDrawOverlays = true

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { currentA11yHost },
            canDrawOverlaysProvider = { canDrawOverlays },
            windowManagerProvider = { ctx ->
                when {
                    ctx === host1 -> wm1.proxy
                    ctx === host2 -> wm2.proxy
                    else -> appWm.proxy
                }
            },
        )

        try {
            val summary = OverlayTaskSummary("t-mig", "running", 3, "Navigating", isActive = true, usingVirtualDisplay = true)
            overlay.update(summary)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertSame(host1, overlay.activeHostContext)

            // Disconnect accessibility service -> cleans up old view and falls back to TYPE_APPLICATION_OVERLAY.
            currentA11yHost = null
            overlay.onAccessibilityServiceChanged(null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(overlay.isShowing)
            assertEquals(1, wm1.removedViews.size)
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlay.activeWindowType)
            assertEquals(1, appWm.addedTypes.size)

            // Reconnect with a fresh accessibility service -> removes app overlay and upgrades to TYPE_ACCESSIBILITY_OVERLAY.
            currentA11yHost = host2
            overlay.onAccessibilityServiceChanged(host2)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(overlay.isShowing)
            assertEquals(1, appWm.removedViews.size)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertSame(host2, overlay.activeHostContext)
            assertEquals(1, wm2.addedTypes.size)

            // Disconnect when draw-overlays is also off -> hides cleanly without crashing, then remounts on reconnect.
            canDrawOverlays = false
            currentA11yHost = null
            overlay.onAccessibilityServiceChanged(null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(overlay.isShowing)
            assertEquals(1, wm2.removedViews.size)

            currentA11yHost = host2
            overlay.onAccessibilityServiceChanged(host2)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(overlay.isShowing)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertEquals(2, wm2.addedTypes.size)
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun postNotificationsPermissionMatrixAndNotificationsSafetyGuard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)

        // Pre-Android 13 (API 32): treated as granted as long as app notifications are enabled.
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowNm.setNotificationsEnabled(true)
        assertTrue(PhoneControlPermissionsChecker.isPostNotificationsGranted(app, sdkInt = 32))
        shadowNm.setNotificationsEnabled(false)
        assertFalse(PhoneControlPermissionsChecker.isPostNotificationsGranted(app, sdkInt = 32))

        // Android 13+ (API 33-36): requires POST_NOTIFICATIONS runtime permission + app notifications enabled.
        shadowNm.setNotificationsEnabled(true)
        assertFalse(PhoneControlPermissionsChecker.isPostNotificationsGranted(app, sdkInt = 35))
        assertFalse(PhoneControlPermissionsChecker.check(app).postNotifications)
        assertFalse(PhoneControlPermissionsChecker.check(app).toWire()["post_notifications"]!!.jsonPrimitive.boolean)

        // Settings intent targets ACTION_APP_NOTIFICATION_SETTINGS with EXTRA_APP_PACKAGE.
        val settingsIntent = PhoneControlPermissionsChecker.buildPostNotificationSettingsIntent(app.packageName)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settingsIntent.action)
        assertEquals(app.packageName, settingsIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        // Decision helpers for runtime permission vs permanent-denial settings fallback.
        assertFalse(shouldRequestPostNotificationsRuntime(sdkInt = 32, granted = false, alreadyRequested = false, shouldShowRationale = false))
        assertFalse(shouldRequestPostNotificationsRuntime(sdkInt = 35, granted = true, alreadyRequested = false, shouldShowRationale = false))
        assertTrue(shouldRequestPostNotificationsRuntime(sdkInt = 35, granted = false, alreadyRequested = false, shouldShowRationale = false))
        assertTrue(shouldRequestPostNotificationsRuntime(sdkInt = 35, granted = false, alreadyRequested = true, shouldShowRationale = true))
        assertFalse(shouldRequestPostNotificationsRuntime(sdkInt = 35, granted = false, alreadyRequested = true, shouldShowRationale = false))
        assertTrue(shouldFallbackToNotificationSettingsAfterDenial(wasRationaleBefore = false, rationaleAfter = false, elapsedMs = 20L))
        assertFalse(shouldFallbackToNotificationSettingsAfterDenial(wasRationaleBefore = false, rationaleAfter = true, elapsedMs = 20L))
        assertFalse(shouldFallbackToNotificationSettingsAfterDenial(wasRationaleBefore = true, rationaleAfter = false, elapsedMs = 20L))

        // Without POST_NOTIFICATIONS on API 35, PhoneControlNotifications skips safely without posting or crashing.
        val notifs = PhoneControlNotifications(app)
        val req = HandoverRequest(
            taskId = "t-notif-1",
            waitingFor = "hand_to_user",
            reason = "Please complete verification",
            appPackage = "com.android.settings",
            requiresForeground = false,
        )
        assertFalse(notifs.showAttentionBanner(req, appLabel = "Settings", canInline = true))
        assertFalse(notifs.showResultNotification("done", "Completed"))
        assertEquals(0, shadowNm.allNotifications.size)
        assertNotNull(nm.getNotificationChannel(PhoneControlNotifications.CHANNEL_ATTENTION))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(PhoneControlNotifications.CHANNEL_ATTENTION).importance)

        // With POST_NOTIFICATIONS granted, posts high-priority banner with quick actions + result notification.
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(PhoneControlPermissionsChecker.isPostNotificationsGranted(app, sdkInt = 35))
        assertTrue(PhoneControlPermissionsChecker.check(app).postNotifications)
        assertTrue(PhoneControlPermissionsChecker.check(app).toWire()["post_notifications"]!!.jsonPrimitive.boolean)

        assertTrue(notifs.showAttentionBanner(req, appLabel = "Settings", canInline = true))
        val posted = shadowNm.getNotification(PhoneControlNotifications.NOTIF_ID_ATTENTION)
        assertNotNull(posted)
        assertEquals(PhoneControlNotifications.CHANNEL_ATTENTION, posted.channelId)
        assertEquals(3, posted.actions?.size ?: 0)
        assertTrue(notifs.showResultNotification("done", "Completed"))
        assertNotNull(shadowNm.getNotification(PhoneControlNotifications.NOTIF_ID_RESULT))
        notifs.cancelAttentionBanner()
    }

    @Test
    fun phoneToolsScreenPostNotificationsGuidanceFlowAndRenderedScreenshots() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)

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
        val runtime = PhoneToolsRuntime(app, HubScope("hub-b24-${UUID.randomUUID()}", "one"), "dev-b24", connection, transport, scope)
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }
        val registry = RecordingRegistry()
        val styleState = mutableStateOf(AppStyle.Glass)

        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = styleState.value) {
                    Box(Modifier.testTag("b24_screen").fillMaxSize().background(PersonaTheme.palette.background)) {
                        PhoneToolsScreen(runtime, navigator)
                    }
                }
            }
        }
        rule.waitForIdle()

        // Enable remote screen control to reveal the Required permissions card including POST_NOTIFICATIONS.
        rule.onNodeWithText(app.getString(R.string.phonetools_control_title)).performClick()
        rule.waitForIdle()

        val postNotifTitle = app.getString(R.string.phonetools_control_perm_post_notif_title)
        val postNotifDetail = app.getString(R.string.phonetools_control_perm_post_notif_detail)
        val allowText = app.getString(R.string.phonetools_allow_access)
        val sysSettingsText = app.getString(R.string.phonetools_system_settings)

        rule.onNodeWithText(postNotifTitle).assertExists().assertIsDisplayed()
        rule.onNodeWithText(postNotifDetail).assertExists().assertIsDisplayed()
        rule.onAllNodesWithText(allowText).onFirst().assertExists().assertIsDisplayed()
        rule.onAllNodesWithText(sysSettingsText).onFirst().assertExists().assertIsDisplayed()

        // Render & save PNG screenshots of PhoneToolsScreen across all 3 styles (denied state) for visual review.
        for (style in listOf(AppStyle.Glass, AppStyle.Brief, AppStyle.Board)) {
            styleState.value = style
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag("b24_screen").captureToImage().asAndroidBitmap()
            File(shotsDir(), "b24-phonetools-notif-denied-${style.name.lowercase()}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        styleState.value = AppStyle.Glass
        rule.waitForIdle()

        // First tap on "Allow" launches standard POST_NOTIFICATIONS runtime request.
        rule.onAllNodesWithText(allowText).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), registry.inputs)

        // Simulate immediate permanent-denial auto-rejection -> gracefully navigates to ACTION_APP_NOTIFICATION_SETTINGS.
        Shadows.shadowOf(rule.activity).clearNextStartedActivities()
        registry.complete(granted = false)
        rule.waitForIdle()
        val autoFallbackIntent = Shadows.shadowOf(rule.activity).nextStartedActivity
        assertNotNull("Permanent denial should fall back to app notification settings", autoFallbackIntent)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, autoFallbackIntent.action)
        assertEquals(app.packageName, autoFallbackIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        // Subsequent tap on "Allow" when permanently denied skips dead dialog and opens ACTION_APP_NOTIFICATION_SETTINGS directly.
        Shadows.shadowOf(rule.activity).clearNextStartedActivities()
        rule.onAllNodesWithText(allowText).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(1, registry.inputs.size)
        val secondTapIntent = Shadows.shadowOf(rule.activity).nextStartedActivity
        assertNotNull(secondTapIntent)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, secondTapIntent.action)

        // Tapping "System settings" directly opens ACTION_APP_NOTIFICATION_SETTINGS.
        Shadows.shadowOf(rule.activity).clearNextStartedActivities()
        rule.onAllNodesWithText(sysSettingsText).onFirst().performClick()
        rule.waitForIdle()
        val settingsClickIntent = Shadows.shadowOf(rule.activity).nextStartedActivity
        assertNotNull(settingsClickIntent)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settingsClickIntent.action)

        // Grant via runtime callback -> updates UI immediately to Allowed and hides Allow button for notification row.
        val _ = app.getSharedPreferences(POST_NOTIF_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_POST_NOTIF_REQUESTED, false).commit()
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onAllNodesWithText(allowText).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(2, registry.inputs.size)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        registry.complete(granted = true)
        rule.waitForIdle()
        assertTrue(runtime.control.currentSnapshot().permissions.postNotifications)
        rule.onNodeWithText(app.getString(R.string.phonetools_status_granted)).assertExists().assertIsDisplayed()
        val grantedBmp = rule.onNodeWithTag("b24_screen").captureToImage().asAndroidBitmap()
        File(shotsDir(), "b24-phonetools-notif-granted-glass.png").outputStream().use {
            grantedBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        runtime.close()
    }

    @Test
    fun renderPostNotificationsCardDeniedAndGrantedScreenshots() {
        val styleState = mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = styleState.value) {
                Box(
                    Modifier
                        .testTag("b24_notif_card")
                        .fillMaxWidth()
                        .background(PersonaTheme.palette.background)
                        .padding(Tokens.Space.m),
                ) {
                    JGroupItem(GroupPosition.Single) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = rowPadding, vertical = Tokens.Space.m)) {
                            PostNotificationsPermissionRow(
                                granted = false,
                                onRequestGrant = {},
                                onOpenSettings = {},
                            )
                            JDivider()
                            PostNotificationsPermissionRow(
                                granted = true,
                                onRequestGrant = {},
                                onOpenSettings = {},
                            )
                        }
                    }
                }
            }
        }
        for (style in listOf(AppStyle.Glass, AppStyle.Brief, AppStyle.Board)) {
            styleState.value = style
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag("b24_notif_card").captureToImage().asAndroidBitmap()
            File(shotsDir(), "b24-notif-card-states-${style.name.lowercase()}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
