package persona.android.assist

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OverlayDictationHandleTest {
    private val stoppedMessage = "Voice input stopped"

    private fun dictationIntent(language: String = "zh-CN"): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private fun wordsBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test
    fun onDeviceLanguageNotSupportedFallsBackToSystemRecognizerAndCachesFallback() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        assertTrue(handle.listening)
        assertEquals(1, onDeviceEngines.size)
        assertEquals(0, systemEngines.size)

        onDeviceEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertEquals(1, onDeviceEngines.single().stopCalls)
        assertEquals(1, onDeviceEngines.single().destroyCalls)
        assertEquals(1, systemEngines.size)
        assertEquals(1, systemEngines.single().startedIntents.size)
        assertSame(intent, systemEngines.single().startedIntents.single())
        assertTrue("Fallback should keep listening seamlessly", handle.listening)
        assertTrue("Seamless fallback must not surface an error notice", errors.isEmpty())

        systemEngines.single().emitResults("hello from system recognizer")
        assertFalse(handle.listening)
        assertEquals(listOf("hello from system recognizer"), heard)
        assertTrue(errors.isEmpty())
        assertEquals(1, systemEngines.single().destroyCalls)

        // Subsequent dictation in the same session goes directly to the system default recognizer.
        handle.toggle()
        assertTrue(handle.listening)
        assertEquals(1, onDeviceEngines.size)
        assertEquals(2, systemEngines.size)

        systemEngines.last().emitResults("second utterance")
        assertFalse(handle.listening)
        assertEquals(listOf("hello from system recognizer", "second utterance"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun onDeviceLanguageUnavailableFallsBackToSystemRecognizerAndDeliversResults() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        onDeviceEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertEquals(1, onDeviceEngines.single().destroyCalls)
        assertEquals(1, systemEngines.size)
        assertTrue(handle.listening)
        assertTrue(errors.isEmpty())

        systemEngines.single().emitResults("downloaded pack fallback")
        assertFalse(handle.listening)
        assertEquals(listOf("downloaded pack fallback"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun onDeviceStartListeningFailureFallsBackToSystemRecognizer() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = {
                FakeOverlaySpeechEngine(
                    onStartListening = { _, _ -> throw IllegalStateException("on-device unavailable") },
                ).also { onDeviceEngines += it }
            },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        assertEquals(1, onDeviceEngines.size)
        assertEquals(1, onDeviceEngines.single().destroyCalls)
        assertEquals(1, systemEngines.size)
        assertTrue(handle.listening)
        assertTrue(errors.isEmpty())

        systemEngines.single().emitResults("recovered after start exception")
        assertFalse(handle.listening)
        assertEquals(listOf("recovered after start exception"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun onDeviceCreationFailureFallsBackToSystemRecognizer() {
        val intent = dictationIntent("zh-CN")
        var onDeviceAttempts = 0
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = {
                onDeviceAttempts++
                null
            },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        assertEquals(1, onDeviceAttempts)
        assertEquals(1, systemEngines.size)
        assertTrue(handle.listening)
        assertTrue(errors.isEmpty())

        systemEngines.single().emitResults("created system fallback")
        assertFalse(handle.listening)
        assertEquals(listOf("created system fallback"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun fallbackFailureNotifiesErrorOnceWithoutInfiniteRetry() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { {} },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        onDeviceEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(1, systemEngines.size)
        assertTrue(handle.listening)
        assertTrue(errors.isEmpty())

        // Even if the system fallback also fails with ERROR_LANGUAGE_NOT_SUPPORTED, stop once and report.
        systemEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertFalse(handle.listening)
        assertEquals(1, onDeviceEngines.size)
        assertEquals(1, systemEngines.size)
        assertEquals(1, systemEngines.single().destroyCalls)
        assertEquals(listOf(stoppedMessage), errors)
    }

    @Test
    fun fallbackStartFailureNotifiesErrorOnceWithoutInfiniteRetry() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = {
                FakeOverlaySpeechEngine(
                    onStartListening = { _, _ -> throw IllegalStateException("system recognizer busy") },
                ).also { systemEngines += it }
            },
            intent = intent,
            onHeard = { {} },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        onDeviceEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        assertFalse(handle.listening)
        assertEquals(1, onDeviceEngines.size)
        assertEquals(1, systemEngines.size)
        assertEquals(1, systemEngines.single().destroyCalls)
        assertEquals(listOf(stoppedMessage), errors)
    }

    @Test
    fun noSystemRecognizerReportsErrorOnceWithoutFallback() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        var systemCreations = 0
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { false },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = {
                systemCreations++
                FakeOverlaySpeechEngine()
            },
            intent = intent,
            onHeard = { {} },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        onDeviceEngines.single().emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertFalse(handle.listening)
        assertEquals(0, systemCreations)
        assertEquals(1, onDeviceEngines.single().destroyCalls)
        assertEquals(listOf(stoppedMessage), errors)
    }

    @Test
    fun noMatchAndSpeechTimeoutStopQuietlyWithoutFallbackOrError() {
        val intent = dictationIntent("en-US")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        var systemCreations = 0
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = {
                systemCreations++
                FakeOverlaySpeechEngine()
            },
            intent = intent,
            onHeard = { {} },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        onDeviceEngines.last().emitError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(handle.listening)
        assertEquals(0, systemCreations)
        assertTrue(errors.isEmpty())

        // Silence timeout must not disable on-device recognition for later attempts.
        handle.toggle()
        assertEquals(2, onDeviceEngines.size)
        onDeviceEngines.last().emitError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertFalse(handle.listening)
        assertEquals(0, systemCreations)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun staleCallbacksFromDestroyedOnDeviceEngineAreIgnoredAfterFallback() {
        val intent = dictationIntent("zh-CN")
        val onDeviceEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = { FakeOverlaySpeechEngine().also { onDeviceEngines += it } },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        val staleOnDevice = onDeviceEngines.single()
        staleOnDevice.emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        // Late events from the torn-down on-device recognizer must not disturb the active fallback session.
        staleOnDevice.emitError(SpeechRecognizer.ERROR_CLIENT)
        staleOnDevice.emitResults("stale text")

        assertTrue(handle.listening)
        assertEquals(0, systemEngines.single().destroyCalls)
        assertTrue(heard.isEmpty())
        assertTrue(errors.isEmpty())

        systemEngines.single().emitResults("fresh text")
        assertFalse(handle.listening)
        assertEquals(listOf("fresh text"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun synchronousOnErrorDuringStartListeningFallsBackCleanly() {
        val intent = dictationIntent("zh-CN")
        val systemEngines = mutableListOf<FakeOverlaySpeechEngine>()
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle(
            preferOnDevice = true,
            hasSystemRecognizer = { true },
            createOnDeviceEngine = {
                FakeOverlaySpeechEngine(
                    onStartListening = { engine, _ ->
                        engine.emitError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                    },
                )
            },
            createSystemEngine = { FakeOverlaySpeechEngine().also { systemEngines += it } },
            intent = intent,
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        assertTrue(handle.listening)
        assertEquals(1, systemEngines.size)
        assertTrue(errors.isEmpty())

        systemEngines.single().emitResults("sync fallback ok")
        assertFalse(handle.listening)
        assertEquals(listOf("sync fallback ok"), heard)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun forContextPlatformEngineDeliversResultsAndDestroysRecognizer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val heard = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val handle = OverlayDictationHandle.forContext(
            context = app,
            preferOnDevice = true,
            intent = dictationIntent("en-US"),
            onHeard = { { words -> heard += words } },
            onError = { { message -> errors += message } },
            stoppedMessage = stoppedMessage,
        )

        handle.toggle()
        assertTrue(handle.listening)
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotNull(recognizer)
        val shadow = shadowOf(recognizer)
        shadow.triggerOnResults(wordsBundle("platform recognizer result"))

        assertFalse(handle.listening)
        assertTrue(shadow.isDestroyed)
        assertEquals(listOf("platform recognizer result"), heard)
        assertTrue(errors.isEmpty())
    }

    private class FakeOverlaySpeechEngine(
        private val onStartListening: ((FakeOverlaySpeechEngine, Intent) -> Unit)? = null,
    ) : OverlaySpeechEngine {
        var listener: RecognitionListener? = null
            private set
        val startedIntents = mutableListOf<Intent>()
        var stopCalls: Int = 0
            private set
        var destroyCalls: Int = 0
            private set

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            startedIntents += intent
            onStartListening?.invoke(this, intent)
        }

        override fun stopListening() {
            stopCalls++
        }

        override fun destroy() {
            destroyCalls++
        }

        fun emitError(error: Int) {
            listener?.onError(error)
        }

        fun emitResults(vararg words: String) {
            val bundle = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(words.toList()))
            }
            listener?.onResults(bundle)
        }
    }
}
