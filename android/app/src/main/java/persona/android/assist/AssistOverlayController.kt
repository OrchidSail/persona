package persona.android.assist

import android.graphics.Bitmap
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.DeliveryState
import persona.core.contracts.api.DigitalAssistantModelCatalog
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.contracts.api.OutboxMessage
import persona.feature.chat.ChatLinkContext
import persona.feature.chat.ComposerAttachment

internal const val ASSIST_LOG_TAG = "PersonaAssist"

internal enum class AssistCaptureStatus {
    /** VoiceInteractionSession opened; awaiting async `onHandleScreenshot` callback. */
    Waiting,

    /** Screenshot is staged strictly in memory; NOT written to disk and NOT attached yet. */
    Available,

    /** User manually tapped "Attach current screen"; written to `cacheDir/assist/` for next send. */
    Attached,

    /** System explicitly returned null (`FLAG_SECURE` / protected page) or bitmap preparation failed. */
    Unavailable,

    /** System did not deliver `onHandleScreenshot` before timeout; still upgrades if a late bitmap arrives. */
    TimedOut,

    /** User disabled system Digital Assistant screenshots (e.g. `assist_screenshot_enabled = 0` or userDisabledShowContext). */
    DisabledBySettings,

    /** Entry point does not provide screenshots, device is locked, or capture was released after send. */
    NotSupported,
}

internal enum class AssistHubStatus {
    Connecting,
    Ready,
    Offline,
    Unpaired,
}

internal enum class AssistTurnDelivery {
    Sending,
    Sent,
    Queued,
    Failed,
}

internal data class AssistOverlayTurn(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val hasScreenAttachment: Boolean = false,
    val delivery: AssistTurnDelivery = AssistTurnDelivery.Sent,
)

internal data class AssistHubSnapshot(
    val status: AssistHubStatus = AssistHubStatus.Connecting,
    val mainSession: SessionKey? = null,
    val observedSession: SessionKey? = mainSession,
    val mainWorking: Boolean = false,
    val chatSnapshot: ChatSnapshot? = null,
    val outbox: List<OutboxMessage> = emptyList(),
)

internal data class AssistOverlayUiState(
    val invocationId: String = "",
    val deviceLocked: Boolean = false,
    val captureStatus: AssistCaptureStatus = AssistCaptureStatus.Waiting,
    val attachedScreenshot: ComposerAttachment? = null,
    val attachedContext: ChatLinkContext? = null,
    val hasStagedContext: Boolean = false,
    val attachingScreen: Boolean = false,
    val hubStatus: AssistHubStatus = AssistHubStatus.Connecting,
    val mainSession: SessionKey? = null,
    val activeSession: SessionKey? = null,
    val modelChoice: DigitalAssistantModelChoice = DigitalAssistantModelChoice(),
    val modelPickerOpen: Boolean = false,
    val modelCatalog: DigitalAssistantModelCatalog = DigitalAssistantModelCatalog(),
    val modelFallbackAvailable: Boolean = false,
    val draftText: String = "",
    val sending: Boolean = false,
    val awaitingReply: Boolean = false,
    val errorMessage: String? = null,
    val turns: List<AssistOverlayTurn> = emptyList(),
) {
    val expanded: Boolean get() = !deviceLocked && (turns.isNotEmpty() || sending)
    val canSend: Boolean
        get() = !deviceLocked &&
            !sending &&
            !attachingScreen &&
            hubStatus == AssistHubStatus.Ready &&
            (draftText.isNotBlank() || attachedScreenshot != null)
}

internal data class SentOverlayTurn(
    val clientMessageId: String,
    val displayText: String,
    val hasScreenAttachment: Boolean,
)

/**
 * Pure state machine and coordinator for Persona's floating Digital Assistant overlay.
 *
 * Privacy, routing, and lifecycle guarantees:
 * - While the device is locked (`deviceLocked == true`), incoming screenshots and assist context
 *   are discarded immediately and sending to the Hub is blocked until unlocked.
 * - Incoming `onHandleScreenshot` bitmaps and filtered `onHandleAssist` context are staged ONLY
 *   in memory until the user explicitly taps "Attach current screen".
 * - Disk writes (`cacheDir/assist/`) happen strictly upon manual user confirmation, and are deleted
 *   immediately upon removal (`×`), successful upload/send, or overlay dismissal.
 * - When a dedicated overlay model is picked, the first send creates a lightweight non-main
 *   conversation (`main = false`), keeping follow-ups in that same session and NEVER mutating
 *   the Hub's main chat model binding.
 */
