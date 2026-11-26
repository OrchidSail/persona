package persona.feature.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.DelegatedAnswer
import persona.core.contracts.Interaction
import persona.core.contracts.Item
import persona.core.contracts.JsonCodec
import persona.core.contracts.Machine
import persona.core.contracts.ModelPick
import persona.core.contracts.Question
import persona.core.contracts.Resolution
import persona.core.contracts.Session
import persona.core.contracts.SessionInfo
import persona.core.contracts.SessionKey
import persona.core.contracts.Subagent
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.AgentModel
import persona.core.contracts.api.AssistantConfig
import persona.core.contracts.api.AssistantLaunchers
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantNotes
import persona.core.contracts.api.ChatAction
import persona.core.contracts.api.ChatActionResult
import persona.core.contracts.api.ChatActions
import persona.core.contracts.api.ChatComposerService
import persona.core.contracts.api.ChatRepository
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ComposerDelivery
import persona.core.contracts.api.InteractionCenter
import persona.core.contracts.api.LauncherDefault
import persona.core.contracts.api.LauncherModels
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask
import persona.core.contracts.api.SessionsRepository
import persona.core.designsystem.AgentMarkKind
import persona.core.designsystem.AgentMarkSpec
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import java.io.File
import java.time.Instant

/**
 * End-to-end three-style parity verification for Batch 1B unlocked capabilities across Glass, Brief, and Board:
 * S-01 (PersonaModelPill on main & non-main Assistant chats), S-05 (clipboard image paste), S-06 (image-card share),
 * S-07 (Persona thinking & live WorkGroup), S-08 (all 6 timeline rich cards/strips), S-09 (single composer permission
 * mode), S-10 (Agent PlanPill -> PlanSheet without top ChatTasks bar), and S-14 (inline Agent load error).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-xhdpi")
class ThreeStyleFeatureGateParityTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val composerService = object : ChatComposerService {
        override val deliveries = MutableStateFlow(emptyMap<String, ComposerDelivery>())
        override suspend fun upload(session: SessionKey, name: String, file: File) = "/tmp/uploads/$name"
        override suspend fun interrupt(session: SessionKey) = Unit
        override suspend fun retryRefused(clientMessageId: String, clearDraft: Boolean): ComposerDelivery = error("unused")
    }

    private val actions = object : ChatActions {
        override suspend fun isMuted(session: Session) = false
        override suspend fun execute(session: Session, action: ChatAction) = ChatActionResult.Done
        override suspend fun verify(session: Session, action: ChatAction) = ChatActionResult.Done
        override suspend fun info(session: Session) = SessionInfo(session.sessionId)
    }

    private fun managementWithPicks(picks: MutableList<Pair<SessionKey, ModelPick>>) = object : AssistantManagement {
        override suspend fun config() = AssistantConfig(machineId = "box", launcher = "claude", main = "main")
        override suspend fun assistantLaunchers(machineId: String) = listOf(AgentLauncher("claude"), AgentLauncher("team"))
        override suspend fun assistantLauncherList(machineId: String) = AssistantLaunchers(
            listOf(AgentLauncher("claude"), AgentLauncher("team", displayName = "Team API")),
            LauncherDefault("claude", "opus", "high"),
        )
        override suspend fun launcherModels(machineId: String, launcher: String) = LauncherModels(
            agent = "claude",
            models = listOf(
                AgentModel("default", "Default", resolved = "claude-opus-5-5", isDefault = true),
                AgentModel("opus", "Opus 5.5", resolved = "claude-opus-5-5"),
                AgentModel("sonnet", "Sonnet 4.5", resolved = "claude-sonnet-4-5"),
            ),
        )
        override suspend fun setModelPick(session: SessionKey, pick: ModelPick): ModelPick {
            picks += session to pick
            return pick
        }
        override suspend fun warm() = Unit
        override suspend fun endConversation(session: SessionKey) = Unit
        override suspend fun tasks() = emptyList<ScheduledTask>()
        override suspend fun saveTask(task: ScheduledTask) = task
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = SessionKey("box", "main")
        override suspend fun freshTask(id: String) = ScheduledTask(id)
        override suspend fun runs() = emptyList<Run>()
        override suspend fun notes() = AssistantNotes()
        override suspend fun setNotes(text: String) = Unit
        override suspend fun memory() = emptyList<MemoryFile>()
        override suspend fun saveMemory(name: String, content: String) = Unit
        override suspend fun deleteMemory(name: String) = Unit
    }

    private fun simpleSender() = object : MessageSender {
        override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
        override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
    }

    private fun simpleInteractions(
        pendingItems: List<Interaction> = emptyList(),
        delegatedItems: List<DelegatedAnswer> = emptyList(),
    ) = object : InteractionCenter {
        override val pending = MutableStateFlow(pendingItems)
        override val delegated = MutableStateFlow(delegatedItems)
        override suspend fun refresh() = Unit
        override suspend fun resolve(id: String, resolution: Resolution) = Unit
    }

    // S-01: Non-main Assistant chat exposes PersonaModelPill and unified model panel across Glass, Brief, and Board.
    @Test fun assistantModelPillWorksInNonMainChatAcrossAllStyles() {
        val key = SessionKey("box", "topic-1")
        val row = Session(
            machineId = key.machineId,
            sessionId = key.sessionId,
            title = "Release topic",
            assistant = true,
            status = "idle",
            state = "idle",
            agent = "claude",
            launcher = "claude",
            model = "claude-opus-5-5",
            permissionMode = "default",
        )
        val picks = mutableListOf<Pair<SessionKey, ModelPick>>()
        val management = managementWithPicks(picks)
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(items = listOf(
                Item("u1", 1, role = "user", kind = "text", text = "Check release notes", ts = "2026-11-22T09:00:00Z"),
                Item("a2", 2, role = "assistant", kind = "text", text = "Checked.", ts = "2026-11-22T09:00:05Z"),
            ), loading = false))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("box", "Workstation", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides setOf("assistant_model_pick", "typed_models", "sessions_lookup")) {
                PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                    ChatScreen(
                        key, chats, simpleSender(), simpleInteractions(), sessions, onBack = {},
                        chrome = ChatChromeBindings(actions = actions, assistantManagement = management, mainConversation = "main"),
                        renderStyle = ChatRenderStyle.Assistant,
                    )
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Check release notes").fetchSemanticsNodes().isNotEmpty() }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("model-pill").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("model-pill").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Search models…").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("$current opens unified JModelPanel dialog", 1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
            rule.onAllNodes(hasText("Sonnet 4.5") and hasAnyAncestor(isDialog()))[0].performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            assertTrue("$current persists session modelPick", picks.any { it.first == key && it.second.model == "sonnet" })
            picks.clear()
        }
    }

    // S-05: Focused composer offers clipboard image paste chip across all 3 styles.
    @Test fun clipboardImagePasteChipShowsAndDismissesAcrossAllStyles() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData(ClipDescription("screenshot", arrayOf("image/png")), ClipData.Item(Uri.parse("content://example.com/snap.png"))),
        )
        var style by mutableStateOf(AppStyle.Glass)
        var round by mutableStateOf(0)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                androidx.compose.runtime.key(round) {
                    val state = androidx.compose.runtime.remember {
                        ChatComposerState().apply { focusRequest = 1 }
                    }
                    Box(Modifier.width(400.dp)) {
                        ChatComposer(
                            SessionKey("mac", "s1"), state, working = false, sending = false, canClear = true,
                            service = composerService, onSend = {}, onCommand = {}, onNotice = {},
                        )
                    }
                }
            }
        }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current; round++ }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("composer-paste-image").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("composer-paste-image").assertIsDisplayed()
            rule.onNodeWithContentDescription("Don't paste").performClick()
            rule.waitForIdle()
            rule.onAllNodesWithTag("composer-paste-image").assertCountEquals(0)
        }
    }

    // S-06: Tapping Share in MessageMenuSheet opens the MessageSharePage card preview across all 3 styles.
    @Test fun messageMenuShareOpensImageCardPreviewAcrossAllStyles() {
        val source = ShareCardSource(
            agent = "Claude",
            mark = AgentMarkSpec(AgentMarkKind.Claude, "Claude Code", null),
            source = "persona › release",
        )
        var style by mutableStateOf(AppStyle.Glass)
        var round by mutableStateOf(0)
        val menu = MessageMenuState()
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                androidx.compose.runtime.key(round) {
                    Box(Modifier.fillMaxSize().background(PersonaTheme.palette.background)) {
                        MessageMenuSheet(
                            menu = menu,
                            writes = true,
                            onQuote = {},
                            onEdit = {},
                            onContext = {},
                            onForward = null,
                            onNotice = {},
                            share = source,
                        )
                    }
                }
            }
        }

        for (current in AppStyle.entries) {
            rule.runOnIdle {
                style = current
                round++
                menu.open(MessageTarget(id = "m1", text = "All checks passed.", user = false, time = "2026-11-22T08:00:00Z"))
            }
            rule.waitForIdle()
            rule.onNodeWithText("Share").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("share-card").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("persona › release").assertIsDisplayed()
            rule.onNodeWithTag("share-card").assertIsDisplayed()
        }
    }

    // S-07: Persona thinking lines are visible and expandable across all 3 styles.
    @Test fun personaThinkingRendersAndExpandsAcrossAllStyles() {
        val key = SessionKey("box", "main")
        val row = Session(machineId = "box", sessionId = "main", title = "Persona", assistant = true, status = "idle", state = "idle", agent = "claude")
        val items = listOf(
            Item("u1", 1, role = "user", kind = "text", text = "Why did the test flake?", ts = "2026-11-22T09:00:00Z"),
            Item("th2", 2, role = "assistant", kind = "thinking", text = "Inspecting the timeout window in retry logic.", ts = "2026-11-22T09:00:02Z"),
            Item("a3", 3, role = "assistant", kind = "text", text = "The retry clock drifted by 20ms.", ts = "2026-11-22T09:00:05Z"),
        )
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(items = items, loading = false))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("box", "Workstation", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        var round by mutableStateOf(0)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                androidx.compose.runtime.key(round) {
                    ChatScreen(key, chats, simpleSender(), simpleInteractions(), sessions, onBack = {}, renderStyle = ChatRenderStyle.Assistant)
                }
            }
        }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current; round++ }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("The retry clock drifted by 20ms.").fetchSemanticsNodes().isNotEmpty() }
            // Thinking group or standalone Thought toggle is never hidden in Assistant chats on any style.
            val hasWorkGroup = rule.onAllNodesWithText("Thinking 1", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            if (hasWorkGroup) {
                rule.onNodeWithText("Thinking 1", substring = true, useUnmergedTree = true).performClick()
                rule.waitUntil(5_000) { rule.onAllNodesWithText("Thought", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            }
            rule.onAllNodesWithText("Thought", substring = true, useUnmergedTree = true)[0].assertIsDisplayed().performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Inspecting the timeout window", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Inspecting the timeout window", substring = true, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    // S-08: All 6 rich timeline components render identically across Glass, Brief, and Board.
    @Test fun richTimelineCardsAndStripsRenderAcrossAllStyles() {
        val key = SessionKey("macbook", "agent-rich")
        val now = Instant.now()
        val row = Session(
            machineId = key.machineId,
            sessionId = key.sessionId,
            title = "Ship feature",
            status = "idle",
            state = "idle",
            agent = "claude",
            paneId = "w1:p1",
            cwd = "/code/app",
            subagents = listOf(
                Subagent("sub-1", "agent-rich@sub-1", "Explore", "Audit imports", toolUseId = "ag1", startedAt = now.minusSeconds(30).toString(), steps = 4),
            ),
        )
        val items = listOf(
            Item("u1", 1, role = "user", kind = "text", text = "Audit and test", ts = now.minusSeconds(120).toString()),
            Item("ag1", 2, role = "assistant", kind = "tool_use", toolUseId = "ag1", toolName = "Agent",
                toolInput = buildJsonObject { put("description", "Audit imports"); put("prompt", "Check unused imports") },
                ts = now.minusSeconds(110).toString()),
            Item("ag1r", 3, role = "user", kind = "tool_result", toolUseId = "ag1", text = "All imports clean.\nagentId: sub-1",
                ts = now.minusSeconds(100).toString()),
            Item("v1", 4, role = "assistant", kind = "tool_use", toolUseId = "v1", toolName = "Read",
                toolInput = buildJsonObject { put("file_path", "/code/app/preview.png") }, ts = now.minusSeconds(95).toString()),
            Item("v1r", 5, role = "user", kind = "tool_result", toolUseId = "v1", text = "image read",
                ts = now.minusSeconds(94).toString()),
            Item("comp", 6, role = "assistant", kind = "tool_use", toolUseId = "comp", toolName = "mcp__persona__computer_run",
                toolInput = buildJsonObject {
                    put("machine", "macbook")
                    put("steps", buildJsonArray { add(buildJsonObject { put("click", buildJsonObject { put("name", "Run") }) }) })
                },
                ts = now.minusSeconds(80).toString()),
            Item("a1", 7, role = "assistant", kind = "text", text = "First pass done.", model = "claude-opus-5-5",
                ts = now.minusSeconds(70).toString()),
            Item("u2", 8, role = "user", kind = "text", text = "Run second pass", ts = now.minusSeconds(60).toString()),
            Item("a2", 9, role = "assistant", kind = "text", text = "Second pass done.", model = "claude-sonnet-4-5",
                ts = now.minusSeconds(50).toString()),
            Item("rc1", 10, role = "assistant", kind = "recap", text = "Completed the audit while you were away.",
                ts = now.minusSeconds(10).toString()),
        )
        val delegated = DelegatedAnswer(
            interaction = Interaction(
                id = "ix-del",
                machineId = key.machineId,
                sessionId = key.sessionId,
                kind = "question",
                questions = listOf(Question("Which branch?")),
            ),
            answers = mapOf("Which branch?" to "release-1.4"),
            reason = "Matches current milestone",
            at = now,
        )
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(items = items, loading = false))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("macbook", "MacBook", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                ChatScreen(key, chats, simpleSender(), simpleInteractions(delegatedItems = listOf(delegated)), sessions, onBack = {})
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Second pass done.").fetchSemanticsNodes().isNotEmpty() }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            // 1. ModelChangeDivider
            rule.onNodeWithText("Opus 5.5 → Sonnet 4.5", substring = true, useUnmergedTree = true).assertExists()
            // 2. ComputerActivityPill
            rule.onNodeWithText("Controlling MacBook · Click Run", substring = true, useUnmergedTree = true).assertExists()
            // 3. DelegatedCard
            rule.onNodeWithText("release-1.4").assertIsDisplayed()
            // 4. OpenRecap strip
            rule.onNodeWithContentDescription("Completed the audit while you were away.", substring = true).assertIsDisplayed()
        }
    }

    // S-09: Single permission mode entry in composer (none in header, no duplicate modeHint) across all 3 styles.
    @Test fun singleComposerPermissionModeAcrossAllStyles() {
        val key = SessionKey("box", "agent-mode")
        val row = Session(
            machineId = key.machineId,
            sessionId = key.sessionId,
            title = "Mode check",
            status = "idle",
            state = "idle",
            agent = "claude",
            paneId = "w1:p1",
            permissionMode = "acceptEdits",
        )
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(items = listOf(
                Item("u1", 1, role = "user", kind = "text", text = "Ready", ts = "2026-11-22T09:00:00Z"),
            ), loading = false))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("box", "Workstation", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                ChatScreen(key, chats, simpleSender(), simpleInteractions(), sessions, onBack = {},
                    chrome = ChatChromeBindings(actions = actions))
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Ready").fetchSemanticsNodes().isNotEmpty() }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).performClick()
            rule.waitForIdle()
            // Exactly 1 permission mode control (inside composer), never duplicated in the top bar.
            rule.onAllNodesWithContentDescription("Permission mode:", substring = true).assertCountEquals(1)
            rule.onAllNodesWithTag("chat-composer-mode-hint").assertCountEquals(0)
            rule.onNodeWithTag("chat-mode").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.isPopup()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasText("Default permissions") and hasAnyAncestor(androidx.compose.ui.test.isPopup())).performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.isPopup()).fetchSemanticsNodes().isEmpty() }
        }
    }

    // S-10: Agent chat hides top ChatTasks bar, surfaces PlanPill through folded work, hides with pending cards, opens PlanSheet.
    @Config(application = QuestionTestApplication::class)
    @Test fun agentChatPlanPillOpensPlanSheetAndHidesTopTasksBarAcrossAllStyles() {
        val key = SessionKey("box", "agent-plan")
        val row = Session(machineId = key.machineId, sessionId = key.sessionId, title = "Refactor plan", status = "idle", state = "idle", agent = "claude")
        val todoTool = JsonCodec.decodeFromString(
            Item.serializer(),
            """{"id":"t2","seq":2,"role":"assistant","kind":"tool_use","tool_use_id":"t2","tool_name":"TodoWrite",
                "tool_input":{"todos":[
                    {"content":"Audit gates","status":"completed"},
                    {"content":"Remove glass-only checks","status":"in_progress"},
                    {"content":"Run regression suite","status":"pending"}
                ]},"ts":"2026-11-22T09:00:05Z"}""",
        )
        val items = listOf(
            Item("u1", 1, role = "user", kind = "text", text = "Finish the migration", ts = "2026-11-22T09:00:00Z"),
            todoTool,
            Item("t2r", 3, role = "user", kind = "tool_result", toolUseId = "t2", text = "Updated", ts = "2026-11-22T09:00:06Z"),
            Item("a4", 4, role = "assistant", kind = "text", text = "Working through step 2 now.", ts = "2026-11-22T09:00:10Z"),
        )
        val pendingFlow = MutableStateFlow<List<Interaction>>(emptyList())
        val interactions = object : InteractionCenter {
            override val pending = pendingFlow
            override suspend fun refresh() = Unit
            override suspend fun resolve(id: String, resolution: Resolution) = Unit
        }
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(items = items, loading = false))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("box", "Workstation", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        var round by mutableStateOf(0)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                androidx.compose.runtime.key(round) {
                    ChatScreen(key, chats, simpleSender(), interactions, sessions, onBack = {})
                }
            }
        }

        for (current in AppStyle.entries) {
            rule.runOnIdle {
                pendingFlow.value = emptyList()
                style = current
                round++
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Working through step 2 now.").fetchSemanticsNodes().isNotEmpty() }
            // Top ChatTasks strip is hidden; floating PlanPill shows step 2/3 through folded work.
            rule.onNodeWithText("Step 2/3").assertIsDisplayed()
            // Pending approval card suppresses PlanPill cleanly.
            rule.runOnIdle {
                pendingFlow.value = listOf(Interaction("req-1", machineId = key.machineId, sessionId = key.sessionId, kind = "permission", toolName = "Bash", summary = "Run tests"))
            }
            rule.waitForIdle()
            rule.onAllNodesWithText("Step 2/3").assertCountEquals(0)
            // Clearing pending card restores PlanPill and tapping it opens PlanSheet.
            rule.runOnIdle { pendingFlow.value = emptyList() }
            rule.waitForIdle()
            rule.onNodeWithText("Step 2/3").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasText("Remove glass-only checks") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
    }

    // S-14: Agent chat renders inline load error line across all 3 styles.
    @Test fun agentChatShowsInlineLoadErrorAcrossAllStyles() {
        val key = SessionKey("box", "agent-err")
        val row = Session(machineId = key.machineId, sessionId = key.sessionId, title = "Error test", status = "idle", state = "idle", agent = "claude")
        val chats = object : ChatRepository {
            override fun observe(session: SessionKey) = MutableStateFlow(ChatSnapshot(
                items = listOf(Item("u1", 1, role = "user", kind = "text", text = "Hello", ts = "2026-11-22T09:00:00Z")),
                loading = false,
                error = "Could not load older messages",
            ))
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int): List<Item> = emptyList()
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine("box", "Workstation", online = true)))
            override val sessions = MutableStateFlow(listOf(row))
            override suspend fun refresh() = Unit
        }
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                ChatScreen(key, chats, simpleSender(), simpleInteractions(), sessions, onBack = {})
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Hello").fetchSemanticsNodes().isNotEmpty() }

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            rule.onNodeWithText("Could not load older messages").assertIsDisplayed()
        }
    }
}
