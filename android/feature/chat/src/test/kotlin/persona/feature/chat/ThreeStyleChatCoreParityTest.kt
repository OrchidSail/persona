package persona.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Interaction
import persona.core.contracts.Item
import persona.core.contracts.Machine
import persona.core.contracts.PendingSwitch
import persona.core.contracts.Resolution
import persona.core.contracts.ServerEvent
import persona.core.contracts.Session
import persona.core.contracts.SessionInfo
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AgentCreationService
import persona.core.contracts.api.AgentDirectories
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.AgentModel
import persona.core.contracts.api.AgentStartRequest
import persona.core.contracts.api.AgentStartResult
import persona.core.contracts.api.ChatAction
import persona.core.contracts.api.ChatActionResult
import persona.core.contracts.api.ChatActions
import persona.core.contracts.api.ChatRepository
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DeliveryState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.InteractionCenter
import persona.core.contracts.api.LauncherModels
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.SessionsRepository
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import persona.core.domain.models.AgentModelPanel

/**
 * End-to-end Compose UI parity tests for Batch 1A across Glass, Brief, and Board:
 * - S-03: Agent chats observe server pendingSwitch, expose AgentModelPill / cross-engine switch confirmation, and send
 *   via `sendApplyingSwitch` regardless of visual style.
 * - S-12: Pending/waiting outbox bubbles offer Edit in both Glass's status-mark menu and the long-press menu across
 *   every style, pulling the message out of the outbox and back into the composer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h840dp-xhdpi")
class ThreeStyleChatCoreParityTest {
    @get:Rule val rule = createComposeRule()

    private val baseSession = Session(
        machineId = "computer",
        sessionId = "worker",
        title = "Worker",
        agent = "claude",
        launcher = "claude",
        model = "sonnet",
        status = "idle",
        state = "idle",
        paneId = "pane-1",
        rev = 5,
    )

    private class RecordingSender(initialOutbox: List<OutboxMessage> = emptyList()) : MessageSender {
        override val outbox = MutableStateFlow(initialOutbox)
        val plainSends = mutableListOf<String>()
        val switchSends = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) {
            plainSends += text
        }

        override suspend fun sendApplyingSwitch(session: SessionKey, text: String, clientMessageId: String) {
            switchSends += text
        }

        override suspend fun cancel(clientMessageId: String): Boolean {
            cancelled += clientMessageId
            outbox.value = outbox.value.filterNot { it.clientMessageId == clientMessageId }
            return true
        }
    }

    private class SwitchWire(private val completeSession: Session) : ChatActions, HubConnection {
        val actions = mutableListOf<ChatAction>()
        override val state = MutableStateFlow(ConnectionState.Online)
        override val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 4)
        override fun reconnect() = Unit
        override fun send(message: JsonObject) = false
        override suspend fun isMuted(session: Session) = false
        override suspend fun execute(session: Session, action: ChatAction): ChatActionResult {
            actions += action
            return ChatActionResult.Done
        }
        override suspend fun verify(session: Session, action: ChatAction) = ChatActionResult.Done
        override suspend fun info(session: Session) = SessionInfo(session.sessionId)
        override suspend fun completeRow(session: Session): Session = completeSession
    }

    private val creation = object : AgentCreationService {
        override val scope = "test"
        override suspend fun launchers(machineId: String) =
            listOf(AgentLauncher("claude"), AgentLauncher("codex", agentType = "codex"))
        override suspend fun launcherModels(machineId: String, launcher: String, refresh: Boolean) =
            LauncherModels(
                launcher,
                if (launcher == "claude") listOf(AgentModel("sonnet", "Sonnet"))
                else listOf(AgentModel("gpt-5.5", "GPT-5.5")),
            )
        override suspend fun directories(machineId: String, path: String) = AgentDirectories(path)
        override suspend fun start(request: AgentStartRequest, operationId: String) = AgentStartResult.Unknown
        override suspend fun operation(operationId: String) = AgentStartResult.Unknown
    }

    private fun mountAgentChat(
        style: AppStyle,
        listed: Session,
        complete: Session = listed,
        sender: RecordingSender = RecordingSender(),
        composer: ChatComposerState = ChatComposerState(),
    ): Pair<RecordingSender, SwitchWire> {
        val wire = SwitchWire(complete)
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine(listed.machineId, "Computer", online = true)))
            override val sessions = MutableStateFlow(listOf(listed))
            override suspend fun refresh() = Unit
        }
        val repo = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(loading = false, items = listOf(
                Item("u1", 1, role = "user", kind = "text", text = "Initial prompt"),
            )))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val interactions = object : InteractionCenter {
            override val pending = MutableStateFlow(emptyList<Interaction>())
            override suspend fun refresh() = Unit
            override suspend fun resolve(id: String, resolution: Resolution) = Unit
        }
        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides setOf(AgentModelPanel.ENGINE_SWITCH, "sessions_lookup")) {
                PersonaTheme(reduceMotion = true, style = style) {
                    ChatScreen(
                        listed.key,
                        repo,
                        sender,
                        interactions,
                        sessions,
                        onBack = {},
                        chrome = ChatChromeBindings(actions = wire, connection = wire, worktrees = { creation }),
                        composerState = composer,
                    )
                }
            }
        }
        rule.waitForIdle()
        return sender to wire
    }

    @Test fun glassAppliesServerPendingSwitchOnSend() = assertServerPendingSwitchApplied(AppStyle.Glass)
    @Test fun briefAppliesServerPendingSwitchOnSend() = assertServerPendingSwitchApplied(AppStyle.Brief)
    @Test fun boardAppliesServerPendingSwitchOnSend() = assertServerPendingSwitchApplied(AppStyle.Board)

    private fun assertServerPendingSwitchApplied(style: AppStyle) {
        val pending = PendingSwitch(launcher = "codex", model = "gpt-5.5", agent = "codex")
        // Summary row omits pendingSwitch; completeRow carries the server-scheduled switch (e.g., set on Web).
        val summary = baseSession.copy(waiting = false, launcher = null, pendingSwitch = null)
        val full = baseSession.copy(pendingSwitch = pending)
        val (sender, _) = mountAgentChat(style, listed = summary, complete = full)

        // Focus composer to reveal AgentModelPill and verify it shows the pending target engine/model in every style.
        val expectedModel = AgentModelPanel.modelLabel("gpt-5.5")
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("model-pill") and hasContentDescription("Codex", substring = true) and
                hasContentDescription(expectedModel, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("model-pill").assertContentDescriptionContains("Codex", substring = true)
        rule.onNodeWithTag("model-pill").assertContentDescriptionContains(expectedModel, substring = true)

        // Sending a message must invoke sendApplyingSwitch rather than ordinary send.
        rule.onNode(hasSetTextAction()).performTextInput("Continue on Codex")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(10_000) { sender.switchSends.isNotEmpty() }
        rule.runOnIdle {
            assertEquals("$style must send via sendApplyingSwitch", listOf("Continue on Codex"), sender.switchSends)
            assertTrue("$style must not bypass pendingSwitch with ordinary send", sender.plainSends.isEmpty())
        }
    }

    @Test fun briefCanScheduleCrossEngineSwitchFromModelPill() = assertCanScheduleCrossEngineSwitch(AppStyle.Brief)
    @Test fun boardCanScheduleCrossEngineSwitchFromModelPill() = assertCanScheduleCrossEngineSwitch(AppStyle.Board)

    private fun assertCanScheduleCrossEngineSwitch(style: AppStyle) {
        val (_, wire) = mountAgentChat(style, listed = baseSession)
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("model-pill")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("model-pill").performClick()
        val target = hasText(AgentModelPanel.modelLabel("gpt-5.5")) and hasAnyAncestor(isDialog())
        rule.waitUntil(10_000) { rule.onAllNodes(target, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(target, useUnmergedTree = true).performClick()
        rule.onNodeWithText("Switch Agent?").assertExists()
        rule.onNodeWithText("Switch Agent").performClick()
        rule.waitUntil(10_000) { wire.actions.isNotEmpty() }
        rule.runOnIdle {
            assertEquals(listOf(ChatAction.Modes(launcher = "codex", from = "claude", model = "gpt-5.5")), wire.actions)
        }
    }

    @Test fun glassPendingBubbleLongPressEditRestoresToComposer() = assertPendingBubbleLongPressEdit(AppStyle.Glass)
    @Test fun briefPendingBubbleLongPressEditRestoresToComposer() = assertPendingBubbleLongPressEdit(AppStyle.Brief)
    @Test fun boardPendingBubbleLongPressEditRestoresToComposer() = assertPendingBubbleLongPressEdit(AppStyle.Board)

    private fun assertPendingBubbleLongPressEdit(style: AppStyle) {
        val queued = OutboxMessage("out-1", baseSession.key, "Wait to revise this prompt", DeliveryState.WaitingForNetwork)
        val sender = RecordingSender(listOf(queued))
        val composer = ChatComposerState()
        mountAgentChat(style, listed = baseSession, sender = sender, composer = composer)

        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Wait to revise this prompt")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Wait to revise this prompt").performTouchInput { longClick() }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Edit") and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("Edit") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { sender.cancelled.contains("out-1") && composer.value.text == "Wait to revise this prompt" }
        rule.runOnIdle {
            assertTrue("$style outbox entry must be cancelled on Edit", sender.outbox.value.isEmpty())
            assertEquals("Wait to revise this prompt", composer.value.text)
        }
    }
}
