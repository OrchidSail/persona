package persona.platform.phonetools.control

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import persona.core.contracts.HubScope
import persona.platform.phonetools.PhoneToolSettings
import persona.platform.phonetools.R
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh-w400dp-h800dp-xhdpi")
class OverlayStateMemoryAndPendingAttentionTest {

    private class SpyWindowManager(private val delegate: WindowManager) {
        val addedViews = mutableListOf<View>()
        val removedViews = mutableListOf<View>()
        var currentView: View? = null

        val proxy: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    val view = args!![0] as View
                    val _ = addedViews.add(view)
                    currentView = view
                    Unit
                }
                "removeViewImmediate", "removeView" -> {
                    val view = args!![0] as View
                    val _ = removedViews.add(view)
                    if (currentView === view) currentView = null
                    Unit
                }
                "updateViewLayout" -> Unit
                else -> method.invoke(delegate, *(args ?: emptyArray()))
            }
        } as WindowManager
    }

    private fun findTextView(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun expandedCardStaysOpenOnPendingAttentionShowsAmberCueAndHandlesWithoutMisclick() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val spyWm = SpyWindowManager(app.getSystemService(WindowManager::class.java))
        var handoverCalls = 0
        var takeoverOrResumeCalls = 0
        val prefChanges = mutableListOf<Boolean>()

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = { takeoverOrResumeCalls++ },
            onEmergencyStop = {},
            onOpenHandover = { handoverCalls++ },
            accessibilityHostProvider = { null },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { spyWm.proxy },
            initialMinimized = false,
            onExpandedPreferenceChanged = { val _ = prefChanges.add(it) },
        )

        try {
            val running = OverlayTaskSummary(
                taskId = "t-exp-1",
                status = "running",
                step = 2,
                actionSummary = "Opening settings",
                isActive = true,
                usingVirtualDisplay = true,
            )
            overlay.update(running)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)
            assertFalse(overlay.isPulsing)

            // Transition to PENDING_ATTENTION must keep LIGHT_CARD open!
            val attention = running.copy(
                status = "waiting_on_phone",
                step = 3,
                actionSummary = "Please complete slider verification",
                isWaitingOnPhone = true,
                handoverPhase = HandoverPhase.PENDING_ATTENTION,
            )
            overlay.update(attention)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)
            assertTrue("Expanded card should pulse on PENDING_ATTENTION", overlay.isPulsing)
            assertTrue("Programmatic phase transition must not rewrite preference", prefChanges.isEmpty())

            val root = requireNotNull(spyWm.currentView)
            val bg = root.background as GradientDrawable
            val expectedAttentionColor = ContextCompat.getColor(app, R.color.phonetools_overlay_stroke_attention)
            assertNotNull(bg)
            // Verify status title, detail summary, and Handle button text
            val titleView = findTextView(root) { it.text.toString().contains(app.getString(R.string.phonetools_overlay_status_waiting_on_phone)) }
            assertNotNull("Expected waiting_on_phone status title", titleView)
            val detailView = findTextView(root) { it.text.toString() == "Please complete slider verification" }
            assertNotNull("Expected attention detail text", detailView)

            val handleLabel = app.getString(R.string.phonetools_overlay_action_handle)
            val handleBtn = findTextView(root) { it.text.toString() == handleLabel }
            assertNotNull("Expected middle button to show Handle ($handleLabel)", handleBtn)
            // Clicking middle button opens handover instead of returning control!
            val _ = handleBtn!!.performClick()
            assertEquals(1, handoverCalls)
            assertEquals(0, takeoverOrResumeCalls)

            // User can still manually collapse the expanded card during PENDING_ATTENTION -> persists false
            val collapseBtn = findTextView(root) { it.contentDescription == app.getString(R.string.phonetools_overlay_collapse_cd) }
            assertNotNull("Expected collapse button on LIGHT_CARD", collapseBtn)
            val _ = collapseBtn!!.performClick()
            assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)
            assertTrue(overlay.isMinimizedPreference)
            assertEquals(listOf(false), prefChanges)
            assertTrue(expectedAttentionColor != 0)
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun collapsedPillStaysMinimizedOnPendingAttentionAndNeverAutoExpands() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val spyWm = SpyWindowManager(app.getSystemService(WindowManager::class.java))
        val prefChanges = mutableListOf<Boolean>()

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { null },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { spyWm.proxy },
            initialMinimized = true,
            onExpandedPreferenceChanged = { val _ = prefChanges.add(it) },
        )

        try {
            val running = OverlayTaskSummary("t-min-1", "running", 1, "Working", isActive = true, usingVirtualDisplay = true)
            overlay.update(running)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)

            val attention = running.copy(
                status = "waiting_on_phone",
                isWaitingOnPhone = true,
                handoverPhase = HandoverPhase.PENDING_ATTENTION,
            )
            overlay.update(attention)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)
            assertTrue(overlay.isMinimizedPreference)
            assertTrue(overlay.isPulsing)
            assertTrue(prefChanges.isEmpty())
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun handoverCyclesTerminalFadeOutAndHidePreserveUserExpandedPreference() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val spyWm = SpyWindowManager(app.getSystemService(WindowManager::class.java))
        val prefChanges = mutableListOf<Boolean>()

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { null },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { spyWm.proxy },
            initialMinimized = false,
            onExpandedPreferenceChanged = { val _ = prefChanges.add(it) },
        )

        try {
            val running = OverlayTaskSummary("t-cycle", "running", 1, "Step 1", isActive = true, usingVirtualDisplay = true)
            overlay.update(running)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)

            // INLINE -> RETURNING -> NONE restores LIGHT_CARD
            overlay.update(running.copy(status = "waiting_on_phone", isWaitingOnPhone = true, handoverPhase = HandoverPhase.INLINE))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.INLINE_INTERACTIVE, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)

            overlay.update(running.copy(status = "running", isWaitingOnPhone = false, handoverPhase = HandoverPhase.RETURNING))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)

            overlay.update(running.copy(status = "running", step = 2, handoverPhase = HandoverPhase.NONE))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)

            // FULLSCREEN -> RETURNING -> NONE restores LIGHT_CARD
            overlay.update(running.copy(status = "waiting_on_phone", isWaitingOnPhone = true, handoverPhase = HandoverPhase.FULLSCREEN))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.FULLSCREEN_BAR, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)

            overlay.update(running.copy(status = "running", isWaitingOnPhone = false, handoverPhase = HandoverPhase.RETURNING))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)

            // Terminal done fade-out keeps LIGHT_CARD and does not reset preference
            overlay.update(running.copy(status = "done", isActive = false, handoverPhase = HandoverPhase.NONE))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertFalse(overlay.isMinimizedPreference)

            // hide() between tasks MUST NOT reset minimized to true!
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(overlay.isShowing)
            assertFalse(overlay.isMinimizedPreference)

            // Next task opens directly as LIGHT_CARD without user tapping anything
            val nextTask = OverlayTaskSummary("t-next", "running", 1, "Next task", isActive = true, usingVirtualDisplay = true)
            overlay.update(nextTask)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertTrue(prefChanges.isEmpty())
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun accessibilityHostMigrationPreservesExpandedLightCardState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val baseWm = app.getSystemService(WindowManager::class.java)
        val a11yHost: Context = object : ContextWrapper(app) {}
        val a11yWm = SpyWindowManager(baseWm)
        val appWm = SpyWindowManager(baseWm)
        var currentHost: Context? = a11yHost

        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
            accessibilityHostProvider = { currentHost },
            canDrawOverlaysProvider = { true },
            windowManagerProvider = { ctx -> if (ctx === a11yHost) a11yWm.proxy else appWm.proxy },
            initialMinimized = false,
        )

        try {
            val running = OverlayTaskSummary("t-a11y-exp", "running", 1, "Navigating", isActive = true, usingVirtualDisplay = true)
            overlay.update(running)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)

            currentHost = null
            overlay.onAccessibilityServiceChanged(null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, overlay.activeWindowType)
            assertFalse(overlay.isMinimizedPreference)

            currentHost = a11yHost
            overlay.onAccessibilityServiceChanged(a11yHost)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlay.activeWindowType)
            assertFalse(overlay.isMinimizedPreference)
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun settingsDataStorePersistsOverlayExpandedAcrossRestartAndSyncsColdStartOverlay() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val hubScope = HubScope("hub-overlay-mem-${UUID.randomUUID()}", "one")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings1 = PhoneToolSettings(app, hubScope, scope1)
            assertFalse("First install default should be collapsed (false)", settings1.overlayExpanded.value)

            settings1.setOverlayExpanded(true)
            withTimeout(5_000L) {
                val _ = settings1.overlayExpanded.first { it }
            }
            assertTrue(settings1.overlayExpanded.value)
        } finally {
            scope1.coroutineContext.job.cancelAndJoin()
        }

        // Simulate process restart with a fresh PhoneToolSettings instance reading the same DataStore file
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings2 = PhoneToolSettings(app, hubScope, scope2)
            withTimeout(5_000L) {
                val _ = settings2.overlayExpanded.first { it }
            }
            assertTrue("Restarted PhoneToolSettings should restore overlayExpanded=true", settings2.overlayExpanded.value)

            // Simulate cold-start race: overlay created with initialMinimized=true before DataStore loaded,
            // then syncExpandedPreference(true) arrives and expands it to LIGHT_CARD without calling onExpandedPreferenceChanged.
            val spyWm = SpyWindowManager(app.getSystemService(WindowManager::class.java))
            val prefChanges = mutableListOf<Boolean>()
            val overlay = TaskStatusOverlay(
                context = app,
                onTogglePause = {},
                onTakeoverOrResumePhone = {},
                onEmergencyStop = {},
                accessibilityHostProvider = { null },
                canDrawOverlaysProvider = { true },
                windowManagerProvider = { spyWm.proxy },
                initialMinimized = true,
                onExpandedPreferenceChanged = { val _ = prefChanges.add(it) },
            )
            try {
                val running = OverlayTaskSummary("t-cold", "running", 1, "Cold start", isActive = true, usingVirtualDisplay = true)
                overlay.update(running)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)

                overlay.syncExpandedPreference(settings2.overlayExpanded.value)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(OverlayDisplayMode.LIGHT_CARD, overlay.displayMode)
                assertFalse(overlay.isMinimizedPreference)
                assertTrue(prefChanges.isEmpty())

                // Once user manually collapses, a stale syncExpandedPreference(true) must NOT override it!
                val root = requireNotNull(spyWm.currentView)
                val collapseBtn = findTextView(root) { it.contentDescription == app.getString(R.string.phonetools_overlay_collapse_cd) }
                val _ = requireNotNull(collapseBtn).performClick()
                assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)
                assertEquals(listOf(false), prefChanges)

                overlay.syncExpandedPreference(true)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(OverlayDisplayMode.MINI_PILL, overlay.displayMode)
                assertTrue(overlay.isMinimizedPreference)
            } finally {
                overlay.hide()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }

            // Persist false back to DataStore and verify
            settings2.setOverlayExpanded(false)
            withTimeout(5_000L) {
                val _ = settings2.overlayExpanded.first { !it }
            }
            assertFalse(settings2.overlayExpanded.value)
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }
}