internal class AssistOverlayController(
    private val mainScope: CoroutineScope,
    private val prepareScreenshot: suspend (Bitmap) -> Bitmap?,
    private val saveScreenshot: suspend (Bitmap, String) -> ComposerAttachment?,
    private val deleteScreenshot: suspend (ComposerAttachment) -> Unit,
    private val sendTextToMain: suspend (String, String) -> Unit,
    private val sendWithScreenToMain: suspend (String, ComposerAttachment, ChatLinkContext?, String) -> Unit,
    private val createSideConversation: suspend (ModelPick) -> SessionKey = {
        throw UnsupportedOperationException("Dedicated overlay model unavailable")
    },
    private val sendTextToSession: suspend (SessionKey, String, String) -> Unit = { _, prompt, clientId ->
        sendTextToMain(prompt, clientId)
    },
    private val sendWithScreenToSession: suspend (SessionKey, String, ComposerAttachment, ChatLinkContext?, String) -> Unit =
        { _, prompt, shot, ctx, clientId -> sendWithScreenToMain(prompt, shot, ctx, clientId) },
    private val onActiveSessionChanged: (SessionKey?) -> Unit = {},
    private val loadModelCatalog: suspend (Boolean) -> DigitalAssistantModelCatalog = {
        DigitalAssistantModelCatalog(loading = false, groups = emptyList(), hubAvailable = false)
    },
    private val onPersistDefaultModel: () -> Unit = {},
    private val onPersistModelChoice: (DigitalAssistantModelChoice) -> Unit = {},
    private val publishDraftForFullApp: (String, ComposerAttachment?, ChatLinkContext?, String, SessionKey?) -> Unit,
    private val onWakeHub: () -> Unit,
    private val onLaunchFullApp: (SessionKey?) -> Unit,
    private val onDismiss: () -> Unit,
    private val screenOnlyDisplayLabel: () -> String,
    private val defaultSendError: () -> String,
    private val defaultModelStartError: () -> String = defaultSendError,
    private val initialModelChoice: DigitalAssistantModelChoice = DigitalAssistantModelChoice(),
    private val screenshotWaitTimeoutMillis: Long = SCREENSHOT_WAIT_TIMEOUT_MILLIS,
    private val noFlagScreenshotGraceMillis: Long = NO_FLAG_SCREENSHOT_GRACE_MILLIS,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = { message -> Log.i(ASSIST_LOG_TAG, message) },
) {
    private val mutableState = MutableStateFlow(AssistOverlayUiState(modelChoice = initialModelChoice))
    val state: StateFlow<AssistOverlayUiState> = mutableState.asStateFlow()

    private var invocationOpen = false
    private var invocationId = ""
    private var deviceLockedForInvocation = false
    private var captureDiscardedForInvocation = false
    private var stagedBitmap: Bitmap? = null
    private var stagedContext: ChatLinkContext? = null
    private var stagedContextFocused = false
    private var handedOffAttachment: ComposerAttachment? = null

    private var hubSnapshot = AssistHubSnapshot()
    private var activeOverlaySession: SessionKey? = null
    private var baselineItemIds: Set<String> = emptySet()
    private val sentTurns = mutableListOf<SentOverlayTurn>()
    private var persistedModelChoice: DigitalAssistantModelChoice = initialModelChoice
    private var cachedCatalog: DigitalAssistantModelCatalog? = null

    private var screenshotWaitJob: Job? = null
    private var stageJob: Job? = null
    private var attachJob: Job? = null
    private var sendJob: Job? = null
    private var catalogJob: Job? = null

    /** Starts a fresh overlay session; idempotent within an active invocation (e.g. `onPrepareShow` -> `onShow`). */
    fun beginSession(
        supportsScreenshot: Boolean,
        showFlags: Int = 0,
        deviceLocked: Boolean = false,
        screenshotDisabledBySettings: Boolean = false,
        expectScreenshotFromFlags: Boolean = true,
    ) {
        if (invocationOpen) {
            if (deviceLocked && !deviceLockedForInvocation) {
                lockSession()
            } else if (!deviceLockedForInvocation && screenshotDisabledBySettings) {
                val status = mutableState.value.captureStatus
                val hasLiveScreenshot = stagedBitmap?.let { !it.isRecycled } == true ||
                    status == AssistCaptureStatus.Available ||
                    status == AssistCaptureStatus.Attached
                if (!hasLiveScreenshot) {
                    screenshotWaitJob?.cancel()
                    screenshotWaitJob = null
                    if (status != AssistCaptureStatus.DisabledBySettings) {
                        log("screenshotDisabledBySettings correctedFrom=$status status=DisabledBySettings")
                        mutableState.update { it.copy(captureStatus = AssistCaptureStatus.DisabledBySettings) }
                    }
                }
            }
            return
        }
        cleanupJobsAndResources(deleteAttached = true)
        invocationOpen = true
        invocationId = nextId()
        deviceLockedForInvocation = deviceLocked
        val allowCapture = supportsScreenshot && !deviceLocked
        captureDiscardedForInvocation = !allowCapture
        handedOffAttachment = null
        activeOverlaySession = null
        onActiveSessionChanged(null)
        baselineItemIds = emptySet()
        sentTurns.clear()
        val initialCaptureStatus = when {
            !allowCapture -> AssistCaptureStatus.NotSupported
            screenshotDisabledBySettings -> AssistCaptureStatus.DisabledBySettings
            else -> AssistCaptureStatus.Waiting
        }
        log(
            "beginSession supportsScreenshot=$supportsScreenshot showFlags=$showFlags " +
                "deviceLocked=$deviceLocked disabledBySettings=$screenshotDisabledBySettings " +
                "expectFromFlags=$expectScreenshotFromFlags initialCapture=$initialCaptureStatus",
        )
        if (!deviceLocked) {
            onWakeHub()
        }
        mutableState.value = AssistOverlayUiState(
            invocationId = invocationId,
            deviceLocked = deviceLocked,
            captureStatus = initialCaptureStatus,
            hubStatus = hubSnapshot.status,
            mainSession = hubSnapshot.mainSession,
            modelChoice = persistedModelChoice,
            modelCatalog = cachedCatalog ?: DigitalAssistantModelCatalog(),
        )
        val effectiveWaitMillis = if (expectScreenshotFromFlags) {
            screenshotWaitTimeoutMillis
        } else {
            minOf(screenshotWaitTimeoutMillis, noFlagScreenshotGraceMillis)
        }
        if (initialCaptureStatus == AssistCaptureStatus.Waiting && effectiveWaitMillis > 0L) {
            val activeId = invocationId
            screenshotWaitJob = mainScope.launch {
                delay(effectiveWaitMillis)
                if (invocationOpen && invocationId == activeId &&
                    mutableState.value.captureStatus == AssistCaptureStatus.Waiting
                ) {
                    log("screenshotWaitTimeout status=TimedOut waitMs=$effectiveWaitMillis expectFromFlags=$expectScreenshotFromFlags")
                    mutableState.update { it.copy(captureStatus = AssistCaptureStatus.TimedOut) }
                }
            }
        }
    }

    /** Transitions an already-open locked session into unlocked state (e.g., after keyguard dismissal). */
    fun onDeviceUnlocked() {
        if (!invocationOpen || !deviceLockedForInvocation) return
        deviceLockedForInvocation = false
        onWakeHub()
        mutableState.update {
            it.copy(
                deviceLocked = false,
                hubStatus = hubSnapshot.status,
                mainSession = hubSnapshot.mainSession,
            )
        }
    }

    /** Keeps the overlay model pill synced live with Hub-scoped preferences (including changes from Settings). */
    fun onModelChoiceUpdated(choice: DigitalAssistantModelChoice) {
        persistedModelChoice = choice
        mutableState.update { it.copy(modelChoice = choice) }
    }

    /** Updates live Hub connection, target session, and streaming transcript snapshot. */
    fun onHubUpdate(snapshot: AssistHubSnapshot) {
        hubSnapshot = snapshot
        val expectedSession = activeOverlaySession ?: snapshot.mainSession
        val projection = if (deviceLockedForInvocation) {
            OverlayTurnsProjection(emptyList(), awaitingReply = false)
        } else {
            projectOverlayTurns(snapshot, sentTurns, baselineItemIds, expectedSession)
        }
        mutableState.update { current ->
            current.copy(
                hubStatus = snapshot.status,
                mainSession = snapshot.mainSession,
                activeSession = activeOverlaySession,
                turns = projection.turns,
                awaitingReply = projection.awaitingReply,
            )
        }
    }

    /** Handles `onHandleScreenshot(Bitmap?)`: stages safely in memory ONLY; never writes to disk or attaches. */
    fun onScreenshotReceived(
        bitmap: Bitmap?,
        screenshotDisabledBySettings: Boolean = false,
    ) {
        if (!invocationOpen) {
            beginSession(
                supportsScreenshot = true,
                screenshotDisabledBySettings = screenshotDisabledBySettings,
            )
        }
        if (deviceLockedForInvocation) {
            log("onHandleScreenshot dropped=deviceLocked")
            return
        }
        val activeId = invocationId
        val valid = bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
        log(
            "onHandleScreenshot received=${bitmap != null} valid=$valid " +
                "disabledBySettings=$screenshotDisabledBySettings " +
                "size=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} config=${bitmap?.config?.name ?: "none"}",
        )
        screenshotWaitJob?.cancel()
        screenshotWaitJob = null
        if (captureDiscardedForInvocation) return
        if (!valid) {
            val status = mutableState.value.captureStatus
            val hasLiveScreenshot = stagedBitmap?.let { !it.isRecycled } == true ||
                status == AssistCaptureStatus.Available ||
                status == AssistCaptureStatus.Attached
            if (!hasLiveScreenshot) {
                val targetStatus = if (screenshotDisabledBySettings || status == AssistCaptureStatus.DisabledBySettings) {
                    AssistCaptureStatus.DisabledBySettings
                } else {
                    AssistCaptureStatus.Unavailable
                }
                mutableState.update { it.copy(captureStatus = targetStatus) }
            }
            return
        }
        val incoming = requireNotNull(bitmap)
        stageJob?.cancel()
        stageJob = mainScope.launch {
            val prepared = try {
                prepareScreenshot(incoming)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!invocationOpen || invocationId != activeId || captureDiscardedForInvocation || deviceLockedForInvocation) {
                prepared?.safeRecycle()
                return@launch
            }
            if (prepared == null) {
                log("screenshotPrepareFailed status=Unavailable")
                if (mutableState.value.captureStatus != AssistCaptureStatus.Attached) {
                    mutableState.update { it.copy(captureStatus = AssistCaptureStatus.Unavailable) }
                }
                return@launch
            }
            val oldStaged = stagedBitmap
            stagedBitmap = prepared
            log("screenshotStagedInMemory size=${prepared.width}x${prepared.height}")
            mutableState.update { current ->
                current.copy(
                    captureStatus = if (current.captureStatus == AssistCaptureStatus.Attached) {
                        AssistCaptureStatus.Attached
                    } else {
                        AssistCaptureStatus.Available
                    },
                )
            }
            oldStaged?.safeRecycle()
        }
    }

    /** Handles filtered `onHandleAssist` context: staged in memory ONLY until user confirms attaching the screen. */
    fun onAssistContextReceived(context: ChatLinkContext?, isFocused: Boolean) {
        if (deviceLockedForInvocation) {
            log("onHandleAssist dropped=deviceLocked")
            return
        }
        log("onHandleAssist safeContext=${context != null} focused=$isFocused")
        if (context == null) return
        if (!invocationOpen) {
            beginSession(supportsScreenshot = true)
        }
        if (captureDiscardedForInvocation) return
        if (stagedContext == null || isFocused || !stagedContextFocused) {
            stagedContext = context
            stagedContextFocused = isFocused
        }
        mutableState.update { current ->
            current.copy(
                hasStagedContext = stagedContext != null,
                attachedContext = if (current.captureStatus == AssistCaptureStatus.Attached) {
                    current.attachedContext ?: stagedContext
                } else {
                    current.attachedContext
                },
            )
        }
    }

    /** Toggles the lightweight inline model picker inside the overlay sheet. */
    fun toggleModelPicker() {
        val current = mutableState.value
        if (!invocationOpen || current.deviceLocked || current.sending) return
        val opening = !current.modelPickerOpen
        mutableState.update { it.copy(modelPickerOpen = opening) }
        if (opening) {
            val existing = cachedCatalog
            val shouldRefresh = existing == null || existing.error != null || !existing.hubAvailable || existing.groups.isEmpty()
            if (shouldRefresh) {
                fetchModelCatalog(refresh = existing?.error != null)
            }
        }
    }

    fun closeModelPicker() {
        mutableState.update { it.copy(modelPickerOpen = false) }
    }

    fun refreshModelCatalog() {
        if (!invocationOpen || deviceLockedForInvocation) return
        fetchModelCatalog(refresh = true)
    }

    fun selectDefaultModel() {
        persistedModelChoice = DigitalAssistantModelChoice()
        onPersistDefaultModel()
        mutableState.update {
            it.copy(
                modelChoice = persistedModelChoice,
                modelPickerOpen = false,
                modelFallbackAvailable = false,
                errorMessage = null,
            )
        }
    }

    fun selectModel(choice: DigitalAssistantModelChoice) {
        if (choice.pick == null) {
            selectDefaultModel()
            return
        }
        persistedModelChoice = choice
        onPersistModelChoice(choice)
        mutableState.update {
            it.copy(
                modelChoice = choice,
                modelPickerOpen = false,
                modelFallbackAvailable = false,
                errorMessage = null,
            )
        }
    }

    /** Manual user confirmation: persists the staged screen to private `cacheDir/assist/` and previews it. */
    fun attachCurrentScreen() {
        val current = mutableState.value
        val source = stagedBitmap
        if (!invocationOpen || current.deviceLocked || current.attachingScreen || current.sending ||
            current.captureStatus != AssistCaptureStatus.Available || source == null || source.isRecycled
        ) {
            return
        }
        val activeId = invocationId
        mutableState.update { it.copy(attachingScreen = true, errorMessage = null) }
        attachJob?.cancel()
        attachJob = mainScope.launch {
            val saved = try {
                saveScreenshot(source, activeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!invocationOpen || invocationId != activeId || captureDiscardedForInvocation || deviceLockedForInvocation) {
                if (saved != null) runCatching { deleteScreenshot(saved) }
                return@launch
            }
            if (saved == null) {
                log("attachCurrentScreenFailed")
                mutableState.update {
                    it.copy(
                        attachingScreen = false,
                        errorMessage = defaultSendError(),
                    )
                }
                return@launch
            }
            log("attachCurrentScreenConfirmed bytes=${saved.size ?: 0L} withContext=${stagedContext != null}")
            mutableState.update {
                it.copy(
                    captureStatus = AssistCaptureStatus.Attached,
                    attachedScreenshot = saved,
                    attachedContext = stagedContext,
                    attachingScreen = false,
                    errorMessage = null,
                )
            }
        }
    }

    /** Removes the manually attached screen (and paired context) and deletes its temporary cache file immediately. */
    fun removeAttachedScreen() {
        val current = mutableState.value
        if (current.sending) return
        attachJob?.cancel()
        attachJob = null
        val previous = current.attachedScreenshot
        val canReattach = !deviceLockedForInvocation && !captureDiscardedForInvocation &&
            stagedBitmap?.let { !it.isRecycled } == true
        mutableState.update {
            it.copy(
                captureStatus = if (canReattach) AssistCaptureStatus.Available else AssistCaptureStatus.NotSupported,
                attachedScreenshot = null,
                attachedContext = null,
                attachingScreen = false,
                errorMessage = null,
            )
        }
        if (previous != null) {
            mainScope.launch { runCatching { deleteScreenshot(previous) } }
        }
    }

    fun updateDraftText(value: String) {
        mutableState.update { it.copy(draftText = value, errorMessage = null, modelFallbackAvailable = false) }
    }

    fun appendDictation(heard: String) {
        val trimmed = heard.trim()
        if (trimmed.isEmpty() || deviceLockedForInvocation) return
        mutableState.update { current ->
            val updated = persona.feature.chat.withDictation(current.draftText, trimmed)
            current.copy(draftText = updated, errorMessage = null, modelFallbackAvailable = false)
        }
    }

    fun showNotice(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    /** Sends the message using the selected overlay model (or main chat if Default) and streams the reply in place. */
    fun send() = sendInternal(forceMainDefault = false)

    /** One-tap fallback after a dedicated overlay model fails: retries sending to Main chat without mutating main's model. */
    fun retryWithDefaultMain() = sendInternal(forceMainDefault = true)

    private fun sendInternal(forceMainDefault: Boolean) {
        val current = mutableState.value
        if (!invocationOpen || !current.canSend) return
        val prompt = current.draftText.trim()
        val screenshot = current.attachedScreenshot
        val contextCard = current.attachedContext
        val clientId = nextId()
        val activeId = invocationId
        val existingSideSession = activeOverlaySession
        val selectedPick = if (forceMainDefault) null else current.modelChoice.pick

        mutableState.update {
            it.copy(
                sending = true,
                modelPickerOpen = false,
                modelFallbackAvailable = false,
                errorMessage = null,
            )
        }
        sendJob = mainScope.launch {
            var createdSideSession = false
            try {
                val targetSession: SessionKey? = when {
                    existingSideSession != null -> existingSideSession
                    selectedPick != null -> {
                        val created = createSideConversation(selectedPick)
                        if (!invocationOpen || invocationId != activeId) return@launch
                        activeOverlaySession = created
                        createdSideSession = true
                        baselineItemIds = emptySet()
                        onActiveSessionChanged(created)
                        created
                    }
                    else -> {
                        if (sentTurns.isEmpty()) {
                            baselineItemIds = if (hubSnapshot.observedSession == hubSnapshot.mainSession) {
                                hubSnapshot.chatSnapshot?.items.orEmpty().mapTo(HashSet()) { it.id }
                            } else {
                                emptySet()
                            }
                        }
                        null
                    }
                }

                if (targetSession != null) {
                    if (screenshot != null) {
                        sendWithScreenToSession(targetSession, prompt, screenshot, contextCard, clientId)
                    } else {
                        sendTextToSession(targetSession, prompt, clientId)
                    }
                } else {
                    if (screenshot != null) {
                        sendWithScreenToMain(prompt, screenshot, contextCard, clientId)
                    } else {
                        sendTextToMain(prompt, clientId)
                    }
                }

                if (!invocationOpen || invocationId != activeId) {
                    if (screenshot != null) runCatching { deleteScreenshot(screenshot) }
                    return@launch
                }
                sentTurns += SentOverlayTurn(
                    clientMessageId = clientId,
                    displayText = prompt.ifBlank { screenOnlyDisplayLabel() },
                    hasScreenAttachment = screenshot != null,
                )
                if (screenshot != null) {
                    runCatching { deleteScreenshot(screenshot) }
                }
                // Release staged capture immediately after any successful turn (text-only or with screen).
                releaseStagedCaptureOnly()
                val expectedSession = activeOverlaySession ?: hubSnapshot.mainSession
                val projection = projectOverlayTurns(hubSnapshot, sentTurns, baselineItemIds, expectedSession)
                mutableState.update {
                    it.copy(
                        draftText = "",
                        sending = false,
                        activeSession = activeOverlaySession,
                        modelFallbackAvailable = false,
                        errorMessage = null,
                        turns = projection.turns,
                        awaitingReply = projection.awaitingReply,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!invocationOpen || invocationId != activeId) return@launch
                val failedDedicatedModel = selectedPick != null && existingSideSession == null && !createdSideSession
                val fallbackMessage = if (failedDedicatedModel) defaultModelStartError() else defaultSendError()
                mutableState.update {
                    it.copy(
                        sending = false,
                        modelFallbackAvailable = failedDedicatedModel,
                        errorMessage = error.message?.takeIf(String::isNotBlank) ?: fallbackMessage,
                    )
                }
            }
        }
    }

    /**
     * Opens Persona's full `MainActivity` at the active conversation (dedicated lightweight overlay
     * session if started, else main chat). Preserves any unsent text or manually-attached screen
     * (never unconfirmed screenshots, never while device is locked), then dismisses the overlay.
     */
    fun openInPersona() {
        val current = mutableState.value
        val targetSession = activeOverlaySession ?: current.mainSession
        if (!current.deviceLocked) {
            val unsentText = current.draftText.trim()
            val confirmedShot = current.attachedScreenshot
            val confirmedContext = if (confirmedShot != null) current.attachedContext else null
            if (unsentText.isNotEmpty() || confirmedShot != null) {
                handedOffAttachment = confirmedShot
                publishDraftForFullApp(
                    invocationId.ifEmpty { nextId() },
                    confirmedShot,
                    confirmedContext,
                    unsentText,
                    targetSession,
                )
            }
        }
        val oldStaged = stagedBitmap
        stagedBitmap = null
        stagedContext = null
        stagedContextFocused = false
        oldStaged?.safeRecycle()
        onLaunchFullApp(if (current.deviceLocked) null else targetSession)
        onDismiss()
    }

    /** Ends the current overlay session (on hide/dismiss/destroy) and releases all bitmaps & un-sent files. */
    fun endSession() {
        if (!invocationOpen && stagedBitmap == null && mutableState.value.attachedScreenshot == null) {
            return
        }
        invocationOpen = false
        deviceLockedForInvocation = false
        activeOverlaySession = null
        onActiveSessionChanged(null)
        baselineItemIds = emptySet()
        sentTurns.clear()
        val previousState = mutableState.value
        mutableState.value = AssistOverlayUiState(
            captureStatus = AssistCaptureStatus.NotSupported,
            hubStatus = hubSnapshot.status,
            mainSession = hubSnapshot.mainSession,
            modelChoice = persistedModelChoice,
            modelCatalog = cachedCatalog ?: DigitalAssistantModelCatalog(),
        )
        cleanupJobsAndResources(deleteAttached = true, attachedToDelete = previousState.attachedScreenshot)
        handedOffAttachment = null
    }

    private fun lockSession() {
        deviceLockedForInvocation = true
        releaseStagedCaptureOnly()
        mutableState.update {
            it.copy(
                deviceLocked = true,
                modelPickerOpen = false,
                modelFallbackAvailable = false,
                errorMessage = null,
                turns = emptyList(),
                awaitingReply = false,
            )
        }
    }

    private fun fetchModelCatalog(refresh: Boolean) {
        val activeId = invocationId
        catalogJob?.cancel()
        mutableState.update {
            it.copy(modelCatalog = it.modelCatalog.copy(loading = true, error = null))
        }
        catalogJob = mainScope.launch {
            val loaded = try {
                loadModelCatalog(refresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DigitalAssistantModelCatalog(
                    loading = false,
                    groups = emptyList(),
                    error = error.message?.takeIf(String::isNotBlank) ?: defaultSendError(),
                    hubAvailable = true,
                )
            }
            if (!invocationOpen || invocationId != activeId) return@launch
            cachedCatalog = loaded
            mutableState.update { it.copy(modelCatalog = loaded) }
        }
    }

    private fun releaseStagedCaptureOnly() {
        captureDiscardedForInvocation = true
        screenshotWaitJob?.cancel()
        screenshotWaitJob = null
        stageJob?.cancel()
        stageJob = null
        attachJob?.cancel()
        attachJob = null
        val oldStaged = stagedBitmap
        stagedBitmap = null
        stagedContext = null
        stagedContextFocused = false
        mutableState.update {
            it.copy(
                captureStatus = AssistCaptureStatus.NotSupported,
                attachedScreenshot = null,
                attachedContext = null,
                hasStagedContext = false,
                attachingScreen = false,
            )
        }
        oldStaged?.safeRecycle()
    }

    private fun cleanupJobsAndResources(
        deleteAttached: Boolean,
        attachedToDelete: ComposerAttachment? = mutableState.value.attachedScreenshot,
    ) {
        screenshotWaitJob?.cancel()
        screenshotWaitJob = null
        stageJob?.cancel()
        stageJob = null
        attachJob?.cancel()
        attachJob = null
        sendJob?.cancel()
        sendJob = null
        catalogJob?.cancel()
        catalogJob = null

        val oldStaged = stagedBitmap
        stagedBitmap = null
        stagedContext = null
        stagedContextFocused = false

        if (deleteAttached && attachedToDelete != null && attachedToDelete !== handedOffAttachment) {
            mainScope.launch { runCatching { deleteScreenshot(attachedToDelete) } }
        }
        oldStaged?.safeRecycle()
    }

    companion object {
        const val SCREENSHOT_WAIT_TIMEOUT_MILLIS = 4_000L
        const val NO_FLAG_SCREENSHOT_GRACE_MILLIS = 1_200L
    }
}

internal data class OverlayTurnsProjection(
    val turns: List<AssistOverlayTurn>,
    val awaitingReply: Boolean,
)

/**
 * Projects only the conversation turns belonging to the current overlay session from the active
 * conversation's live [AssistHubSnapshot], preserving real-time streaming state and immediate
 * optimistic user bubbles.
 */
internal fun projectOverlayTurns(
    hub: AssistHubSnapshot,
    sentTurns: List<SentOverlayTurn>,
    baselineItemIds: Set<String>,
    expectedSession: SessionKey? = hub.observedSession,
): OverlayTurnsProjection {
    if (sentTurns.isEmpty()) return OverlayTurnsProjection(emptyList(), awaitingReply = false)
    val snapshotMatches = expectedSession == null || hub.observedSession == expectedSession
    val items = if (snapshotMatches) hub.chatSnapshot?.items.orEmpty() else emptyList()
    val streamingIds = if (snapshotMatches) hub.chatSnapshot?.streamingItemIds.orEmpty() else emptySet()
    val sentById = sentTurns.associateBy { it.clientMessageId }
    val matchedClientIds = mutableSetOf<String>()

    val firstIndex = items.indexOfFirst { item ->
        item.clientMsgId != null && item.clientMsgId in sentById
    }.takeIf { it >= 0 } ?: items.indexOfFirst { item ->
        item.id !in baselineItemIds && item.role == "user" && item.kind == "text"
    }

    val projected = mutableListOf<AssistOverlayTurn>()
    if (firstIndex >= 0) {
        for (i in firstIndex until items.size) {
            val item = items[i]
            if (item.kind != "text") continue
            when (item.role) {
                "user" -> {
                    val raw = item.text.orEmpty()
                    val stripped = stripOverlayWireTags(raw)
                    val matched = item.clientMsgId?.let(sentById::get)
                        ?: sentTurns.firstOrNull { candidate ->
                            candidate.clientMessageId !in matchedClientIds &&
                                (candidate.displayText == stripped.text ||
                                    (stripped.hasAttachedImage && candidate.hasScreenAttachment && stripped.text.isBlank()))
                        }
                    if (matched != null) matchedClientIds += matched.clientMessageId
                    val display = stripped.text.ifBlank { matched?.displayText.orEmpty() }
                    if (display.isNotBlank() || stripped.hasAttachedImage || matched?.hasScreenAttachment == true) {
                        projected += AssistOverlayTurn(
                            id = item.id,
                            fromUser = true,
                            text = display,
                            hasScreenAttachment = stripped.hasAttachedImage || matched?.hasScreenAttachment == true,
                            delivery = AssistTurnDelivery.Sent,
                        )
                    }
                }

                "assistant" -> {
                    val body = item.text.orEmpty()
                    if (body.isNotBlank()) {
                        projected += AssistOverlayTurn(
                            id = item.id,
                            fromUser = false,
                            text = body,
                            streaming = item.id in streamingIds,
                            delivery = AssistTurnDelivery.Sent,
                        )
                    }
                }
            }
        }
    }

    val outboxById = hub.outbox.associateBy { it.clientMessageId }
    for (sent in sentTurns) {
        if (sent.clientMessageId in matchedClientIds) continue
        val outbox = outboxById[sent.clientMessageId]
        val delivery = when (outbox?.state) {
            DeliveryState.Refused, DeliveryState.Rejected -> AssistTurnDelivery.Failed
            DeliveryState.Queued, DeliveryState.WaitingForNetwork -> AssistTurnDelivery.Queued
            DeliveryState.Sent -> AssistTurnDelivery.Sent
            else -> AssistTurnDelivery.Sending
        }
        projected += AssistOverlayTurn(
            id = "pending:${sent.clientMessageId}",
            fromUser = true,
            text = sent.displayText,
            hasScreenAttachment = sent.hasScreenAttachment,
            delivery = delivery,
        )
    }

    val last = projected.lastOrNull()
    val awaitingReply = last != null &&
        last.fromUser &&
        last.delivery != AssistTurnDelivery.Failed &&
        ((snapshotMatches && hub.mainWorking) || last.delivery == AssistTurnDelivery.Sending || last.delivery == AssistTurnDelivery.Sent)

    return OverlayTurnsProjection(projected, awaitingReply)
}

internal data class StrippedWireMessage(
    val text: String,
    val hasAttachedImage: Boolean,
)

private val CONTEXT_TAG_REGEX = Regex("<persona-context\\b[^>]*>[\\s\\S]*?</persona-context>\\s*")
private val ATTACHED_IMAGES_REGEX = Regex("<attached_images>[\\s\\S]*?</attached_images>\\s*")

internal fun stripOverlayWireTags(raw: String): StrippedWireMessage {
    val hasImage = ATTACHED_IMAGES_REGEX.containsMatchIn(raw)
    val cleaned = raw
        .replace(CONTEXT_TAG_REGEX, "")
        .replace(ATTACHED_IMAGES_REGEX, "")
        .trim()
    return StrippedWireMessage(text = cleaned, hasAttachedImage = hasImage)
}

private fun Bitmap.safeRecycle() {
    if (!isRecycled) runCatching { recycle() }
}
