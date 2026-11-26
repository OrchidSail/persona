package persona.feature.assistant

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.HubScope
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantManagerChoice
import persona.core.contracts.api.AssistantNotes
import persona.core.contracts.api.AssistantRefused
import persona.core.contracts.api.AssistantRepository
import persona.core.contracts.api.AssistantSnapshot
import persona.core.contracts.api.DraftKey
import persona.core.contracts.api.DraftRepository
import persona.core.contracts.api.DraftSnapshot
import persona.core.contracts.api.MainConversationSender
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask
import persona.core.designsystem.AppFeedback
import persona.core.designsystem.AppStyle
import persona.core.designsystem.FeedbackStyle
import persona.core.designsystem.LocalAppFeedback
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * Interactive Compose UI tests for W-C01 permanent assistant conversation deletion across Glass, Brief, and Board:
 * Hub capability gating, main & supervisor & busy protections, cancel vs confirm, switching back to main when
 * deleting the currently open conversation, server refusal error handling, shared sidebar, and archived sheet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h1500dp-xhdpi")
class ConversationDeleteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val operations = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() {
        operations.cancel()
        AssistantManagerChoice.reset()
    }

    private val main = Session(machineId = "hub", sessionId = "main", title = "Main chat", status = "idle", assistant = true)
    private val first = Session(machineId = "hub", sessionId = "side-1", title = "Trip planning", status = "idle", assistant = true)
    private val busy = Session(machineId = "hub", sessionId = "side-2", title = "Live deploy", status = "working", assistant = true)
    private val supervisor = Session(machineId = "hub", sessionId = "bg-sup", title = "Background supervisor", status = "idle", assistant = true)
    private val archivedChat = Session(machineId = "hub", sessionId = "old-1", title = "Old weekly report", archived = true, status = "idle", assistant = true)

    private val navigator = object : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }

    private class DeleteHub(
        main: Session,
        vararg chats: Session,
        builtInSupervisor: String? = null,
        archived: List<Session> = emptyList(),
    ) : AssistantRepository, MessageSender, AssistantManagement {
        override val assistant = MutableStateFlow(
            AssistantSnapshot(
                mainSession = main.key,
                conversations = listOf(main) + chats,
                backgroundSupervisor = builtInSupervisor,
            ),
        )
        override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
        var archivedList: List<Session> = archived
        val deleted = mutableListOf<SessionKey>()
        var deleteFailure: Exception? = null
        var refreshes = 0

        override suspend fun deleteConversation(session: SessionKey) {
            deleted += session
            deleteFailure?.let { throw it }
            assistant.value = assistant.value.copy(conversations = assistant.value.conversations.filterNot { it.key == session })
            archivedList = archivedList.filterNot { it.key == session }
        }

        override suspend fun archivedConversations(): List<Session> = archivedList
        override suspend fun refresh() { refreshes++ }
        override suspend fun warm() = Unit
        override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = error("unused")
        override suspend fun endConversation(session: SessionKey) = Unit
        override suspend fun archiveConversation(session: SessionKey) = Unit
        override suspend fun unarchiveConversation(session: SessionKey) = Unit
        override suspend fun renameConversation(session: SessionKey, title: String) = Unit
        override suspend fun tasks(): List<ScheduledTask> = emptyList()
        override suspend fun saveTask(task: ScheduledTask) = task
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = error("unused")
        override suspend fun freshTask(id: String) = error("unused")
        override suspend fun runs(): List<Run> = emptyList()
        override suspend fun notes() = AssistantNotes()
        override suspend fun setNotes(text: String) = Unit
        override suspend fun memory(): List<MemoryFile> = emptyList()
        override suspend fun saveMemory(name: String, content: String) = Unit
        override suspend fun deleteMemory(name: String) = Unit
    }

    @Test fun entryIsHiddenWhenHubDoesNotAdvertiseFeature() {
        val hub = DeleteHub(main, first, archived = listOf(archivedChat))
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Glass) {
                CompositionLocalProvider(LocalHubFeatures provides emptySet()) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = first.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_conversation_list)).performClick()
        rule.waitForIdle()
        // Row menu hides Delete… without capability.
        val copyIdText = rule.activity.getString(R.string.assistant_copy_session_id)
        rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.assistant_conversation_actions))[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(copyIdText).assertExists()
        rule.onAllNodesWithText(rule.activity.getString(R.string.assistant_delete_menu)).assertCountEquals(0)
        rule.onNodeWithText(copyIdText).performClick()
        rule.waitForIdle()

        // Archived sheet long-press also hides Delete… without capability.
        rule.onNodeWithText(rule.activity.getString(R.string.assistant_archived_count, 1)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText(archivedChat.title) and hasAnyAncestor(isDialog()) and hasClickAction())
            .performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onAllNodesWithText(rule.activity.getString(R.string.assistant_delete_menu)).assertCountEquals(0)
    }

    @Test fun mainSupervisorAndWorkingChatsNeverShowDelete() {
        val hub = DeleteHub(main, first, busy, supervisor, builtInSupervisor = supervisor.sessionId)
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Brief) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(ConversationDeleteFeature)) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = main.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val copyIdText = rule.activity.getString(R.string.assistant_copy_session_id)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_conversation_list)).performClick()
        rule.waitForIdle()

        // Main conversation row (index 0): never offers Delete….
        rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.assistant_conversation_actions))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText(copyIdText).assertExists()
        rule.onAllNodesWithText(deleteMenu).assertCountEquals(0)
        rule.onNodeWithText(copyIdText).performClick()
        rule.waitForIdle()

        // Working conversation row: hides Delete… while running.
        rule.onNode(hasText(busy.title) and hasClickAction()).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(copyIdText).assertExists()
        rule.onAllNodesWithText(deleteMenu).assertCountEquals(0)
        rule.onNodeWithText(copyIdText).performClick()
        rule.waitForIdle()

        // Built-in background supervisor (folded under All chats): never offers Delete….
        rule.onNodeWithText("All chats (3)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText(rule.activity.getString(R.string.assistant_background_supervisor)) and hasClickAction())
            .performScrollTo()
            .performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(copyIdText).assertExists()
        rule.onAllNodesWithText(deleteMenu).assertCountEquals(0)
    }

    @Test fun deletingActiveConversationSwitchesBackToMainInGlass() =
        verifyDeleteActiveSwitchesToMain(AppStyle.Glass)

    @Test fun deletingActiveConversationSwitchesBackToMainInBrief() =
        verifyDeleteActiveSwitchesToMain(AppStyle.Brief)

    @Test fun deletingActiveConversationSwitchesBackToMainInBoard() =
        verifyDeleteActiveSwitchesToMain(AppStyle.Board)

    private fun verifyDeleteActiveSwitchesToMain(style: AppStyle) {
        val hub = DeleteHub(main, first)
        val notices = mutableListOf<Pair<String, FeedbackStyle>>()
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = style) {
                CompositionLocalProvider(
                    LocalHubFeatures provides setOf(ConversationDeleteFeature),
                    LocalAppFeedback provides object : AppFeedback {
                        override fun failure(failure: Throwable, operation: Boolean) = true
                        override fun notice(message: String) = false
                        override fun notice(message: String, style: FeedbackStyle): Boolean {
                            notices += message to style
                            return true
                        }
                    },
                ) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = first.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val deleteTitle = rule.activity.getString(R.string.assistant_delete_title, first.title)
        val deleteConfirm = rule.activity.getString(R.string.assistant_delete_confirm)
        val cancelText = rule.activity.getString(R.string.assistant_cancel)
        val deletedToast = rule.activity.getString(R.string.assistant_deleted)
        rule.onNodeWithText("chat:${first.sessionId}").assertExists()

        // Open drawer and trigger Delete… on the currently open non-main chat.
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_conversation_list)).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(first.title) and hasClickAction()).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()

        // Cancel keeps conversation untouched.
        rule.onNode(hasText(deleteTitle) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText(cancelText) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertTrue("Cancel must not delete in $style", hub.deleted.isEmpty())

        // Confirm permanently deletes, refreshes, shows toast, and switches back to main.
        rule.onNode(hasText(first.title) and hasClickAction()).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { hub.deleted == listOf(first.key) && hub.refreshes >= 1 }
        rule.waitForIdle()

        assertEquals("style=$style", listOf(first.key), hub.deleted)
        assertTrue("style=$style should show deleted toast: $notices", deletedToast to FeedbackStyle.Success in notices)
        rule.onAllNodesWithText(first.title).assertCountEquals(0)
        rule.onNodeWithText("chat:main").assertExists()
    }

    @Test fun titleMenuDeletesActiveNonMainConversationAndSwitchesToMain() {
        val hub = DeleteHub(main, first)
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Glass) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(ConversationDeleteFeature)) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = first.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val deleteTitle = rule.activity.getString(R.string.assistant_delete_title, first.title)
        val deleteConfirm = rule.activity.getString(R.string.assistant_delete_confirm)
        rule.onNodeWithText("chat:${first.sessionId}").assertExists()
        // Open top-bar title menu while drawer is closed.
        rule.onNode(hasText("Persona") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteTitle) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText(deleteConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { hub.deleted == listOf(first.key) && hub.refreshes >= 1 }
        rule.waitForIdle()
        rule.onNodeWithText("chat:main").assertExists()
    }

    @Test fun serverRefusalKeepsDialogOpenWithInlineErrorAndDoesNotSwitchAway() {
        val hub = DeleteHub(main, first).apply {
            deleteFailure = AssistantRefused("conversation_busy", "This conversation is still running")
        }
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Glass) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(ConversationDeleteFeature)) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = first.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val deleteConfirm = rule.activity.getString(R.string.assistant_delete_confirm)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_conversation_list)).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(first.title) and hasClickAction()).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { hub.deleted == listOf(first.key) }
        rule.waitForIdle()

        rule.onNode(hasText("This conversation is still running") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("chat:${first.sessionId}").assertExists()
    }

    @Test fun archivedSheetLongPressDeletesArchivedConversation() {
        val hub = DeleteHub(main, first, archived = listOf(archivedChat))
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Board) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(ConversationDeleteFeature)) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = hub,
                        messageSender = hub,
                        startConversation = { error("unused") },
                        requestedSession = main.key,
                        management = hub,
                        composerService = null,
                        chatContent = { Text("chat:${it.sessionId}") },
                    )
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val deleteTitle = rule.activity.getString(R.string.assistant_delete_title, archivedChat.title)
        val deleteConfirm = rule.activity.getString(R.string.assistant_delete_confirm)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_conversation_list)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.assistant_archived_count, 1)).performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNode(hasText(archivedChat.title) and hasAnyAncestor(isDialog()) and hasClickAction())
            .performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteTitle) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText(deleteConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { hub.deleted == listOf(archivedChat.key) }
        rule.waitForIdle()

        assertEquals(listOf(archivedChat.key), hub.deleted)
        rule.onNodeWithText(rule.activity.getString(R.string.assistant_archived_empty)).assertExists()
    }

    @Test fun sharedSidebarNotifiesRetiredOnPermanentDelete() {
        val choice = AssistantManagerChoice.current.value
        val hub = DeleteHub(main, first)
        var snapshot by mutableStateOf(
            AssistantSnapshot(
                mainSession = main.key,
                conversations = listOf(main, first),
                manager = choice.manager,
                managerId = choice.shown,
            ),
        )
        val repository = object : AssistantRepository {
            override val assistant = MutableStateFlow(snapshot)
            override suspend fun refresh() {
                val next = snapshot.copy(conversations = snapshot.conversations.filterNot { it.key in hub.deleted })
                snapshot = next
                assistant.value = next
            }
        }
        val retired = mutableListOf<SessionKey>()
        val drafts = object : DraftRepository {
            override fun observe(key: DraftKey): Flow<DraftSnapshot> = flowOf(DraftSnapshot(HubScope("hub", "gen"), key))
            override suspend fun read(key: DraftKey) = DraftSnapshot(HubScope("hub", "gen"), key)
            override suspend fun save(key: DraftKey, text: String, expectedVersion: Long) =
                DraftSnapshot(HubScope("hub", "gen"), key, text, expectedVersion + 1)
        }
        val mainSender = object : MainConversationSender {
            override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
            override suspend fun sendToMain(text: String, clientMessageId: String, draft: DraftSnapshot?) = Unit
        }
        val bindings = AssistantHostBindings(
            identity = "test",
            management = hub,
            drafts = drafts,
            coroutineScope = operations,
            phoneToolsAvailable = false,
            machines = emptyList(),
            conversations = repository,
            mainSender = mainSender,
            chatContent = { _, _ -> },
        )

        rule.setContent {
            PersonaTheme(style = AppStyle.Brief, reduceMotion = true) {
                CompositionLocalProvider(
                    LocalAssistantHost provides bindings,
                    LocalHubFeatures provides setOf(ConversationDeleteFeature),
                ) {
                    Box(Modifier.width(400.dp).height(1200.dp)) {
                        AssistantSidebarContent(
                            snapshot = snapshot,
                            repository = repository,
                            active = first.key,
                            operationScope = operations,
                            demo = false,
                            onOpen = {},
                            onAction = {},
                            onSettings = {},
                            onInbox = {},
                            onKnowledge = {},
                            onTodos = {},
                            todoCount = 0,
                            showTodos = false,
                            inboxCount = 0,
                            settingsCount = 0,
                            settingsDot = false,
                            settingsBadge = null,
                            pages = emptyList(),
                            onPage = {},
                            onRetired = { retired += it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val deleteMenu = rule.activity.getString(R.string.assistant_delete_menu)
        val deleteConfirm = rule.activity.getString(R.string.assistant_delete_confirm)
        rule.onNode(hasText(first.title) and hasClickAction()).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNode(hasText(deleteMenu) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(deleteConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { hub.deleted == listOf(first.key) && retired == listOf(first.key) }
        rule.waitForIdle()

        assertEquals(listOf(first.key), hub.deleted)
        assertEquals(listOf(first.key), retired)
        rule.onAllNodesWithText(first.title).assertCountEquals(0)
    }
}
