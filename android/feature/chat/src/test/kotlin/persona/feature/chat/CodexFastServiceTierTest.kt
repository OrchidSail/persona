package persona.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.CoreText
import persona.core.contracts.ServiceTier
import persona.core.contracts.Session
import persona.core.contracts.api.ChatAction
import persona.core.contracts.api.ChatActionResult
import persona.core.contracts.api.ChatActions
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp-xhdpi")
class CodexFastServiceTierTest {
    @get:Rule val rule = createComposeRule()

    private val fastTiers = listOf(
        ServiceTier("default", "Standard"),
        ServiceTier("priority", "Fast"),
    )

    private fun codexSession(tier: String = "default", assistant: Boolean = false) = Session(
        machineId = "mac",
        sessionId = "s1",
        status = "idle",
        agent = "codex",
        launcher = "codex",
        model = "gpt-5",
        assistant = assistant,
        serviceTier = tier,
        serviceTiers = fastTiers,
    )

    @Test fun sessionWithDetailAndChatModeHintPreserveAndDisplayFastTier() {
        val complete = codexSession(tier = "priority")
        val summary = Session(machineId = "mac", sessionId = "s1", status = "idle", agent = "codex", waiting = false)
        val merged = sessionWithDetail(summary, previous = null, info = null, complete = complete)
        assertEquals("priority", merged.serviceTier)
        assertEquals(fastTiers, merged.serviceTiers)

        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val hint = chatModeHint(merged, res, includeModes = true)
        assertNotNull(hint)
        assertTrue("expected Fast in hint, got $hint", hint!!.contains("Fast"))
    }

    private fun makeState(actions: ChatActions): ChatChromeState = ChatChromeState(
        res = ApplicationProvider.getApplicationContext<android.content.Context>().resources,
        actions = actions,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        refresh = {},
        onClosed = {},
    )

    private fun assertModeBarFastToggleInStyle(style: AppStyle) {
        val sent = mutableListOf<ChatAction>()
        val actions = object : ChatActions {
            override suspend fun isMuted(session: Session) = false
            override suspend fun execute(session: Session, action: ChatAction): ChatActionResult {
                sent += action
                return ChatActionResult.Done
            }
            override suspend fun verify(session: Session, action: ChatAction) = ChatActionResult.Done
        }
        val state = makeState(actions)
        val session = codexSession(tier = "default", assistant = true)

        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides emptySet()) {
                PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                    ChatModeBar(session = session, state = state, online = true, openRequested = true)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chat-modes-fast-switch").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("style=$style", listOf(ChatAction.Modes(serviceTier = "priority")), sent)
    }

    @Test fun chatModeBarFastSwitchWorksInGlass() = assertModeBarFastToggleInStyle(AppStyle.Glass)
    @Test fun chatModeBarFastSwitchWorksInBrief() = assertModeBarFastToggleInStyle(AppStyle.Brief)
    @Test fun chatModeBarFastSwitchWorksInBoard() = assertModeBarFastToggleInStyle(AppStyle.Board)

    @Test fun failedServiceTierToggleDoesNotOptimisticallyFlipState() {
        val actions = object : ChatActions {
            override suspend fun isMuted(session: Session) = false
            override suspend fun execute(session: Session, action: ChatAction) =
                ChatActionResult.Failed(CoreText.plain("node_outdated"))
            override suspend fun verify(session: Session, action: ChatAction) = ChatActionResult.Done
        }
        val state = makeState(actions)
        val session = codexSession(tier = "default")

        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides emptySet()) {
                PersonaTheme(style = AppStyle.Brief, darkTheme = false, reduceMotion = true) {
                    ChatModeBar(session = session, state = state, online = true, openRequested = true)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chat-modes-fast-switch").assertIsOff().performClick()
        rule.waitForIdle()
        // Remains OFF because server refused the billable speed switch
        rule.onNodeWithTag("chat-modes-fast-switch").assertIsOff()
    }

    @Test fun glassAgentModelPillReflectsFastAndTogglesServiceTier() {
        val sent = mutableListOf<ChatAction>()
        val actions = object : ChatActions {
            override suspend fun isMuted(session: Session) = false
            override suspend fun execute(session: Session, action: ChatAction): ChatActionResult {
                sent += action
                return ChatActionResult.Done
            }
            override suspend fun verify(session: Session, action: ChatAction) = ChatActionResult.Done
        }
        val state = makeState(actions)
        val session = codexSession(tier = "priority")

        rule.setContent {
            CompositionLocalProvider(LocalHubFeatures provides emptySet()) {
                PersonaTheme(style = AppStyle.Glass, darkTheme = false, reduceMotion = true) {
                    AgentModelPill(
                        session = session,
                        state = state,
                        creation = null,
                        online = true,
                        width = persona.core.designsystem.ModelPillWidth.Wide,
                        onSwitch = {},
                        onOverlay = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("model-pill").assertContentDescriptionContains("Fast", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Customize").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-panel-fast-switch").assertIsOn().performClick()
        rule.waitForIdle()
        assertEquals(listOf(ChatAction.Modes(serviceTier = "default")), sent)
    }
}
