package persona.feature.assistant

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantManagerChoice
import persona.core.contracts.api.AssistantRepository
import persona.core.contracts.api.AssistantSnapshot
import persona.core.contracts.api.DraftSnapshot
import persona.core.contracts.api.MainConversationSender
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.icons.PersonaIcons
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * Verifies S-13 (single source of truth for Assistant Home Needs You + terminal-waiting subtitle parity)
 * and S-17 (dated hub title parsing in active/archived lists and unified menu icons) across Glass, Brief, and Board.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h1100dp-xhdpi")
class ThreeStyleAssistantParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() = AssistantManagerChoice.choose("hub", null, "assistant")
    @After fun cleanup() = AssistantManagerChoice.reset()

    private val navigator = object : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }

    private class BlankHub : AssistantRepository, MessageSender, MainConversationSender {
        override val assistant = MutableStateFlow(AssistantSnapshot())
        override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
        override suspend fun refresh() = Unit
        override suspend fun sendToMain(text: String, clientMessageId: String, draft: DraftSnapshot?) = Unit
        override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
        suspend fun start() = SessionKey("mac", "new")
    }

    @Test fun needsYouFromLinesCapsToShownAndPreservesTotalAndOrder() {
        val lines = listOf(
            NeedsYouLine.Agent(SessionKey("m", "s1"), "r1", "screen", null, who = "Terminal build", what = "", row = "agent:m/s1"),
            NeedsYouLine.Agent(SessionKey("m", "s2"), "r2", "plan", null, who = "Release plan", what = "Plan ready", row = "agent:m/s2"),
            NeedsYouLine.Inbox("i1", lark = false, itemKind = "mail", who = "Alex", what = "Sign off release"),
            NeedsYouLine.Inbox("i2", lark = true, itemKind = "dm", who = "Sam", what = "Check staging"),
        )
        val result = needsYouFromLines(lines)
        assertEquals(3, result.lines.size)
        assertEquals(1, result.more)
        assertEquals(4, result.total)
        assertEquals(listOf("agent:m/s1", "agent:m/s2", "inbox:i1"), result.lines.map { it.key })
        assertTrue(needsYouFromLines(emptyList()).isEmpty)
    }

    @Test fun assistantHomeDerivesNeedsYouFromSingleShellSourceAndShowsTerminalSubtitleAcrossStyles() {
        val hub = BlankHub()
        val lines = listOf(
            NeedsYouLine.Agent(SessionKey("m", "term"), "r-term", "screen", null, who = "Terminal build", what = "", row = "agent:m/term", terminal = true),
            NeedsYouLine.Inbox("i1", lark = false, itemKind = "mail", who = "Alex", what = "Sign off release"),
        )
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                Box(Modifier.width(400.dp).height(1100.dp).background(PersonaTheme.palette.background)) {
                    // Omit explicit `needsYou` so Brief/Board exercise the shared `needsYouFromLines(shellNeedsYou)` path.
                    AssistantScreen(
                        navigator, hub, hub, hub::start,
                        composerService = null,
                        mainConversationSender = hub,
                        shellNeedsYou = lines,
                        onOpenNeed = {},
                        onOpenInbox = {},
                        machines = emptyList(),
                        phoneToolsAvailable = true,
                    )
                }
            }
        }
        rule.waitForIdle()

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            rule.onNodeWithText("Terminal build", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Terminal needs you", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Alex", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Sign off release", substring = true).assertIsDisplayed()
        }
    }

    @Test fun archivedConversationsParseHubDatedTitleAcrossAllStyles() {
        val fullTitle = "1101 | Fix | Quarterly roadmap"
        val archived = listOf(
            Session(
                machineId = "mac",
                sessionId = "s-dated",
                title = fullTitle,
                assistant = true,
                status = "idle",
                updatedAt = "2026-11-22T09:00:00Z",
            ),
        )
        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                Column(Modifier.width(400.dp).height(600.dp)) {
                    ArchivedConversationsSheet(
                        sessions = archived,
                        onDismiss = {},
                        onOpen = {},
                        onUnarchive = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        val parsed = persona.core.domain.ConversationTitle.parse(fullTitle)
        requireNotNull(parsed) { "Expected ConversationTitle.parse to split dated hub title" }
        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()
            val spans = rule.onNodeWithText(fullTitle, useUnmergedTree = true).fetchSemanticsNode().config
                .let { it[androidx.compose.ui.semantics.SemanticsProperties.Text] }
                .first().spanStyles.map { it.start to it.end }
            assertEquals("$current splits hub title head from topic", listOf(0 to parsed.head.length), spans)
        }
    }

    @Test fun conversationMenuUsesUnifiedIconsAcrossStyles() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val actions = conversationActions(
            resources = res,
            leader = false,
            live = true,
            onLeader = {},
            onCopy = {},
            onEnd = {},
            onArchive = {},
            onRename = {},
            glass = true,
        )
        val copy = actions.first { it.label == res.getString(R.string.assistant_copy_session_id) }
        val end = actions.first { it.label == res.getString(R.string.assistant_end_menu) }
        assertEquals(PersonaIcons.id, copy.icon)
        assertEquals(PersonaIcons.end, end.icon)
    }
}
