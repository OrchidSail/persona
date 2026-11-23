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
import kotlinx.coroutines.launch
import persona.android.PersonaApp
import persona.android.R
import persona.android.di.ConnectionRuntime
import persona.android.ui.AppServices
import persona.android.ui.ShellSession
import persona.core.contracts.AgentState
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
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
 * Connects [AssistOverlayController] to the app's existing Hub connection, main conversation
 * repository, in-place main-conversation model switcher, attachment uploader, model catalog, and
 * live streaming transcript without opening any extra socket or creating side conversations.
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

    private var observeJob: Job? = null
    private var modelChoiceJob: Job? = null

    fun wakeHub() {
        val _ = runCatching { entryPoint?.assistConnections()?.wake() }
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
                val mainSessionFlow = session.assistant.assistant.map { it.mainSession }.distinctUntilChanged()
                val chatFlow = mainSessionFlow.flatMapLatest { key ->
                    if (key == null) flowOf<Pair<SessionKey?, ChatSnapshot?>>(null to null)
                    else session.chats.observe(key).map { key to it }
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
                    val activeRow = mainKey?.let { key ->
                        sessions.firstOrNull { it.key == key }
                            ?: assistant.conversations.firstOrNull { it.key == key }
                    }
                    val working = activeRow?.foldedState == AgentState.Working ||
                        activeRow?.status == "working"
                    val chat = observedChat.second?.takeIf { observedChat.first == mainKey }
                    val hubStatus = mapConnectionStateToHubStatus(
                        connectionState = connState,
                        hasBinding = activeBinding != null || session.demo != null,
                    )
                    AssistHubSnapshot(
                        status = hubStatus,
                        mainSession = mainKey,
                        mainModelPick = activeRow?.modelPick,
                        mainWorking = working,
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
        observeJob?.cancel()
        observeJob = null
        modelChoiceJob?.cancel()
        modelChoiceJob = null
    }

    /**
     * Switches the Hub's main conversation in place to [pick] (`PUT /api/v1/assistant/conversations/{id}/model`),
     * or clears its custom pick (`DELETE .../model`) when [pick] is null.
     *
     * When the paired Hub has no main conversation yet (first-time pairing), creates the main
     * conversation (`main = true`) directly with [pick] when [pick] != null — never creating a
     * non-main side conversation.
     */
    suspend fun prepareMainModel(pick: ModelPick?, clearIfDefault: Boolean): SessionKey? {
        val (session, binding) = requireReadySession()
        val mainKey = resolveExistingMainSession(session)
        if (pick != null) {
            if (mainKey != null) {
                val activeBinding = binding ?: error(context.getString(R.string.app_assist_hub_offline_title))
                val currentPick = currentMainModelPick(session, mainKey)
                if (currentPick != pick) {
                    val _ = activeBinding.assistantManagement.setModelPick(mainKey, pick)
                }
                return mainKey
            }
            return session.assistant.startConversation(pick)
        }
        if (mainKey != null) {
            val currentPick = currentMainModelPick(session, mainKey)
            if (clearIfDefault || currentPick != null) {
                val management = binding?.assistantManagement
                if (management != null) {
                    try {
                        management.clearModelPick(mainKey)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: HubFailure) {
                        // Older hubs without `assistant_model_pick` answer 404 / 405; keep sending to main.
                        if (failure.status != 404 && failure.status != 405) throw failure
                    } catch (_: UnsupportedOperationException) {
                        // Demo or legacy transport without model_pick support.
                    }
                }
            }
        }
        return mainKey
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

    private fun currentMainModelPick(session: ShellSession, mainKey: SessionKey): ModelPick? =
        session.sessions.sessions.value.firstOrNull { it.key == mainKey }?.modelPick
            ?: session.assistant.assistant.value.conversations.firstOrNull { it.key == mainKey }?.modelPick

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
