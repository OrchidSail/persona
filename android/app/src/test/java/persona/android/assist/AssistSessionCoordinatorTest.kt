package persona.android.assist

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import persona.feature.chat.ChatLinkContext
import persona.feature.chat.ComposerAttachment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistSessionCoordinatorTest {
    private val sampleAttachment = ComposerAttachment(
        id = "assist-1",
        name = "assist-1.jpg",
        uri = "content://com.example.persona.chat.files/assist/assist-1.jpg",
        image = true,
        size = 1024L,
    )
    private val sampleContext = ChatLinkContext(
        kind = "context",
        title = "Example Page",
        url = "https://example.com",
        text = "Example Page\nhttps://example.com",
        id = "assist-ctx-1",
    )

    @Test
    fun assistThenScreenshotCoalescesAndFinishesOnce() = runTest {
        val published = mutableListOf<Triple<String, ComposerAttachment?, ChatLinkContext?>>()
        var launches = 0
        var finishes = 0
        val coordinator = AssistSessionCoordinator(
            mainScope = this,
            ioScope = this,
            expectAssist = true,
            expectScreenshot = true,
            handoffId = "h1",
            saveScreenshot = { sampleAttachment },
            publishDraft = { id, shot, ctx -> published += Triple(id, shot, ctx) },
            attachLateScreenshot = { _, _ -> error("unexpected late screenshot") },
            attachLateContext = { _, _ -> error("unexpected late context") },
            launchPersona = { launches++ },
            finishSession = { finishes++ },
        )

        coordinator.start()
        coordinator.onAssist(sampleContext, isFocused = true)
        runCurrent()
        assertEquals(0, launches)

        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        coordinator.onScreenshot(bitmap)
        advanceUntilIdle()

        assertEquals(1, launches)
        assertEquals(1, finishes)
        assertEquals(listOf(Triple("h1", sampleAttachment, sampleContext)), published)
        bitmap.recycle()
    }

    @Test
    fun screenshotThenAssistOutOfOrderCoalescesBoth() = runTest {
        val published = mutableListOf<Triple<String, ComposerAttachment?, ChatLinkContext?>>()
        var launches = 0
        var finishes = 0
        val coordinator = AssistSessionCoordinator(
            mainScope = this,
            ioScope = this,
            expectAssist = true,
            expectScreenshot = true,
            handoffId = "h2",
            saveScreenshot = { sampleAttachment },
            publishDraft = { id, shot, ctx -> published += Triple(id, shot, ctx) },
            attachLateScreenshot = { _, _ -> error("unexpected late screenshot") },
            attachLateContext = { _, _ -> error("unexpected late context") },
            launchPersona = { launches++ },
            finishSession = { finishes++ },
        )

        coordinator.start()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        coordinator.onScreenshot(bitmap)
        runCurrent()
        assertEquals(0, launches)

        coordinator.onAssist(sampleContext, isFocused = true)
        advanceUntilIdle()

        assertEquals(1, launches)
        assertEquals(1, finishes)
        assertEquals(listOf(Triple("h2", sampleAttachment, sampleContext)), published)
        bitmap.recycle()
    }

    @Test
    fun nullScreenshotFromFlagSecureDegradesGracefullyWithoutWaitingForTimeout() = runTest {
        val published = mutableListOf<Triple<String, ComposerAttachment?, ChatLinkContext?>>()
        var launches = 0
        var finishes = 0
        val coordinator = AssistSessionCoordinator(
            mainScope = this,
            ioScope = this,
            expectAssist = true,
            expectScreenshot = true,
            handoffId = "h3",
            saveScreenshot = { error("save must not run for null screenshot") },
            publishDraft = { id, shot, ctx -> published += Triple(id, shot, ctx) },
            attachLateScreenshot = { _, _ -> error("unexpected late screenshot") },
            attachLateContext = { _, _ -> error("unexpected late context") },
            launchPersona = { launches++ },
            finishSession = { finishes++ },
        )

        coordinator.start()
        coordinator.onAssist(sampleContext, isFocused = true)
        coordinator.onScreenshot(null)
        runCurrent()

        assertEquals(1, launches)
        assertEquals(1, finishes)
        assertNull(published.single().second)
        assertEquals(sampleContext, published.single().third)
    }

    @Test
    fun slowScreenshotSaveLaunchesOnTimeAndAttachesLateScreenshot() = runTest {
        val published = mutableListOf<Triple<String, ComposerAttachment?, ChatLinkContext?>>()
        val lateShots = mutableListOf<Pair<String, ComposerAttachment>>()
        var launches = 0
        var finishes = 0
        val coordinator = AssistSessionCoordinator(
            mainScope = this,
            ioScope = this,
            expectAssist = false,
            expectScreenshot = true,
            handoffId = "h4",
            saveScreenshot = {
                delay(500L)
                sampleAttachment
            },
            publishDraft = { id, shot, ctx -> published += Triple(id, shot, ctx) },
            attachLateScreenshot = { id, shot -> lateShots += id to shot },
            attachLateContext = { _, _ -> },
            launchPersona = { launches++ },
            finishSession = { finishes++ },
            saveWaitMillis = 100L,
        )

        coordinator.start()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        coordinator.onScreenshot(bitmap)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(1, launches)
        assertEquals(1, finishes)
        assertNull(published.single().second)
        assertEquals(0, lateShots.size)

        advanceUntilIdle()
        assertEquals(listOf("h4" to sampleAttachment), lateShots)
        bitmap.recycle()
    }

    @Test
    fun missingCallbacksStillLaunchAndFinishAtSafetyDeadline() = runTest {
        var launches = 0
        var finishes = 0
        val coordinator = AssistSessionCoordinator(
            mainScope = this,
            ioScope = this,
            expectAssist = false,
            expectScreenshot = false,
            handoffId = "h5",
            saveScreenshot = { null },
            publishDraft = { _, _, _ -> },
            attachLateScreenshot = { _, _ -> },
            attachLateContext = { _, _ -> },
            launchPersona = { launches++ },
            finishSession = { finishes++ },
            idleWaitMillis = 150L,
        )

        coordinator.start()
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, launches)
        assertEquals(1, finishes)
    }
}
