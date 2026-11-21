package persona.android.assist

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import persona.feature.chat.ChatLinkContext
import persona.feature.chat.ComposerAttachment

/**
 * Coordinates asynchronous, potentially out-of-order `onHandleAssist` and `onHandleScreenshot` callbacks
 * inside a `VoiceInteractionSession`, ensuring the session always launches Persona and finishes promptly
 * without hanging or dropping late-arriving screenshots.
 */
internal class AssistSessionCoordinator(
    private val mainScope: CoroutineScope,
    private val ioScope: CoroutineScope,
    private val expectAssist: Boolean,
    private val expectScreenshot: Boolean,
    private val handoffId: String,
    private val saveScreenshot: suspend (Bitmap) -> ComposerAttachment?,
    private val publishDraft: (id: String, screenshot: ComposerAttachment?, context: ChatLinkContext?) -> Unit,
    private val attachLateScreenshot: (id: String, screenshot: ComposerAttachment) -> Unit,
    private val attachLateContext: (id: String, context: ChatLinkContext) -> Unit,
    private val launchPersona: () -> Unit,
    private val finishSession: () -> Unit,
    private val screenshotWaitMillis: Long = SCREENSHOT_WAIT_MILLIS,
    private val assistWaitMillis: Long = ASSIST_WAIT_MILLIS,
    private val idleWaitMillis: Long = IDLE_WAIT_MILLIS,
    private val saveWaitMillis: Long = SAVE_WAIT_MILLIS,
) {
    private var assistReceived = false
    private var assistFromFocused = false
    private var screenshotReceived = false
    private var completing = false
    private var launched = false
    private var pendingContext: ChatLinkContext? = null
    private var saveJob: Deferred<ComposerAttachment?>? = null
    private var deadlineJob: Job? = null

    fun start() {
        val initialWait = when {
            expectScreenshot -> maxOf(screenshotWaitMillis, assistWaitMillis)
            expectAssist -> assistWaitMillis
            else -> idleWaitMillis
        }
        scheduleDeadline(initialWait)
    }

    fun onAssist(context: ChatLinkContext?, isFocused: Boolean = true) {
        if (launched || completing) {
            if (context != null && (!assistReceived || (isFocused && !assistFromFocused))) {
                assistReceived = true
                assistFromFocused = assistFromFocused || isFocused
                attachLateContext(handoffId, context)
            }
            return
        }
        if (assistReceived && assistFromFocused && !isFocused) return
        assistReceived = true
        assistFromFocused = assistFromFocused || isFocused
        if (context != null) pendingContext = context
        if (!expectScreenshot || screenshotReceived) {
            triggerComplete()
        } else {
            scheduleDeadline(screenshotWaitMillis)
        }
    }

    fun onScreenshot(bitmap: Bitmap?) {
        screenshotReceived = true
        if (bitmap != null && saveJob == null) {
            val job = ioScope.async {
                try {
                    saveScreenshot(bitmap)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            saveJob = job
            if (launched || completing) {
                deliverLateScreenshot(job)
                return
            }
        }
        if (launched || completing) return
        if (!expectAssist || assistReceived) {
            triggerComplete()
        } else {
            scheduleDeadline(assistWaitMillis)
        }
    }

    fun onSessionClosed() {
        deadlineJob?.cancel()
        deadlineJob = null
    }

    private fun scheduleDeadline(delayMillis: Long) {
        if (completing || launched) return
        deadlineJob?.cancel()
        deadlineJob = mainScope.launch {
            delay(delayMillis)
            triggerComplete()
        }
    }

    private fun triggerComplete() {
        if (completing || launched) return
        completing = true
        deadlineJob?.cancel()
        deadlineJob = null
        mainScope.launch {
            val job = saveJob
            var readyAttachment: ComposerAttachment? = null
            var waitingInBackground = false
            if (job != null) {
                if (job.isCompleted) {
                    readyAttachment = runCatching { job.await() }.getOrNull()
                } else {
                    val result = withTimeoutOrNull(saveWaitMillis) {
                        runCatching { job.await() }.getOrNull()
                    }
                    if (job.isCompleted) {
                        readyAttachment = result
                    } else {
                        waitingInBackground = true
                    }
                }
            }
            publishDraft(handoffId, readyAttachment, pendingContext)
            launched = true
            if (waitingInBackground && job != null) {
                deliverLateScreenshot(job)
            }
            launchPersona()
            finishSession()
        }
    }

    private fun deliverLateScreenshot(job: Deferred<ComposerAttachment?>) {
        ioScope.launch {
            val attachment = runCatching { job.await() }.getOrNull() ?: return@launch
            attachLateScreenshot(handoffId, attachment)
        }
    }

    companion object {
        const val SCREENSHOT_WAIT_MILLIS = 600L
        const val ASSIST_WAIT_MILLIS = 250L
        const val IDLE_WAIT_MILLIS = 150L
        const val SAVE_WAIT_MILLIS = 300L
    }
}
