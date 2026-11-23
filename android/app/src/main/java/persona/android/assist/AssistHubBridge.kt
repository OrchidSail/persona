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
 * Connects [AssistOverlayController] to the app's existing Hub connection, main or dedicated
 * lightweight overlay conversation repository, attachment uploader, model catalog, and live
 * streaming transcript without opening any extra socket.
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

    private val activeSessionOverride = MutableStateFlow<SessionKey?>(null)
    private var observeJob: Job? = null
    private var modelChoiceJob: Job? = null

    fun wakeHub() {
        val _ = runCatching { entryPoint?.assistConnections()?.wake() }
    }

    /** Switches live transcript observation to a dedicated overlay [session] (or null to follow main chat). */
    fun setObservedSession(session: SessionKey?) {
        activeSessionOverride.value = session
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
                val observedSessionFlow = combine(mainSessionFlow, activeSessionOverride) { mainKey, overrideKey ->
                    overrideKey ?: mainKey
                }.distinctUntilChanged()
                val chatFlow = observedSessionFlow.flatMapLatest { key ->
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
                    observedSessionFlow,
                    chatFlow,
                    outboxFlow,
                ) { values ->
                    val connState = values[0] as ConnectionState
                    val assistant = values[1] as persona.core.contracts.api.AssistantSnapshot
                    @Suppress("UNCHECKED_CAST")
                    val sessions = values[2] as List<persona.core.contracts.Session>
                    val targetKey = values[3] as SessionKey?
                    @Suppress("UNCHECKED_CAST")
                    val observedChat = values[4] as Pair<SessionKey?, ChatSnapshot?>
                    @Suppress("UNCHECKED_CAST")
                    val outbox = values[5] as List<OutboxMessage>

                    val mainKey = assistant.mainSession
                    val activeRow = targetKey?.let { key ->
                        sessions.firstOrNull { it.key == key }
                            ?: assistant.conversations.firstOrNull { it.key == key }
                    }
                    val working = activeRow?.foldedState == AgentState.Working ||
                        activeRow?.status == "working"
                    val chat = observedChat.second?.takeIf { observedChat.first == targetKey }
                    val hubStatus = mapConnectionStateToHubStatus(
                        connectionState = connState,
                        hasBinding = activeBinding != null || session.demo != null,
                    )
                    AssistHubSnapshot(
                        status = hubStatus,
                        mainSession = mainKey,
                        observedSession = targetKey,
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
        activeSessionOverride.value = null
    }

    /** Creates a lightweight non-main conversation (`main = false`) using [pick], never touching main chat. */
    suspend fun createSideConversation(pick: ModelPick): SessionKey {
        val (session, _) = requireReadySession()
        return session.assistant.startSideConversation(pick)
    }

    suspend fun sendTextToSession(targetKey: SessionKey, prompt: String, clientMessageId: String) {
        val (session, _) = requireReadySession()
        val wireText = formatAssistWireMessage(
            prompt = prompt,
            remoteImagePath = null,
            contextCard = null,
            photosOnlyPrompt = "",
        )
        session.sender.send(targetKey, wireText, clientMessageId)
    }

    suspend fun sendWithScreenToSession(
        targetKey: SessionKey,
        prompt: String,
        screenshot: ComposerAttachment,
        contextCard: ChatLinkContext?,
        clientMessageId: String,
    ) {
        val (session, binding) = requireReadySession()
        val activeBinding = binding ?: error(context.getString(R.string.app_assist_hub_offline_title))
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

    suspend fun sendTextToMain(prompt: String, clientMessageId: String) {
        val (session, binding) = requireReadySession()
        val mainKey = session.assistant.assistant.value.mainSession
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
        prompt: String,
        screenshot: ComposerAttachment,
        contextCard: ChatLinkContext?,
        clientMessageId: String,
    ) {
        val (session, binding) = requireReadySession()
        val activeBinding = binding ?: error(context.getString(R.string.app_assist_hub_offline_title))
        val targetKey = session.assistant.assistant.value.mainSession
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
