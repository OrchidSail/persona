package persona.android.assist

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Item
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DigitalAssistantModelCatalog
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.contracts.api.DigitalAssistantModelGroup
import persona.core.contracts.api.DigitalAssistantModelOption
import persona.feature.chat.ChatLinkContext
import persona.feature.chat.ComposerAttachment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AssistOverlayControllerTest {
    private val mainKey = SessionKey("machine-1", "main-1")
    private val sideKey = SessionKey("machine-1", "quick-side-1")
    private val fastPick = ModelPick(launcher = "gemini", model = "gemini-2.5-flash")
    private val fastChoice = DigitalAssistantModelChoice(
        pick = fastPick,
        modelLabel = "Gemini 2.5 Flash",
        launcherLabel = "Gemini",
    )
    private val sampleContext = ChatLinkContext(
        kind = "context",
        title = "Pricing Page",
        url = "https://example.com/pricing",
        text = "Pricing Page\nhttps://example.com/pricing",
        id = "ctx-1",
    )

    private data class PublishedDraft(
        val id: String,
        val screenshot: ComposerAttachment?,
        val context: ChatLinkContext?,
        val text: String,
        val targetSession: SessionKey?,
    )

    private class Harness(
        scope: TestScope,
        timeoutMs: Long = 4_000L,
        initialModelChoice: DigitalAssistantModelChoice = DigitalAssistantModelChoice(),
        private val sideConversationKey: SessionKey = SessionKey("machine-1", "quick-side-1"),
    ) {
        val preparedBitmaps = mutableListOf<Bitmap>()
        var saveCount = 0
        val deletedAttachments = mutableListOf<ComposerAttachment>()
        val sentTexts = mutableListOf<Pair<String, String>>()
        val sentWithScreen = mutableListOf<Triple<String, ComposerAttachment, ChatLinkContext?>>()
        val createdSidePicks = mutableListOf<ModelPick>()
        val sentSessionTexts = mutableListOf<Triple<SessionKey, String, String>>()
        val sentSessionScreens = mutableListOf<Triple<SessionKey, String, ComposerAttachment>>()
        val observedSessions = mutableListOf<SessionKey?>()
        val persistedChoices = mutableListOf<DigitalAssistantModelChoice>()
        var sideCreateFailure: Exception? = null
        var catalogLoads = 0
        var catalogResponse = DigitalAssistantModelCatalog(
            groups = listOf(
                DigitalAssistantModelGroup(
                    launcher = "gemini",
                    title = "Gemini",
                    options = listOf(
                        DigitalAssistantModelOption(
                            pick = ModelPick("gemini", "gemini-2.5-flash"),
                            modelLabel = "Gemini 2.5 Flash",
                            launcherLabel = "Gemini",
                        ),
                    ),
                ),
            ),
        )
        val publishedDrafts = mutableListOf<PublishedDraft>()
        val launchedTargets = mutableListOf<SessionKey?>()
        var wakeCount = 0
        var dismissCount = 0
        private var idSeq = 0

        val controller = AssistOverlayController(
            mainScope = scope,
            prepareScreenshot = { src ->
                Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { preparedBitmaps += it }
            },
            createThumbnail = { _ ->
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            },
            saveScreenshot = { _, id ->
                saveCount++
                ComposerAttachment(
                    id = "assist-$id-$saveCount",
                    name = "assist-$id-$saveCount.jpg",
                    uri = "content://com.example.persona.chat.files/assist/assist-$id-$saveCount.jpg",
                    image = true,
                    size = 1024L,
                )
            },
            deleteScreenshot = { deletedAttachments += it },
            sendTextToMain = { prompt, clientId -> sentTexts += prompt to clientId },
            sendWithScreenToMain = { prompt, shot, ctx, _ -> sentWithScreen += Triple(prompt, shot, ctx) },
            createSideConversation = { pick ->
                createdSidePicks += pick
                sideCreateFailure?.let { throw it }
                sideConversationKey
            },
            sendTextToSession = { session, prompt, clientId ->
                sentSessionTexts += Triple(session, prompt, clientId)
            },
            sendWithScreenToSession = { session, prompt, shot, _, _ ->
                sentSessionScreens += Triple(session, prompt, shot)
            },
            onActiveSessionChanged = { observedSessions += it },
            loadModelCatalog = {
                catalogLoads++
                catalogResponse
            },
            onPersistDefaultModel = { persistedChoices += DigitalAssistantModelChoice() },
            onPersistModelChoice = { persistedChoices += it },
            publishDraftForFullApp = { id, shot, ctx, text, target ->
                publishedDrafts += PublishedDraft(id, shot, ctx, text, target)
            },
            onWakeHub = { wakeCount++ },
            onLaunchFullApp = { launchedTargets += it },
            onDismiss = { dismissCount++ },
            screenOnlyDisplayLabel = { "Please look at this screenshot." },
            defaultSendError = { "Couldn't send message" },
            defaultModelStartError = { "Selected model is unavailable right now" },
            initialModelChoice = initialModelChoice,
            screenshotWaitTimeoutMillis = timeoutMs,
            nextId = { "id-${++idSeq}" },
            log = {},
        )
    }

    private fun sampleBitmap(): Bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
    }

    @Test
    fun screenshotStaysInMemoryAndIsNotSavedOrAttachedUntilManualConfirmation() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))

        controller.beginSession(supportsScreenshot = true, showFlags = 4)
        assertEquals(1, harness.wakeCount)
        assertEquals(AssistCaptureStatus.Waiting, controller.state.value.captureStatus)

        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()

        // Never written to disk and never attached by default.
        assertEquals(0, harness.saveCount)
        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)
        assertNull(controller.state.value.attachedScreenshot)
        assertNull(controller.state.value.attachedContext)
        assertTrue(controller.state.value.hasStagedContext)

        // Manual tap on "Attach current screen" persists to private cache and attaches preview + safe context.
        controller.attachCurrentScreen()
        runCurrent()

        assertEquals(1, harness.saveCount)
        assertEquals(AssistCaptureStatus.Attached, controller.state.value.captureStatus)
        assertNotNull(controller.state.value.attachedScreenshot)
        assertNotNull(controller.state.value.attachedThumbnail)
        assertEquals(sampleContext, controller.state.value.attachedContext)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun removeAttachedScreenDeletesCacheFileClearsContextAndAllowsReattach() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.beginSession(supportsScreenshot = true)

        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()

        controller.attachCurrentScreen()
        runCurrent()
        val firstSaved = controller.state.value.attachedScreenshot!!

        controller.removeAttachedScreen()
        runCurrent()

        assertEquals(listOf(firstSaved), harness.deletedAttachments)
        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)
        assertNull(controller.state.value.attachedScreenshot)
        assertNull(controller.state.value.attachedContext)
        assertNull(controller.state.value.attachedThumbnail)

        // User can re-attach before sending.
        controller.attachCurrentScreen()
        runCurrent()
        assertEquals(2, harness.saveCount)
        assertEquals(AssistCaptureStatus.Attached, controller.state.value.captureStatus)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun textOnlySendReleasesStagedBitmapAndNeverSavesOrAttachesScreen() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))
        controller.beginSession(supportsScreenshot = true)

        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()
        assertEquals(1, harness.preparedBitmaps.size)
        assertFalse(harness.preparedBitmaps.single().isRecycled)

        controller.updateDraftText("What is the weather?")
        controller.send()
        runCurrent()

        // Staged bitmap recycled immediately; zero disk writes; pure text sent directly to main chat.
        assertEquals(0, harness.saveCount)
        assertTrue(harness.preparedBitmaps.single().isRecycled)
        assertTrue(harness.sentWithScreen.isEmpty())
        assertTrue(harness.createdSidePicks.isEmpty())
        assertEquals(1, harness.sentTexts.size)
        assertEquals("What is the weather?", harness.sentTexts.single().first)
        assertEquals(AssistCaptureStatus.NotSupported, controller.state.value.captureStatus)
        assertTrue(controller.state.value.expanded)
        assertEquals(1, controller.state.value.turns.size)
        assertTrue(controller.state.value.awaitingReply)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun dismissReleasesStagedBitmapAndDeletesUnsentAttachedCacheFile() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.beginSession(supportsScreenshot = true)

        val shot1 = sampleBitmap()
        controller.onScreenshotReceived(shot1)
        runCurrent()
        controller.endSession()
        runCurrent()

        assertEquals(0, harness.saveCount)
        assertTrue(harness.preparedBitmaps[0].isRecycled)

        // Second session: user attaches screen, then dismisses without sending -> temp file must be deleted.
        controller.beginSession(supportsScreenshot = true)
        val shot2 = sampleBitmap()
        controller.onScreenshotReceived(shot2)
        runCurrent()
        controller.attachCurrentScreen()
        runCurrent()
        val saved = controller.state.value.attachedScreenshot!!

        controller.endSession()
        runCurrent()

        assertTrue(harness.preparedBitmaps[1].isRecycled)
        assertEquals(listOf(saved), harness.deletedAttachments)

        shot1.recycle()
        shot2.recycle()
    }

    @Test
    fun flagSecureNullScreenshotIsDistinctFromTimeoutAndStillAllowsTextSend() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))
        controller.beginSession(supportsScreenshot = true)

        controller.onScreenshotReceived(null)
        runCurrent()

        assertEquals(AssistCaptureStatus.Unavailable, controller.state.value.captureStatus)
        assertEquals(0, harness.saveCount)

        controller.updateDraftText("Summarize my last meeting")
        assertTrue(controller.state.value.canSend)
        controller.send()
        runCurrent()

        assertEquals(1, harness.sentTexts.size)
        assertEquals("Summarize my last meeting", harness.sentTexts.single().first)
        controller.endSession()
        runCurrent()
    }

    @Test
    fun screenshotWaitTimeoutReportsTimedOutNotProtectedAndStillUpgradesOnLateBitmap() = runTest {
        val harness = Harness(this, timeoutMs = 1_000L)
        val controller = harness.controller
        controller.beginSession(supportsScreenshot = true)
        assertEquals(AssistCaptureStatus.Waiting, controller.state.value.captureStatus)

        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(AssistCaptureStatus.TimedOut, controller.state.value.captureStatus)

        // Late onHandleScreenshot from a busy compositor still upgrades seamlessly to Available.
        val shot = sampleBitmap()
        controller.onScreenshotReceived(shot)
        runCurrent()
        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)
        assertEquals(0, harness.saveCount)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun lockedDeviceBlocksCaptureContextSendAndHandoffDrafts() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))
        controller.beginSession(supportsScreenshot = true, showFlags = 0, deviceLocked = true)

        assertTrue(controller.state.value.deviceLocked)
        assertEquals(AssistCaptureStatus.NotSupported, controller.state.value.captureStatus)
        assertEquals(0, harness.wakeCount)

        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()

        assertEquals(0, harness.preparedBitmaps.size)
        assertEquals(0, harness.saveCount)
        assertFalse(controller.state.value.hasStagedContext)

        controller.updateDraftText("Secret locked query")
        assertFalse(controller.state.value.canSend)
        controller.send()
        runCurrent()
        assertTrue(harness.sentTexts.isEmpty())

        controller.openInPersona()
        assertTrue(harness.publishedDrafts.isEmpty())
        assertEquals(listOf<SessionKey?>(null), harness.launchedTargets)
        assertEquals(1, harness.dismissCount)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun dedicatedOverlayModelCreatesNonMainSessionReusesItOnFollowUpsAndOpensExactSession() = runTest {
        val harness = Harness(this, initialModelChoice = fastChoice, sideConversationKey = sideKey)
        val controller = harness.controller
        val mainHistoryItem = Item(id = "main-old", role = "assistant", kind = "text", text = "Main history")
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                observedSession = mainKey,
                chatSnapshot = ChatSnapshot(items = listOf(mainHistoryItem), loading = false),
            ),
        )
        controller.beginSession(supportsScreenshot = false)

        // First send creates a lightweight side conversation with the chosen ModelPick (never main).
        controller.updateDraftText("Quick translate hello")
        controller.send()
        runCurrent()

        assertEquals(listOf(fastPick), harness.createdSidePicks)
        assertTrue(harness.sentTexts.isEmpty())
        assertEquals(1, harness.sentSessionTexts.size)
        assertEquals(sideKey, harness.sentSessionTexts[0].first)
        assertEquals("Quick translate hello", harness.sentSessionTexts[0].second)
        assertEquals(sideKey, controller.state.value.activeSession)
        assertEquals(sideKey, harness.observedSessions.last())

        // While bridge is still switching from main -> side, main chat history must not leak into overlay turns.
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                observedSession = mainKey,
                chatSnapshot = ChatSnapshot(items = listOf(mainHistoryItem), loading = false),
            ),
        )
        assertEquals(1, controller.state.value.turns.size)
        assertTrue(controller.state.value.turns.single().fromUser)

        // Streaming reply arrives on the dedicated side conversation.
        val firstClientId = harness.sentSessionTexts[0].third
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                observedSession = sideKey,
                mainWorking = false,
                chatSnapshot = ChatSnapshot(
                    items = listOf(
                        Item(id = "su-1", role = "user", kind = "text", text = "Quick translate hello", clientMsgId = firstClientId),
                        Item(id = "sa-1", role = "assistant", kind = "text", text = "你好"),
                    ),
                    loading = false,
                ),
            ),
        )
        assertEquals(2, controller.state.value.turns.size)
        assertEquals("你好", controller.state.value.turns[1].text)

        // Follow-up in the same overlay session reuses sideKey instead of creating a second conversation.
        controller.updateDraftText("And in Japanese?")
        controller.send()
        runCurrent()
        assertEquals(1, harness.createdSidePicks.size)
        assertEquals(2, harness.sentSessionTexts.size)
        assertEquals(sideKey, harness.sentSessionTexts[1].first)

        // "Open in Persona" opens the exact side session and claims any unsent draft for it.
        controller.updateDraftText("Draft for full app")
        controller.openInPersona()
        assertEquals(listOf<SessionKey?>(sideKey), harness.launchedTargets)
        assertEquals(sideKey, harness.publishedDrafts.single().targetSession)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun dedicatedModelFailureOffersOneTapFallbackToMainWithoutLosingDraftOrScreen() = runTest {
        val harness = Harness(this, initialModelChoice = fastChoice)
        harness.sideCreateFailure = IllegalStateException("Launcher gemini is offline")
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))
        controller.beginSession(supportsScreenshot = true)

        val shot = sampleBitmap()
        controller.onScreenshotReceived(shot)
        runCurrent()
        controller.attachCurrentScreen()
        runCurrent()
        val confirmed = controller.state.value.attachedScreenshot!!

        controller.updateDraftText("Explain this error")
        controller.send()
        runCurrent()

        assertEquals("Launcher gemini is offline", controller.state.value.errorMessage)
        assertTrue(controller.state.value.modelFallbackAvailable)
        assertEquals("Explain this error", controller.state.value.draftText)
        assertEquals(confirmed, controller.state.value.attachedScreenshot)
        assertTrue(harness.deletedAttachments.isEmpty())

        // One-tap fallback retries cleanly against Main chat without mutating main chat's model.
        controller.retryWithDefaultMain()
        runCurrent()

        assertFalse(controller.state.value.modelFallbackAvailable)
        assertNull(controller.state.value.errorMessage)
        assertEquals(1, harness.sentWithScreen.size)
        assertEquals("Explain this error", harness.sentWithScreen.single().first)
        assertEquals(confirmed, harness.sentWithScreen.single().second)
        assertEquals(listOf(confirmed), harness.deletedAttachments)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun modelPickerLoadsCatalogPersistsSelectionAndSyncsDefaultSwitch() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))
        controller.beginSession(supportsScreenshot = false)

        controller.toggleModelPicker()
        runCurrent()
        assertTrue(controller.state.value.modelPickerOpen)
        assertEquals(1, harness.catalogLoads)
        assertEquals(1, controller.state.value.modelCatalog.groups.size)

        controller.selectModel(fastChoice)
        assertFalse(controller.state.value.modelPickerOpen)
        assertEquals(fastChoice, controller.state.value.modelChoice)
        assertEquals(listOf(fastChoice), harness.persistedChoices)

        controller.selectDefaultModel()
        assertTrue(controller.state.value.modelChoice.isDefault)
        assertEquals(2, harness.persistedChoices.size)
        assertTrue(harness.persistedChoices.last().isDefault)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun sendWithConfirmedScreenUploadsDeletesTempFileAndStreamsRepliesInOverlay() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        val earlierItem = Item(id = "old-1", role = "assistant", kind = "text", text = "Earlier history")
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                chatSnapshot = ChatSnapshot(items = listOf(earlierItem), loading = false),
            ),
        )
        controller.beginSession(supportsScreenshot = true)

        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()
        controller.attachCurrentScreen()
        runCurrent()
        val saved = controller.state.value.attachedScreenshot!!

        controller.updateDraftText("Explain this chart")
        controller.send()
        runCurrent()

        assertEquals(1, harness.sentWithScreen.size)
        assertEquals("Explain this chart", harness.sentWithScreen.single().first)
        assertEquals(saved, harness.sentWithScreen.single().second)
        assertEquals(sampleContext, harness.sentWithScreen.single().third)
        assertEquals(listOf(saved), harness.deletedAttachments)
        assertTrue(harness.preparedBitmaps.single().isRecycled)

        val sentClientId = controller.state.value.turns.single().id.removePrefix("pending:")

        // Streaming assistant reply arrives on the Hub main conversation.
        val userEcho = Item(
            id = "u-1",
            role = "user",
            kind = "text",
            text = "${sampleContext.xml()}\n\nExplain this chart\n\n<attached_images>\n/tmp/a.jpg\n</attached_images>",
            clientMsgId = sentClientId,
        )
        val partialReply = Item(id = "a-1", role = "assistant", kind = "text", text = "The chart shows")
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                mainWorking = true,
                chatSnapshot = ChatSnapshot(
                    items = listOf(earlierItem, userEcho, partialReply),
                    loading = false,
                    streamingItemIds = setOf("a-1"),
                ),
            ),
        )

        // Earlier history stays hidden; overlay shows only this session's turns and live streaming state.
        val streamingTurns = controller.state.value.turns
        assertEquals(2, streamingTurns.size)
        assertTrue(streamingTurns[0].fromUser)
        assertEquals("Explain this chart", streamingTurns[0].text)
        assertTrue(streamingTurns[0].hasScreenAttachment)
        assertFalse(streamingTurns[1].fromUser)
        assertEquals("The chart shows", streamingTurns[1].text)
        assertTrue(streamingTurns[1].streaming)
        assertFalse(controller.state.value.awaitingReply)

        // Reply finishes streaming.
        val finalReply = partialReply.copy(text = "The chart shows steady growth.")
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                mainWorking = false,
                chatSnapshot = ChatSnapshot(
                    items = listOf(earlierItem, userEcho, finalReply),
                    loading = false,
                    streamingItemIds = emptySet(),
                ),
            ),
        )
        assertEquals("The chart shows steady growth.", controller.state.value.turns[1].text)
        assertFalse(controller.state.value.turns[1].streaming)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun openInPersonaHandsOffOnlyManuallyConfirmedDraftAndPreservesItsCacheFile() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))

        // Case 1: unconfirmed screenshot in memory -> must NOT hand off to MainActivity draft.
        controller.beginSession(supportsScreenshot = true)
        val shot1 = sampleBitmap()
        controller.onScreenshotReceived(shot1)
        runCurrent()
        controller.openInPersona()
        controller.endSession()
        runCurrent()

        assertTrue(harness.publishedDrafts.isEmpty())
        assertEquals(listOf<SessionKey?>(mainKey), harness.launchedTargets)
        assertEquals(1, harness.dismissCount)
        assertTrue(harness.preparedBitmaps[0].isRecycled)

        // Case 2: manually confirmed screenshot + unsent text -> handed off and cache file preserved.
        controller.beginSession(supportsScreenshot = true)
        val shot2 = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot2)
        runCurrent()
        controller.attachCurrentScreen()
        runCurrent()
        val confirmedShot = controller.state.value.attachedScreenshot!!
        controller.updateDraftText("Continue in full app")

        controller.openInPersona()
        controller.endSession()
        runCurrent()

        assertEquals(1, harness.publishedDrafts.size)
        val handoff = harness.publishedDrafts.single()
        assertEquals(confirmedShot, handoff.screenshot)
        assertEquals(sampleContext, handoff.context)
        assertEquals("Continue in full app", handoff.text)
        assertEquals(mainKey, handoff.targetSession)
        assertTrue("Handed-off screenshot must not be deleted on overlay close", harness.deletedAttachments.isEmpty())

        shot1.recycle()
        shot2.recycle()
    }

    @Test
    fun wireFormattingAndHubStatusMappingCoverOfflineAndImageCases() {
        val formatted = formatAssistWireMessage(
            prompt = "",
            remoteImagePath = "/tmp/assist-1.jpg",
            contextCard = sampleContext,
            photosOnlyPrompt = "Please look at this screenshot.",
        )
        assertTrue(formatted.startsWith("<persona-context "))
        assertTrue(formatted.contains("Please look at this screenshot."))
        assertTrue(formatted.contains("<attached_images>\n/tmp/assist-1.jpg\n</attached_images>"))

        assertEquals(AssistHubStatus.Ready, mapConnectionStateToHubStatus(ConnectionState.Online, hasBinding = true))
        assertEquals(AssistHubStatus.Connecting, mapConnectionStateToHubStatus(ConnectionState.Connecting, hasBinding = true))
        assertEquals(AssistHubStatus.Offline, mapConnectionStateToHubStatus(ConnectionState.WaitingForNetwork, hasBinding = true))
        assertEquals(AssistHubStatus.Offline, mapConnectionStateToHubStatus(ConnectionState.Revoked, hasBinding = true))
    }

    @Test
    fun screenshotDisabledBySettingsReportsImmediatelyWithoutWaitingForTimeout() = runTest {
        val harness = Harness(this, timeoutMs = 4_000L)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))

        controller.beginSession(
            supportsScreenshot = true,
            showFlags = 4,
            deviceLocked = false,
            screenshotDisabledBySettings = true,
            expectScreenshotFromFlags = false,
        )

        // Immediately reports DisabledBySettings at t=0ms instead of spinning for 4s timeout.
        assertEquals(AssistCaptureStatus.DisabledBySettings, controller.state.value.captureStatus)

        // Pure text send remains completely unblocked.
        controller.updateDraftText("Explain this concept")
        assertTrue(controller.state.value.canSend)
        controller.send()
        runCurrent()
        assertEquals(1, harness.sentTexts.size)
        assertEquals("Explain this concept", harness.sentTexts.single().first)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun missingScreenshotFlagUsesShortGracePeriodAndStillUpgradesOnLateBitmap() = runTest {
        val harness = Harness(this, timeoutMs = 4_000L)
        val controller = harness.controller
        controller.beginSession(
            supportsScreenshot = true,
            showFlags = 4,
            deviceLocked = false,
            screenshotDisabledBySettings = false,
            expectScreenshotFromFlags = false,
        )
        assertEquals(AssistCaptureStatus.Waiting, controller.state.value.captureStatus)

        // Finishes after short 1.2s grace period instead of waiting the full 4s timeout.
        advanceTimeBy(AssistOverlayController.NO_FLAG_SCREENSHOT_GRACE_MILLIS + 100L)
        runCurrent()
        assertEquals(AssistCaptureStatus.TimedOut, controller.state.value.captureStatus)

        // Late screenshot callback still upgrades cleanly.
        val shot = sampleBitmap()
        controller.onScreenshotReceived(shot)
        runCurrent()
        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }

    @Test
    fun settingsProbeDetectsDisabledScreenshotFromSystemFlagsAndSecureSetting() {
        assertFalse(
            AssistScreenshotSettingsProbe(
                disabledShowContext = 0,
                userDisabledShowContext = 0,
                secureAssistScreenshotEnabled = 1,
                secureAssistStructureEnabled = 1,
            ).screenshotDisabledBySettings,
        )
        assertTrue(
            AssistScreenshotSettingsProbe(
                disabledShowContext = 0,
                userDisabledShowContext = android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                secureAssistScreenshotEnabled = 1,
                secureAssistStructureEnabled = 1,
            ).screenshotDisabledBySettings,
        )
        assertTrue(
            AssistScreenshotSettingsProbe(
                disabledShowContext = android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                userDisabledShowContext = 0,
                secureAssistScreenshotEnabled = 1,
                secureAssistStructureEnabled = 1,
            ).screenshotDisabledBySettings,
        )
        assertTrue(
            AssistScreenshotSettingsProbe(
                disabledShowContext = 0,
                userDisabledShowContext = 0,
                secureAssistScreenshotEnabled = 0,
                secureAssistStructureEnabled = 1,
            ).screenshotDisabledBySettings,
        )
    }
}
