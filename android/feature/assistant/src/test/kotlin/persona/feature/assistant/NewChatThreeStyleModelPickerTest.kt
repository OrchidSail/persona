package persona.feature.assistant

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.AgentModel
import persona.core.contracts.api.AssistantConfig
import persona.core.contracts.api.AssistantLaunchers
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantNotes
import persona.core.contracts.api.AssistantRepository
import persona.core.contracts.api.AssistantSnapshot
import persona.core.contracts.api.LauncherDefault
import persona.core.contracts.api.LauncherModels
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * S-04 regression: Glass, Brief, and Board share the exact same starter model + thinking effort picker
 * (`PersonaModelPill` -> `JModelPanel`) on the blank new-conversation screen and submit the picked
 * `ModelPick(launcher, model, effort)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-xhdpi")
class NewChatThreeStyleModelPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val navigator = object : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }

    private val management = object : AssistantManagement {
        override suspend fun config() = AssistantConfig(machineId = "box", launcher = "claude", main = "main")
        override suspend fun assistantLauncherList(machineId: String) = AssistantLaunchers(
            launchers = listOf(
                AgentLauncher("claude", displayName = "Claude"),
                AgentLauncher("team", displayName = "Team API"),
            ),
            default = LauncherDefault("claude", "opus", "high"),
        )
        override suspend fun launcherModels(machineId: String, launcher: String) = when (launcher) {
            "team" -> LauncherModels("claude", listOf(
                AgentModel("sonnet", "Sonnet 4.5", efforts = listOf("low", "medium", "high", "max"), defaultEffort = "high"),
            ))
            else -> LauncherModels("claude", listOf(
                AgentModel("opus", "Opus 5.5", efforts = listOf("low", "medium", "high", "max"), defaultEffort = "high"),
            ))
        }
        override suspend fun warm() = Unit
        override suspend fun endConversation(session: SessionKey) = Unit
        override suspend fun tasks() = emptyList<ScheduledTask>()
        override suspend fun saveTask(task: ScheduledTask) = task
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = SessionKey("box", "main")
        override suspend fun freshTask(id: String) = ScheduledTask(id)
        override suspend fun runs() = emptyList<Run>()
        override suspend fun notes() = AssistantNotes(machineId = "box")
        override suspend fun setNotes(text: String) = Unit
        override suspend fun memory() = emptyList<MemoryFile>()
        override suspend fun saveMemory(name: String, content: String) = Unit
        override suspend fun deleteMemory(name: String) = Unit
    }

    @Test fun glassPicksStarterModelAndEffortOnBlankPage() = assertStarterModelAndEffortPick(AppStyle.Glass)
    @Test fun briefPicksStarterModelAndEffortOnBlankPage() = assertStarterModelAndEffortPick(AppStyle.Brief)
    @Test fun boardPicksStarterModelAndEffortOnBlankPage() = assertStarterModelAndEffortPick(AppStyle.Board)

    private fun assertStarterModelAndEffortPick(style: AppStyle) {
        val pickedStarts = mutableListOf<ModelPick>()
        val sentMessages = mutableListOf<Pair<SessionKey, String>>()
        val repo = object : AssistantRepository {
            override val assistant = MutableStateFlow(AssistantSnapshot())
            override suspend fun refresh() = Unit
        }
        val sender = object : MessageSender {
            override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
            override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) {
                sentMessages += session to text
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides setOf("assistant_model_pick")) {
                PersonaTheme(reduceMotion = true, style = style) {
                    AssistantScreen(
                        navigator = navigator,
                        repository = repo,
                        messageSender = sender,
                        startConversation = { SessionKey("box", "default-new") },
                        startConversationWith = { launcher -> SessionKey("box", "launcher-$launcher") },
                        startConversationPicked = { pick ->
                            pickedStarts += pick
                            SessionKey("box", "picked-${pick.launcher}-${pick.model}-${pick.effort}")
                        },
                        management = management,
                        composerService = null,
                    )
                }
            }
        }

        // Legacy StarterLauncherChip must not be rendered in Brief or Board.
        rule.onNode(hasText("Default:", substring = true)).assertDoesNotExist()

        // Focus composer to reveal the shared PersonaModelPill.
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("model-pill")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("model-pill").performClick()

        // Wait for Team API -> Sonnet 4.5 to load, open its Customize page, set effort to Max (index 3), return and pick it.
        val sonnetRow = hasText("Sonnet 4.5") and hasAnyAncestor(isDialog())
        rule.waitUntil(10_000) { rule.onAllNodes(sonnetRow, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        // Customize the Sonnet 4.5 row (last Customize button in the dialog corresponds to Team API -> Sonnet 4.5).
        val customizeButtons = rule.onAllNodes(hasContentDescription("Customize") and hasClickAction() and hasAnyAncestor(isDialog()))
        val count = customizeButtons.fetchSemanticsNodes().size
        customizeButtons[count - 1].performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("Thinking effort") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasContentDescription("Thinking effort") and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.SetProgress) { check(it(3f)) }
        rule.onNode(hasContentDescription("Back") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

        rule.waitUntil(10_000) { rule.onAllNodes(sonnetRow, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(sonnetRow, useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

        // Pill reflects the concrete model and thinking effort in Glass, Brief, and Board alike.
        rule.onNodeWithTag("model-pill").assertContentDescriptionContains("Sonnet 4.5", substring = true)
        rule.onNodeWithTag("model-pill").assertContentDescriptionContains("Max", substring = true)

        // Sending starts the new conversation with the exact launcher + model + effort pick.
        rule.onNode(hasSetTextAction()).performTextInput("Start with Sonnet Max")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(10_000) { sentMessages.isNotEmpty() }
        rule.runOnIdle {
            assertEquals("$style must submit exact ModelPick(launcher, model, effort)",
                listOf(ModelPick(launcher = "team", model = "sonnet", effort = "max")), pickedStarts)
            assertEquals("Start with Sonnet Max", sentMessages.single().second)
        }
    }
}
