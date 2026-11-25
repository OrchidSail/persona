package persona.core.designsystem

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-xhdpi")
class PlanPillTest {
    @get:Rule val rule = createComposeRule()

    @Test fun progressFractionUsesCompletedCountAndClampsBounds() {
        assertEquals(0f, planPillProgressFraction(done = 0, total = 4), 0.0001f)
        assertEquals(0.25f, planPillProgressFraction(done = 1, total = 4), 0.0001f)
        assertEquals(0.75f, planPillProgressFraction(done = 3, total = 4), 0.0001f)
        assertEquals(1f, planPillProgressFraction(done = 4, total = 4), 0.0001f)
        assertEquals(1f, planPillProgressFraction(done = 6, total = 4), 0.0001f)
        assertEquals(0f, planPillProgressFraction(done = -1, total = 4), 0.0001f)
        assertEquals(0f, planPillProgressFraction(done = 2, total = 0), 0.0001f)
    }

    /**
     * Regression for PlanPill drawing `step / total` instead of `done / total` in Brief and Board:
     * with `done = 0`, stepping from step 1 to step 3 must keep the progress ring identical (empty),
     * while advancing `done` from 0 to 2 must visibly fill the ring in every style.
     */
    @Test fun progressRingTracksDoneRatherThanStepInEveryStyle() {
        var style by mutableStateOf(AppStyle.Glass)
        var step by mutableIntStateOf(1)
        var done by mutableIntStateOf(0)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                Box(Modifier.background(PersonaTheme.palette.background).padding(Tokens.Space.m)) {
                    PlanPill(step = step, total = 4, done = done, onClick = {}, modifier = Modifier.testTag("plan-pill"))
                }
            }
        }
        for (s in AppStyle.entries) {
            rule.runOnIdle { style = s; step = 1; done = 0 }
            rule.waitForIdle()
            rule.onNodeWithTag("plan-pill").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, 0f..4f))
            )
            val stepOneZeroDone = leadingRingPixels(rule.onNodeWithTag("plan-pill").captureToImage().asAndroidBitmap())

            rule.runOnIdle { step = 3; done = 0 }
            rule.waitForIdle()
            val stepThreeZeroDone = leadingRingPixels(rule.onNodeWithTag("plan-pill").captureToImage().asAndroidBitmap())
            assertTrue("$s: changing step with done=0 must not draw progress arc", stepOneZeroDone.contentEquals(stepThreeZeroDone))

            rule.runOnIdle { step = 3; done = 2 }
            rule.waitForIdle()
            rule.onNodeWithTag("plan-pill").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(2f, 0f..4f))
            )
            val stepThreeTwoDone = leadingRingPixels(rule.onNodeWithTag("plan-pill").captureToImage().asAndroidBitmap())
            assertFalse("$s: advancing done from 0 to 2 must draw the progress arc", stepThreeZeroDone.contentEquals(stepThreeTwoDone))
        }
    }

    /** Samples the exact leading 16dp × 16dp progress ring inside PlanPill (starts at 14dp padding), independent of text width. */
    private fun leadingRingPixels(bitmap: Bitmap): IntArray {
        val left = with(rule.density) { 14.dp.roundToPx() }.coerceIn(0, bitmap.width - 1)
        val ring = with(rule.density) { 16.dp.roundToPx() }.coerceAtMost(bitmap.width - left).coerceAtLeast(1)
        val top = ((bitmap.height - ring) / 2).coerceIn(0, bitmap.height - 1)
        val height = ring.coerceAtMost(bitmap.height - top).coerceAtLeast(1)
        val pixels = IntArray(ring * height)
        bitmap.getPixels(pixels, 0, ring, left, top, ring, height)
        return pixels
    }
}
