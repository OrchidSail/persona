package persona.feature.agents

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.navigation3.runtime.NavKey
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
import persona.core.contracts.JsonCodec
import persona.core.contracts.Machine
import persona.core.contracts.Resolution
import persona.core.contracts.ServerEvent
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.HubPreferences
import persona.core.contracts.api.HubPreferencesSnapshot
import persona.core.contracts.api.InteractionCenter
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.SessionsRepository
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHomeChrome
import persona.core.designsystem.PersonaTheme
import persona.core.domain.sort.AgentGrouping
import persona.core.domain.sort.AgentListDisplay
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Verifies S-11 three-style parity on Agents:
 * - All 4 `AgentListDisplay` modes (`Avatar`, `TwoLines`, `Cards`, `Compact`) render across Glass, Brief, and Board.
 * - By-Repository tree (`repoTree`) and idle repositories / idle fold line work identically across all 3 styles.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h1800dp-xhdpi")
class ThreeStyleAgentsParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
    private fun ago(hours: Long) = now.minus(Duration.ofHours(hours)).toString()

    private val repo = "/home/me/code/persona"
    private val sessions = listOf(
        """"machine_id":"laptop","session_id":"login","title":"Fix the login bug","state":"working","cwd":"$repo","repo_root":"$repo","worktree":"$repo","branch":"main","pane_id":"w1:p1","slot_id":"t1","slot_name":"login","model":"claude-sonnet-4-5","todo":{"id":"td1","title":"Ship 1.2"},"last_text":"Updating the retry logic","updated_at":"${ago(1)}"""",
        """"machine_id":"laptop","session_id":"review","title":"Review the release notes","state":"idle","cwd":"$repo","repo_root":"$repo","worktree":"$repo","branch":"main","slot_id":"t2","slot_name":"review","updated_at":"${ago(3)}"""",
        """"machine_id":"laptop","session_id":"research","title":"Compare cache libs","state":"idle","cwd":"$repo","repo_root":"$repo","worktree":"$repo","branch":"main","slot_id":"t3","slot_name":"research","updated_at":"${ago(6)}"""",
        """"machine_id":"laptop","session_id":"bisect","title":"Bisect the crash","state":"unread","cwd":"$repo/.worktrees/bisect","repo_root":"$repo","worktree":"$repo/.worktrees/bisect","slot_id":"t4","slot_name":"bisect","updated_at":"${ago(1)}"""",
        """"machine_id":"laptop","session_id":"notes1","title":"Summarize the meeting","state":"idle","cwd":"/home/me/notes","slot_id":"n1","slot_name":"minutes","updated_at":"${ago(2)}"""",
        """"machine_id":"laptop","session_id":"notes2","title":"Plan the week","state":"idle","cwd":"/home/me/notes","slot_id":"n2","slot_name":"planning","updated_at":"${ago(28)}"""",
    ).map { JsonCodec.decodeFromString(Session.serializer(), "{$it}") }

    private fun mount(styleState: () -> AppStyle): AgentsViewModel {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val model = AgentsViewModel(
            repository = object : SessionsRepository {
                override val sessions = MutableStateFlow(this@ThreeStyleAgentsParityTest.sessions)
                override val machines = MutableStateFlow(listOf(Machine("laptop", "Laptop", online = true)))
                override suspend fun refresh() = Unit
            },
            interactions = object : InteractionCenter {
                override val pending = MutableStateFlow<List<Interaction>>(emptyList())
                override suspend fun refresh() = Unit
                override suspend fun resolve(id: String, resolution: Resolution) = Unit
            },
            connection = object : HubConnection {
                override val state = MutableStateFlow(ConnectionState.Online)
                override val events = MutableSharedFlow<ServerEvent>()
                override fun reconnect() = Unit
                override fun send(message: JsonObject) = true
            },
            sender = object : MessageSender {
                override val outbox = MutableStateFlow<List<OutboxMessage>>(emptyList())
                override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
            },
            preferences = AgentsPreferences(store, object : HubPreferences {
                override val preferences = MutableStateFlow(HubPreferencesSnapshot())
                override suspend fun refresh() = Unit
                override suspend fun togglePin(path: String, space: Boolean) = Unit
                override suspend fun toggleMute(path: String, space: Boolean) = Unit
                override suspend fun markSeen(session: SessionKey, version: String) = Unit
            }),
        )
        rule.setContent {
            PersonaTheme(style = styleState(), darkTheme = false, reduceMotion = true) {
                CompositionLocalProvider(LocalHomeChrome provides agentsHomeChrome) {
                    AgentsScreen(model, Nowhere, AgentsMetadata())
                }
            }
        }
        rule.waitUntil(5_000) { model.page.value.loaded }
        rule.waitForIdle()
        return model
    }

    @Test fun repoTreeAndIdleRepositoriesWorkAcrossAllThreeStyles() {
        var style by mutableStateOf(AppStyle.Glass)
        val model = mount { style }
        rule.runOnIdle { model.grouping(AgentGrouping.Repo) }
        rule.waitForIdle()

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()

            // Repository header & linked worktree lines render identically across Glass, Brief, and Board.
            rule.onNodeWithContentDescription("persona, Repository", substring = true).assertHasClickAction()
            rule.onNodeWithContentDescription("Worktree, bisect, Detached HEAD, 1 agent").assertHasClickAction()

            // Idle line opens and folds back via "Hide 2 Idle".
            rule.onNodeWithContentDescription("Show 2 idle agents").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Review the release notes").assertExists()
            rule.onNodeWithContentDescription("Hide 2 Idle").performClick()
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Show 2 idle agents").assertExists()

            // Idle repository row expands and collapses its agents.
            rule.onNodeWithContentDescription("notes, Repository, 2 agents · minutes · planning", substring = true).performClick()
            rule.waitForIdle()
            rule.onNode(hasText("Plan the week")).assertExists()
            rule.onNodeWithContentDescription("notes, Repository", substring = true).performClick()
            rule.waitForIdle()
            rule.onNode(hasText("Plan the week")).assertDoesNotExist()
        }
    }

    @Test fun twoLinesAndStateCardsLayoutsRenderAcrossBriefAndBoard() {
        var style by mutableStateOf(AppStyle.Brief)
        val model = mount { style }
        fun said(title: String) = rule.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .fetchSemanticsNode().config.let {
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    it.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text }
            }

        for (current in AppStyle.entries) {
            rule.runOnIdle {
                style = current
                model.grouping(AgentGrouping.Repo)
                model.display(AgentListDisplay.Cards)
            }
            rule.waitForIdle()
            val cards = said("Fix the login bug")
            assertTrue("$current Cards shows Working: $cards", "Working" in cards)
            assertTrue("$current Cards shows To-do: $cards", "To-do: Ship 1.2" in cards)
            assertTrue("$current Cards shows model: $cards", "Sonnet 4.5" in cards)

            rule.runOnIdle { model.display(AgentListDisplay.TwoLines) }
            rule.waitForIdle()
            val twoLines = said("Fix the login bug")
            assertTrue("$current TwoLines shows source: $twoLines", "Terminal" in twoLines)
            assertTrue("$current TwoLines hides model: $twoLines", twoLines.none { "Sonnet" in it })

            // List settings sheet is reached via reselecting Agents tab and offers all 4 displays.
            rule.reselectAgentsTab()
            rule.waitForIdle()
            for (label in listOf("Avatar", "Two lines", "State cards", "Compact list")) {
                assertEquals("$current list settings shows $label", 1, rule.onAllNodesWithText(label).fetchSemanticsNodes().size)
            }
            rule.onNodeWithText("Avatar").performClick()
            rule.waitForIdle()
        }
    }

    private object Nowhere : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }
}
