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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Item
import persona.core.contracts.ModelPick
import persona.core.contracts.ProtocolCompatibility
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

    private data class PreparedModelCall(
        val pick: ModelPick?,
        val clearIfDefault: Boolean,
    )

    private class Harness(
        scope: TestScope,
        timeoutMs: Long = 4_000L,
        initialModelChoice: DigitalAssistantModelChoice = DigitalAssistantModelChoice(),
        private val resolvedMainSession: SessionKey? = SessionKey("machine-1", "main-1"),
    ) {
        val preparedBitmaps = mutableListOf<Bitmap>()
        var saveCount = 0
        val deletedAttachments = mutableListOf<ComposerAttachment>()
        val preparedModels = mutableListOf<PreparedModelCall>()
        val sentTexts = mutableListOf<Pair<String, String>>()
        val sentTargets = mutableListOf<SessionKey?>()
        val sentWithScreen = mutableListOf<Triple<String, ComposerAttachment, ChatLinkContext?>>()
        val persistedChoices = mutableListOf<DigitalAssistantModelChoice>()
        var modelSwitchFailure: Exception? = null
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
            prepareMainModel = { pick, clearIfDefault ->
                preparedModels += PreparedModelCall(pick, clearIfDefault)
                if (pick != null) {
                    modelSwitchFailure?.let { throw it }
                }
                resolvedMainSession
            },
            sendTextToMain = { target, prompt, clientId ->
                sentTargets += target
                sentTexts += prompt to clientId
            },
            sendWithScreenToMain = { target, prompt, shot, ctx, _ ->
                sentTargets += target
                sentWithScreen += Triple(prompt, shot, ctx)
            },
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
        assertTrue(harness.preparedModels.isEmpty())
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
    fun selectedOverlayModelSwitchesMainConversationInPlaceKeepsTurnsInMainAndClearsOnDefault() = runTest {
        val harness = Harness(this, initialModelChoice = fastChoice, resolvedMainSession = mainKey)
        val controller = harness.controller
        val mainHistoryItem = Item(id = "main-old", role = "assistant", kind = "text", text = "Main history")
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                chatSnapshot = ChatSnapshot(items = listOf(mainHistoryItem), loading = false),
            ),
        )
        controller.beginSession(supportsScreenshot = false)

        // First send switches main conversation in place to fastPick and sends directly to mainKey.
        controller.updateDraftText("Quick translate hello")
        controller.send()
        runCurrent()

        assertEquals(listOf(PreparedModelCall(fastPick, clearIfDefault = false)), harness.preparedModels)
        assertEquals(listOf<SessionKey?>(mainKey), harness.sentTargets)
        assertEquals(1, harness.sentTexts.size)
        assertEquals("Quick translate hello", harness.sentTexts[0].first)
        assertEquals(mainKey, controller.state.value.mainSession)

        // Streaming reply arrives directly on the main conversation; prior main history stays hidden.
        val firstClientId = harness.sentTexts[0].second
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                mainModelPick = fastPick,
                mainWorking = false,
                chatSnapshot = ChatSnapshot(
                    items = listOf(
                        mainHistoryItem,
                        Item(id = "mu-1", role = "user", kind = "text", text = "Quick translate hello", clientMsgId = firstClientId),
                        Item(id = "ma-1", role = "assistant", kind = "text", text = "你好"),
                    ),
                    loading = false,
                ),
            ),
        )
        assertEquals(2, controller.state.value.turns.size)
        assertEquals("你好", controller.state.value.turns[1].text)

        // Switching back to Default (Main chat) clears main's model_pick on next send and stays in mainKey.
        controller.selectDefaultModel()
        controller.updateDraftText("And in Japanese?")
        controller.send()
        runCurrent()

        assertEquals(
            listOf(
                PreparedModelCall(fastPick, clearIfDefault = false),
                PreparedModelCall(null, clearIfDefault = true),
            ),
            harness.preparedModels,
        )
        assertEquals(listOf<SessionKey?>(mainKey, mainKey), harness.sentTargets)
        assertEquals(2, harness.sentTexts.size)

        // "Open in Persona" opens mainKey seamlessly carrying any unsent draft.
        controller.updateDraftText("Draft for full app")
        controller.openInPersona()
        assertEquals(listOf<SessionKey?>(mainKey), harness.launchedTargets)
        assertEquals(mainKey, harness.publishedDrafts.single().targetSession)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun firstTimePairingWithCustomModelCreatesMainConversationAndOpensIt() = runTest {
        val newlyCreatedMain = SessionKey("machine-1", "main-created-1")
        val harness = Harness(this, initialModelChoice = fastChoice, resolvedMainSession = newlyCreatedMain)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = null))
        controller.beginSession(supportsScreenshot = false)

        controller.updateDraftText("Hello first main turn")
        controller.send()
        runCurrent()

        assertEquals(listOf(PreparedModelCall(fastPick, clearIfDefault = false)), harness.preparedModels)
        assertEquals(listOf<SessionKey?>(newlyCreatedMain), harness.sentTargets)
        assertEquals(newlyCreatedMain, controller.state.value.mainSession)

        controller.openInPersona()
        assertEquals(listOf<SessionKey?>(newlyCreatedMain), harness.launchedTargets)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun defaultModelSkipsExtraRpcWhenMainHasNoPickAndClearsWhenMainHasPick() = runTest {
        val harness = Harness(this, resolvedMainSession = mainKey)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey, mainModelPick = null))
        controller.beginSession(supportsScreenshot = false)

        // Main already on default -> zero extra prepareMainModel RPC.
        controller.updateDraftText("Plain default question")
        controller.send()
        runCurrent()
        assertTrue(harness.preparedModels.isEmpty())
        assertEquals(listOf<SessionKey?>(mainKey), harness.sentTargets)

        // If main currently has a custom pick on the Hub, Default clears it before sending.
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey, mainModelPick = fastPick))
        controller.updateDraftText("Back to default")
        controller.send()
        runCurrent()
        assertEquals(listOf(PreparedModelCall(null, clearIfDefault = true)), harness.preparedModels)
        assertEquals(listOf<SessionKey?>(mainKey, mainKey), harness.sentTargets)

        controller.endSession()
        runCurrent()
    }

    @Test
    fun switchingMainModelFailureOffersOneTapFallbackToMainDefaultWithoutLosingDraftOrScreen() = runTest {
        val harness = Harness(this, initialModelChoice = fastChoice, resolvedMainSession = mainKey)
        harness.modelSwitchFailure = IllegalStateException("Launcher gemini is offline")
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

        // One-tap fallback clears/skips custom model and retries cleanly on Main default.
        controller.retryWithDefaultMain()
        runCurrent()

        assertFalse(controller.state.value.modelFallbackAvailable)
        assertNull(controller.state.value.errorMessage)
        assertEquals(
            listOf(
                PreparedModelCall(fastPick, clearIfDefault = false),
                PreparedModelCall(null, clearIfDefault = true),
            ),
            harness.preparedModels,
        )
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

    @Test
    fun outOfOrderNullScreenshotBeforeOnPrepareShowCorrectsToDisabledBySettings() = runTest {
        // Case A: onHandleScreenshot(null) arrives 1ms before onPrepareShow detects disabled settings.
        val harnessA = Harness(this)
        val controllerA = harnessA.controller
        controllerA.onScreenshotReceived(bitmap = null, screenshotDisabledBySettings = false)
        assertEquals(AssistCaptureStatus.Unavailable, controllerA.state.value.captureStatus)

        controllerA.beginSession(
            supportsScreenshot = true,
            showFlags = 4,
            deviceLocked = false,
            screenshotDisabledBySettings = true,
            expectScreenshotFromFlags = false,
        )
        assertEquals(AssistCaptureStatus.DisabledBySettings, controllerA.state.value.captureStatus)
        controllerA.endSession()
        runCurrent()

        // Case B: onHandleScreenshot(null) itself probes disabled settings before onPrepareShow.
        val harnessB = Harness(this)
        val controllerB = harnessB.controller
        controllerB.onScreenshotReceived(bitmap = null, screenshotDisabledBySettings = true)
        assertEquals(AssistCaptureStatus.DisabledBySettings, controllerB.state.value.captureStatus)
        controllerB.endSession()
        runCurrent()

        // Case C: onHandleScreenshot(null) arriving after DisabledBySettings never regresses to Unavailable.
        val harnessC = Harness(this)
        val controllerC = harnessC.controller
        controllerC.beginSession(
            supportsScreenshot = true,
            showFlags = 4,
            deviceLocked = false,
            screenshotDisabledBySettings = true,
            expectScreenshotFromFlags = false,
        )
        controllerC.onScreenshotReceived(bitmap = null, screenshotDisabledBySettings = false)
        assertEquals(AssistCaptureStatus.DisabledBySettings, controllerC.state.value.captureStatus)
        controllerC.endSession()
        runCurrent()

        // Case D: a real captured bitmap is never overwritten by a subsequent disabledBySettings probe.
        val harnessD = Harness(this)
        val controllerD = harnessD.controller
        val shot = sampleBitmap()
        controllerD.onScreenshotReceived(bitmap = shot)
        runCurrent()
        assertEquals(AssistCaptureStatus.Available, controllerD.state.value.captureStatus)
        controllerD.beginSession(
            supportsScreenshot = true,
            showFlags = 263,
            deviceLocked = false,
            screenshotDisabledBySettings = true,
            expectScreenshotFromFlags = true,
        )
        assertEquals(AssistCaptureStatus.Available, controllerD.state.value.captureStatus)
        shot.recycle()
        controllerD.endSession()
        runCurrent()
    }

    @Test
    fun privacyModeStagesScreenshotInMemoryAndCleansUpOnToggleAndEndSession() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.beginSession(supportsScreenshot = true)

        val shot = sampleBitmap()
        controller.onScreenshotReceived(shot)
        runCurrent()

        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)
        assertNull(controller.state.value.attachedScreenshot)
        assertEquals(0, harness.saveCount)

        // Confirming attach persists only to private cacheDir/assist without exposing preview bitmaps to UI state.
        controller.attachCurrentScreen()
        runCurrent()
        assertEquals(AssistCaptureStatus.Attached, controller.state.value.captureStatus)
        assertEquals(1, harness.saveCount)
        assertNotNull(controller.state.value.attachedScreenshot)

        // Toggling off deletes the temp file immediately while allowing instant re-attach.
        controller.removeAttachedScreen()
        runCurrent()
        assertEquals(AssistCaptureStatus.Available, controller.state.value.captureStatus)
        assertEquals(1, harness.deletedAttachments.size)
        assertNull(controller.state.value.attachedScreenshot)

        shot.recycle()
        controller.endSession()
        runCurrent()
        assertTrue(harness.preparedBitmaps.single().isRecycled)
    }

    @Test
    fun stripOverlayWireTagsStripsCanonicalAndLegacyContextAndFileBlocks() {
        val canonical = formatAssistWireMessage(
            prompt = "Explain this chart",
            remoteImagePath = "/tmp/assist-1.jpg",
            contextCard = sampleContext,
            photosOnlyPrompt = "Please look at this screenshot.",
        )
        val legacy = ProtocolCompatibility.messageForHub(canonical)
        assertNotEquals(canonical, legacy)
        assertFalse(legacy.contains("<persona-context"))
        assertTrue(ProtocolCompatibility.hasContextTag(legacy))

        val strippedCanonical = stripOverlayWireTags(canonical)
        assertEquals(StrippedWireMessage(text = "Explain this chart", hasAttachedImage = true), strippedCanonical)

        val strippedLegacy = stripOverlayWireTags(legacy)
        assertEquals(StrippedWireMessage(text = "Explain this chart", hasAttachedImage = true), strippedLegacy)
        assertFalse(ProtocolCompatibility.hasContextTag(strippedLegacy.text))

        val screenOnlyCanonical = "${sampleContext.xml()}\n\n<attached_images>\n/tmp/assist-2.jpg\n</attached_images>"
        val screenOnlyLegacy = ProtocolCompatibility.messageForHub(screenOnlyCanonical)
        assertEquals(StrippedWireMessage(text = "", hasAttachedImage = true), stripOverlayWireTags(screenOnlyCanonical))
        assertEquals(StrippedWireMessage(text = "", hasAttachedImage = true), stripOverlayWireTags(screenOnlyLegacy))

        val canonicalWithFile = "${sampleContext.xml()}\n\nCheck attached notes\n\n" +
            "<persona-file kind=\"file\" path=\"/tmp/notes.txt\" name=\"notes.txt\" size=\"42\"/>\n\n" +
            "<attached_images>\n/tmp/assist-3.jpg\n</attached_images>"
        val legacyWithFile = ProtocolCompatibility.messageForHub(canonicalWithFile)
        assertFalse(legacyWithFile.contains("<persona-file"))
        assertTrue(ProtocolCompatibility.hasFileTag(legacyWithFile))
        assertEquals(
            StrippedWireMessage(text = "Check attached notes", hasAttachedImage = true),
            stripOverlayWireTags(canonicalWithFile),
        )
        assertEquals(
            StrippedWireMessage(text = "Check attached notes", hasAttachedImage = true),
            stripOverlayWireTags(legacyWithFile),
        )
    }

    @Test
    fun projectOverlayTurnsStripsLegacyHubRewrittenContextFromEchoedUserBubble() = runTest {
        val harness = Harness(this)
        val controller = harness.controller
        controller.onHubUpdate(AssistHubSnapshot(status = AssistHubStatus.Ready, mainSession = mainKey))

        controller.beginSession(supportsScreenshot = true)
        val shot = sampleBitmap()
        controller.onAssistContextReceived(sampleContext, isFocused = true)
        controller.onScreenshotReceived(shot)
        runCurrent()
        controller.attachCurrentScreen()
        runCurrent()

        controller.updateDraftText("Explain this chart")
        controller.send()
        runCurrent()

        val sentClientId = controller.state.value.turns.single().id.removePrefix("pending:")
        val legacyEchoText = ProtocolCompatibility.messageForHub(
            formatAssistWireMessage(
                prompt = "Explain this chart",
                remoteImagePath = "/tmp/assist-1.jpg",
                contextCard = sampleContext,
                photosOnlyPrompt = "Please look at this screenshot.",
            ),
        )
        assertFalse(legacyEchoText.contains("<persona-context"))
        assertTrue(ProtocolCompatibility.hasContextTag(legacyEchoText))

        // Case 1: Echo arrives with matching clientMsgId.
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                mainWorking = true,
                chatSnapshot = ChatSnapshot(
                    items = listOf(
                        Item(
                            id = "u-legacy-1",
                            role = "user",
                            kind = "text",
                            text = legacyEchoText,
                            clientMsgId = sentClientId,
                        ),
                    ),
                    loading = false,
                ),
            ),
        )
        val withClientId = controller.state.value.turns.single()
        assertEquals("u-legacy-1", withClientId.id)
        assertTrue(withClientId.fromUser)
        assertEquals("Explain this chart", withClientId.text)
        assertFalse(ProtocolCompatibility.hasContextTag(withClientId.text))
        assertTrue(withClientId.hasScreenAttachment)

        // Case 2: Echo arrives without clientMsgId and must match via stripped display text.
        controller.onHubUpdate(
            AssistHubSnapshot(
                status = AssistHubStatus.Ready,
                mainSession = mainKey,
                mainWorking = true,
                chatSnapshot = ChatSnapshot(
                    items = listOf(
                        Item(
                            id = "u-legacy-2",
                            role = "user",
                            kind = "text",
                            text = legacyEchoText,
                            clientMsgId = null,
                        ),
                    ),
                    loading = false,
                ),
            ),
        )
        val withoutClientId = controller.state.value.turns.single()
        assertEquals("u-legacy-2", withoutClientId.id)
        assertTrue(withoutClientId.fromUser)
        assertEquals("Explain this chart", withoutClientId.text)
        assertFalse(ProtocolCompatibility.hasContextTag(withoutClientId.text))
        assertTrue(withoutClientId.hasScreenAttachment)

        shot.recycle()
        controller.endSession()
        runCurrent()
    }
}
