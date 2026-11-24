package persona.android.assist

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import persona.android.PersonaApp
import persona.android.R
import persona.android.di.ConnectionRuntime
import persona.android.ui.AppServices
import persona.android.ui.ShellSession
import persona.core.contracts.AgentState
import persona.core.contracts.ModelPick
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantSnapshot
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DigitalAssistantModelCatalog
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.contracts.api.OutboxMessage
import persona.core.domain.ComposerMessage
import persona.core.hub.HubFailure
import persona.feature.chat.ChatAppBindings
import persona.feature.chat.ChatLinkContext
import persona.feature.chat.ChatWiringSource
import persona.feature.chat.ComposerAttachment
import persona.feature.chat.uploadAttachment

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AssistOverlayEntryPoint {
    fun assistAppServices(): AppServices
    fun assistChatWiring(): ChatWiringSource
    fun assistConnections(): ConnectionRuntime
    fun assistModelRuntime(): AssistModelRuntime
}

/**
 * Connects [AssistOverlayController] to the app's existing Hub connection, assistant conversation
 * list, live streaming transcript observer for the active target conversation (main, another
 * existing conversation, or a newly created standalone conversation), per-conversation model
 * switcher, attachment uploader, and model catalog without opening any extra socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AssistHubBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSnapshot: (AssistHubSnapshot) -> Unit,
    private val onModelChoice: (DigitalAssistantModelChoice) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val app: PersonaApp? = appContext as? PersonaApp
    private val entryPoint: AssistOverlayEntryPoint? = runCatching {
        EntryPointAccessors.fromApplication(appContext, AssistOverlayEntryPoint::class.java)
    }.getOrNull()

    private val targetSessionOverride = MutableStateFlow<SessionKey?>(null)
    private var observeJob: Job? = null
    private var modelChoiceJob: Job? = null

    fun wakeHub() {
        val _ = runCatching { entryPoint?.assistConnections()?.wake() }
    }

    /** Routes live chat observation (`chatFlow`, `activeModelPick`, `activeWorking`) to [sessionKey], or Main when null. */
    fun setObservedSession(sessionKey: SessionKey?) {
        targetSessionOverride.value = sessionKey
    }

    fun currentModelChoice(): DigitalAssistantModelChoice =
        entryPoint?.assistModelRuntime()?.choice?.value ?: DigitalAssistantModelChoice()

    fun selectDefaultModel() {
        entryPoint?.assistModelRuntime()?.selectDefault()
    }

    fun selectModel(choice: DigitalAssistantModelChoice) {
        entryPoint?.assistModelRuntime()?.selectModel(choice)
    }

    suspend fun loadModelCatalog(refresh: Boolean = false): DigitalAssistantModelCatalog {
        val runtime = entryPoint?.assistModelRuntime()
            ?: return DigitalAssistantModelCatalog(
                loading = false,
                hubAvailable = false,
                error = context.getString(R.string.app_assist_hub_unpaired_title),
            )
        return runtime.loadCatalog(refresh)
    }

    fun start() {
        if (modelChoiceJob?.isActive != true) {
            val modelFlow = entryPoint?.assistModelRuntime()?.choice
            if (modelFlow != null) {
                onModelChoice(modelFlow.value)
                modelChoiceJob = scope.launch {
                    modelFlow.collect { choice -> onModelChoice(choice) }
                }
            }
        }
        if (observeJob?.isActive == true) return
        val services = entryPoint?.assistAppServices()
        val wiring = entryPoint?.assistChatWiring()
        if (services == null || wiring == null) {
            onSnapshot(AssistHubSnapshot(status = AssistHubStatus.Unpaired))
            return
        }
        wakeHub()
        val pairingReady = app?.pairingReady ?: MutableStateFlow(true)
        val pairingError = app?.pairingRestoreError ?: MutableStateFlow(false)
        observeJob = scope.launch {
            combine(pairingReady, pairingError, services.session, wiring.current) { ready, err, session, binding ->
                HubBindingTuple(ready, err, session, binding)
            }.collectLatest { tuple ->
                val session = tuple.session
                if (session == null) {
                    val status = if (!tuple.pairingReady && !tuple.pairingError) {
                        AssistHubStatus.Connecting
                    } else {
                        AssistHubStatus.Unpaired
                    }
                    onSnapshot(AssistHubSnapshot(status = status))
                    return@collectLatest
                }
                val activeBinding = tuple.binding?.takeIf { it.scope.path == session.identity }
                val connStateFlow = (activeBinding?.connection ?: session.connection).state
                val observedKeyFlow = combine(
                    session.assistant.assistant.map { it.mainSession }.distinctUntilChanged(),
                    targetSessionOverride,
                ) { mainKey, overrideKey -> overrideKey ?: mainKey }.distinctUntilChanged()
                val chatFlow = observedKeyFlow.flatMapLatest { key ->
                    if (key == null) {
                        flowOf(ObservedChat(key = null, snapshot = null))
                    } else {
                        session.chats.observe(key)
                            .map { ObservedChat(key = key, snapshot = it) }
                            .onStart { emit(ObservedChat(key = key, snapshot = null)) }
                    }
                }
                val mainOutboxFlow = activeBinding?.mainSender?.outbox
                val outboxFlow = if (mainOutboxFlow != null && mainOutboxFlow !== session.sender.outbox) {
                    combine(session.sender.outbox, mainOutboxFlow) { senderItems, mainItems ->
                        mergeOutbox(senderItems, mainItems)
                    }
                } else {
                    session.sender.outbox
                }
                combine(
                    connStateFlow,
                    session.assistant.assistant,
                    session.sessions.sessions,
                    chatFlow,
                    outboxFlow,
                ) { connState, assistant, sessions, observedChat, outbox ->
                    val mainKey = assistant.mainSession
                    val activeKey = targetSessionOverride.value ?: mainKey
                    val mainRow = mainKey?.let { key ->
                        sessions.firstOrNull { it.key == key }
                            ?: assistant.conversations.firstOrNull { it.key == key }
                    }
                    val activeRow = activeKey?.let { key ->
                        sessions.firstOrNull { it.key == key }
                            ?: assistant.conversations.firstOrNull { it.key == key }
                    }
                    val mainWorking = mainRow?.foldedState == AgentState.Working ||
                        mainRow?.status == "working"
                    val activeWorking = activeRow?.foldedState == AgentState.Working ||
                        activeRow?.status == "working"
                    val chat = observedChat.snapshot?.takeIf { observedChat.key == activeKey }
                    val hubStatus = mapConnectionStateToHubStatus(
                        connectionState = connState,
                        hasBinding = activeBinding != null || session.demo != null,
                    )
                    AssistHubSnapshot(
                        status = hubStatus,
                        mainSession = mainKey,
                        mainModelPick = mainRow?.modelPick,
                        mainWorking = mainWorking,
                        activeSession = activeKey,
                        activeModelPick = activeRow?.modelPick,
                        activeWorking = activeWorking,
                        conversations = selectOverlayConversations(assistant, sessions),
                        chatSnapshot = chat,
                        outbox = outbox,
                    )
                }.collect { snapshot ->
                    onSnapshot(snapshot)
                }
            }
        }
    }

    fun stop() {
        targetSessionOverride.value = null
        observeJob?.cancel()
        observeJob = null
        modelChoiceJob?.cancel()
        modelChoiceJob = null
    }

    /**
     * Switches the target conversation's model in place to [pick] (`PUT /api/v1/assistant/conversations/{id}/model`),
     * or clears its custom pick (`DELETE .../model`) when [pick] is null. When [targetSession] is null, targets
     * the Hub's main conversation (creating main with [pick] only on first-time pairing when no main exists).
     */
    suspend fun prepareConversationModel(
        targetSession: SessionKey?,
        pick: ModelPick?,
        clearIfDefault: Boolean,
    ): SessionKey? {
        val (session, binding) = requireReadySession()
        val key = targetSession ?: resolveExistingMainSession(session)
        if (pick != null) {
            if (key != null) {
                val activeBinding = binding ?: error(context.getString(R.string.app_assist_hub_offline_title))
                val currentPick = currentSessionModelPick(session, key)
                if (currentPick != pick) {
                    val _ = activeBinding.assistantManagement.setModelPick(key, pick)
                }
                return key
            }
            return session.assistant.startConversation(pick)
        }
        if (key != null) {
            val currentPick = currentSessionModelPick(session, key)
            if (clearIfDefault || currentPick != null) {
                val management = binding?.assistantManagement
                if (management != null) {
                    try {
                        management.clearModelPick(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: HubFailure) {
                        // Older hubs without `assistant_model_pick` answer 404 / 405; keep sending.
                        if (failure.status != 404 && failure.status != 405) throw failure
                    } catch (_: UnsupportedOperationException) {
                        // Demo or legacy transport without model_pick support.
                    }
                }
            }
        }
        return key
    }

    suspend fun prepareMainModel(pick: ModelPick?, clearIfDefault: Boolean): SessionKey? =
        prepareConversationModel(targetSession = null, pick = pick, clearIfDefault = clearIfDefault)

    /**
     * Creates a new standalone non-main conversation via `session.assistant.startConversation(...)`
     * (never passing `main = true` when a main conversation exists) with an optional independent [pick].
     */
    suspend fun createNewConversation(pick: ModelPick?): SessionKey {
        val (session, _) = requireReadySession()
        return if (pick != null) {
            session.assistant.startConversation(pick)
        } else {
            session.assistant.startConversation()
        }
    }

    suspend fun sendTextToMain(targetMain: SessionKey?, prompt: String, clientMessageId: String) {
        val (session, binding) = requireReadySession()
        val mainKey = targetMain ?: session.assistant.assistant.value.mainSession
        val wireText = formatAssistWireMessage(
            prompt = prompt,
            remoteImagePath = null,
            contextCard = null,
            photosOnlyPrompt = "",
        )
        if (mainKey != null) {
            session.sender.send(mainKey, wireText, clientMessageId)
        } else {
            val mainSender = binding?.mainSender
                ?: error(context.getString(R.string.app_assist_hub_offline_title))
            mainSender.sendToMain(wireText, clientMessageId)
        }
    }

    suspend fun sendWithScreenToMain(
        targetMain: SessionKey?,
        prompt: String,
        screenshot: ComposerAttachment,
        contextCard: ChatLinkContext?,
        clientMessageId: String,
    ) {
        val (session, binding) = requireReadySession()
        val activeBinding = binding ?: error(context.getString(R.string.app_assist_hub_offline_title))
        val targetKey = targetMain
            ?: resolveExistingMainSession(session)
            ?: activeBinding.mainUploadTarget?.invoke()
            ?: run {
                try {
                    session.assistant.startConversation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            ?: error(context.getString(R.string.app_assist_hub_offline_title))
        val remotePath = uploadAttachment(
            context = appContext,
            service = activeBinding.composer,
            session = targetKey,
            attachment = screenshot,
        )
        val wireText = formatAssistWireMessage(
            prompt = prompt,
            remoteImagePath = remotePath,
            contextCard = contextCard,
            photosOnlyPrompt = context.getString(R.string.app_assist_screen_only_prompt),
        )
        session.sender.send(targetKey, wireText, clientMessageId)
    }

    private suspend fun resolveExistingMainSession(session: ShellSession): SessionKey? {
        session.assistant.assistant.value.mainSession?.let { return it }
        val _ = runCatching { session.assistant.refresh() }
        return session.assistant.assistant.value.mainSession
    }

    private fun currentSessionModelPick(session: ShellSession, key: SessionKey): ModelPick? =
        session.sessions.sessions.value.firstOrNull { it.key == key }?.modelPick
            ?: session.assistant.assistant.value.conversations.firstOrNull { it.key == key }?.modelPick

    private fun requireReadySession(): Pair<ShellSession, ChatAppBindings?> {
        val services = entryPoint?.assistAppServices()
            ?: error(context.getString(R.string.app_assist_hub_unpaired_title))
        val wiring = entryPoint.assistChatWiring()
        val session = services.session.value
            ?: error(context.getString(R.string.app_assist_hub_unpaired_title))
        val binding = wiring.current.value?.takeIf { it.scope.path == session.identity }
        val connState = (binding?.connection ?: session.connection).state.value
        if (connState != ConnectionState.Online) {
            error(context.getString(R.string.app_assist_hub_offline_title))
        }
        return session to binding
    }

    private data class HubBindingTuple(
        val pairingReady: Boolean,
        val pairingError: Boolean,
        val session: ShellSession?,
        val binding: ChatAppBindings?,
    )

    private data class ObservedChat(
        val key: SessionKey?,
        val snapshot: ChatSnapshot?,
    )
}

internal const val MAX_OVERLAY_CONVERSATIONS = 12

/**
 * Selects recent non-archived, sendable non-main assistant conversations for the floating overlay
 * target picker, merging live [sessions] status/model metadata and respecting saved conversation order.
 */
internal fun selectOverlayConversations(
    assistant: AssistantSnapshot,
    sessions: List<Session>,
    limit: Int = MAX_OVERLAY_CONVERSATIONS,
): List<AssistConversationOption> {
    val mainKey = assistant.mainSession
    val backgroundId = assistant.backgroundSupervisor?.takeIf(String::isNotBlank)
    val liveByKey = sessions.associateBy { it.key }
    val candidates = assistant.conversations
        .distinctBy { it.key }
        .mapNotNull { row ->
            val live = liveByKey[row.key]
            val archived = live?.archived ?: row.archived
            val isSpace = live?.isSpace ?: row.isSpace
            val readOnly = live?.readOnlyReason ?: row.readOnlyReason
            if (archived || isSpace || readOnly != null) return@mapNotNull null
            if (row.key == mainKey || row.sessionId.isBlank() || row.machineId.isBlank()) return@mapNotNull null
            if (backgroundId != null && row.sessionId == backgroundId) return@mapNotNull null
            val title = (live?.title?.takeIf(String::isNotBlank)
                ?: row.title.takeIf(String::isNotBlank)
                ?: assistant.directionNames[row.sessionId]?.takeIf(String::isNotBlank)).orEmpty().trim()
            val modelPick = live?.modelPick ?: row.modelPick
            val working = (live ?: row).let { it.foldedState == AgentState.Working || it.status == "working" }
            val updated = live?.updated ?: row.updated
            Triple(
                AssistConversationOption(
                    key = row.key,
                    title = title,
                    modelPick = modelPick,
                    working = working,
                ),
                updated,
                row.sessionId,
            )
        }
        .sortedWith(
            compareByDescending<Triple<AssistConversationOption, java.time.Instant?, String>> { it.second }
                .thenBy { it.third },
        )
        .map { it.first }

    val savedOrder = assistant.conversationOrder
    val ordered = if (savedOrder.isNullOrEmpty()) {
        candidates
    } else {
        val byId = candidates.associateBy { it.key.sessionId }
        val pinned = savedOrder.distinct().mapNotNull(byId::get)
        val seen = pinned.mapTo(HashSet()) { it.key.sessionId }
        pinned + candidates.filter { it.key.sessionId !in seen }
    }
    return ordered.take(limit)
}

internal fun mergeOutbox(
    primary: List<OutboxMessage>,
    secondary: List<OutboxMessage>,
): List<OutboxMessage> {
    if (secondary.isEmpty()) return primary
    if (primary.isEmpty()) return secondary
    val seen = HashSet<String>(primary.size + secondary.size)
    return buildList(primary.size + secondary.size) {
        for (item in primary) {
            if (seen.add(item.clientMessageId)) add(item)
        }
        for (item in secondary) {
            if (seen.add(item.clientMessageId)) add(item)
        }
    }
}

internal fun mapConnectionStateToHubStatus(
    connectionState: ConnectionState,
    hasBinding: Boolean,
): AssistHubStatus = when (connectionState) {
    ConnectionState.Online -> if (hasBinding) AssistHubStatus.Ready else AssistHubStatus.Connecting
    ConnectionState.Connecting, ConnectionState.Updating -> AssistHubStatus.Connecting
    ConnectionState.WaitingForNetwork,
    ConnectionState.NeedsLocalNetworkPermission,
    ConnectionState.CertificateMismatch,
    ConnectionState.Revoked,
    -> AssistHubStatus.Offline
}

internal fun formatAssistWireMessage(
    prompt: String,
    remoteImagePath: String?,
    contextCard: ChatLinkContext?,
    photosOnlyPrompt: String,
): String {
    val trimmed = prompt.trim()
    val body = if (remoteImagePath != null) {
        ComposerMessage.text(
            words = trimmed,
            photos = listOf(remoteImagePath),
            files = emptyList(),
            photosOnly = photosOnlyPrompt,
        )
    } else {
        trimmed
    }
    return if (contextCard != null) {
        "${contextCard.xml()}\n\n$body"
    } else {
        body
    }
}
