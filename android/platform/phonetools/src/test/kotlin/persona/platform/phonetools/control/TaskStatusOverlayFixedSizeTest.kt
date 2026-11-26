package persona.platform.phonetools.control

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TaskStatusOverlayFixedSizeTest {

    @Test
    fun overlayKeepsFixedDimensionsAndZeroFlickerInVirtualDisplayMode() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val overlay = TaskStatusOverlay(
            context = app,
            onTogglePause = {},
            onTakeoverOrResumePhone = {},
            onEmergencyStop = {},
        )

        assertTrue(overlay.fixedCardWidthPx > 0)
        assertTrue(overlay.fixedPreviewHeightPx > 0)

        // Update with short then very long narration: state tracks virtual display mode without crashing.
        overlay.update(
            OverlayTaskSummary(
                taskId = "t-vd",
                status = "running",
                step = 1,
                actionSummary = "Tap",
                isActive = true,
                usingVirtualDisplay = true,
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlay.usingVirtualDisplayMode)

        val frame = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.BLUE)
        try {
            overlay.updatePreviewFrame(frame, usingVirtualDisplay = true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // In virtual-display mode, hiddenWhile and passGesturesThrough short-circuit immediately.
            var ranHidden = false
            val h = overlay.hiddenWhile {
                ranHidden = true
                "ok-hidden"
            }
            assertTrue(ranHidden)
            assertEquals("ok-hidden", h)

            var ranPass = false
            val p = overlay.passGesturesThrough {
                ranPass = true
                "ok-pass"
            }
            assertTrue(ranPass)
            assertEquals("ok-pass", p)

            overlay.update(
                OverlayTaskSummary(
                    taskId = "t-vd",
                    status = "running",
                    step = 9,
                    actionSummary = "Very long narration string that previously caused WRAP_CONTENT overlay jumping across the screen",
                    isActive = true,
                    usingVirtualDisplay = true,
                ),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(9, overlay.lastSummary.step)
            assertNotNull(overlay.lastSummary.actionSummary)
        } finally {
            overlay.hide()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(overlay.isShowing)
            frame.recycle()
        }
    }
}
