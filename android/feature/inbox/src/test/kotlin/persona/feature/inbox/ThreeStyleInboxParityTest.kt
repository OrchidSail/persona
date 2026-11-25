package persona.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.InboxBulkAction
import persona.core.contracts.api.InboxDoneTarget
import persona.core.contracts.api.InboxEntry
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Tokens
import persona.core.designsystem.icons.PersonaIcons
import persona.core.domain.inbox.InboxInteraction
import persona.core.domain.inbox.InboxItem
import persona.core.inbox.*
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * Verifies S-16 three-style parity in Inbox: every style (Glass, Brief, Board) shares the inline source/kind
 * filter strip (`GlassInboxFilterStrip`) and the unified agent interaction row (`GlassInboxAgentRow` /
 * `projectInboxAgentRow`) with machine name, timestamp, request-kind badge, and >=48dp touch targets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h1200dp-xhdpi")
class ThreeStyleInboxParityTest {
    @get:Rule val rule = createComposeRule()

    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val opened = mutableListOf<NavKey>()
    private val navigator = object : Navigator {
        override fun navigate(key: NavKey) { opened += key }
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }

    @After fun tearDown() { owner.cancel() }

    private class FakeInbox(snapshotValue: InboxSnapshot) : InboxRepository {
        override val entries = MutableStateFlow(emptyList<InboxEntry>())
        override val snapshot = MutableStateFlow(snapshotValue)
        override val connectionState = MutableStateFlow(ConnectionState.Online)
        override val preferences = MutableStateFlow(InboxPreferences(expanded = listOf("action", "fyi")))
        override suspend fun savePreferences(value: InboxPreferences) { preferences.value = value }
        override val conversationInbox = MutableStateFlow<InboxConversations?>(null)
        override suspend fun item(itemId: String): InboxRecord? = snapshot.value.records.firstOrNull { it.item.id == itemId }
        override suspend fun markLatestRead(feedId: String): Boolean = true
        override suspend fun bulk(items: List<InboxDoneTarget>, action: InboxBulkAction): InboxBulkResult =
            InboxBulkResult(changed = items.map { it.id })
        override suspend fun doneHistory(manager: String?, cursor: InboxDoneCursor?) = InboxDonePage()
        override suspend fun refresh() = Unit
        override suspend fun refreshNow() = Unit
        override fun conversation(feedId: String, startPosition: Long?) = flowOf(LarkConversation())
        override suspend fun loadOlder(feedId: String) = Unit
        override suspend fun refreshConversation(feedId: String) = Unit
        override suspend fun setDone(itemId: String, done: Boolean) = Unit
        override suspend fun readDraft(target: ReplyTarget): ReplyDraft? = null
        override suspend fun saveDraft(target: ReplyTarget, draft: ReplyDraft) = Unit
        override suspend fun reply(target: ReplyTarget, request: ReplyRequest) = Unit
        override suspend fun downloadFile(file: LarkPart.File): String = error("unused")
    }

    @Test fun projectInboxAgentRowProducesSharedTitleAskContextAndTimestamp() {
        val interaction = InboxInteraction(
            id = "ix_perm",
            machineId = "studio",
            sessionId = "s1",
            createdAt = "2026-11-22T05:16:00Z",
            sessionTitle = "Deploy staging",
            summary = "Run deploy script",
            title = "Approval needed",
            kind = "permission",
            askLine = "Allow Bash deploy.sh?",
        )
        val model = projectInboxAgentRow(
            interaction = interaction,
            machineName = "Mac Studio",
            timeText = "5m",
            fallbackKindLabel = "Permission",
        )
        assertEquals("Deploy staging", model.heading)
        assertEquals("Allow Bash deploy.sh?", model.ask)
        assertEquals("Approval needed", model.subtitleHeadline)
        assertEquals("Mac Studio", model.machine)
        assertEquals("5m", model.timeText)
        assertEquals(PersonaIcons.waitsForYou, model.icon)
    }

    @Test fun everyStyleShowsInlineFilterStripAndUnifiedAgentInteractionRow() {
        val mailRecord = InboxRecord(
            InboxItem(
                id = "mail-1",
                account = "mail-acc",
                kind = "mail",
                title = "Release checklist",
                sender = "Alex",
                preview = "Please review before noon.",
                tier = "action",
                updatedAt = "2026-11-22T06:00:00Z",
                version = "v1",
            ),
            sourceLabel = "Work Mail",
            source = "mail",
        )
        val larkRecord = InboxRecord(
            InboxItem(
                id = "dm-1",
                account = "feishu",
                kind = "dm",
                title = "Build green",
                sender = "Sam",
                preview = "All checks passed.",
                tier = "action",
                updatedAt = "2026-11-22T06:05:00Z",
                version = "v2",
            ),
            sourceLabel = "Lark",
            source = "lark",
        )
        val interaction = InboxInteraction(
            id = "ix_plan",
            machineId = "mac",
            sessionId = "s1",
            createdAt = "2026-11-22T05:16:00Z",
            sessionTitle = "Photo sync HEIC",
            summary = "Plan awaiting approval",
            title = "Plan awaiting approval",
            kind = "plan",
            askLine = "Support HEIC",
        )
        val inbox = FakeInbox(
            InboxSnapshot(
                records = listOf(mailRecord, larkRecord),
                interactions = listOf(interaction),
                machines = mapOf("mac" to "Mac Studio"),
            ),
        )
        inbox.conversationInbox.value = InboxConversations(inbox, "synthetic-pairing", owner, ReplyComposers(inbox, owner))

        var style by mutableStateOf(AppStyle.Glass)
        rule.setContent {
            PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                Box(Modifier.background(PersonaTheme.palette.background)) {
                    OnAll { InboxScreen(inbox, navigator) }
                }
            }
        }
        rule.waitForIdle()

        val minTouchPx = with(rule.density) { Tokens.MinTouch.android.toPx() }
        val inFilterStrip = hasAnyAncestor(hasTestTag("inbox-filter-strip"))

        for (current in AppStyle.entries) {
            rule.runOnIdle { style = current }
            rule.waitForIdle()

            // Inline source/kind filter strip exists across all 3 styles with >=48dp touch height.
            rule.onNodeWithTag("inbox-filter-strip").assertIsDisplayed()
            val allPill = rule.onNode(inFilterStrip and hasText("All"))
            allPill.assertIsDisplayed()
            assertTrue(
                "$current filter pill touch height >= 48dp",
                allPill.fetchSemanticsNode().touchBoundsInRoot.height >= minTouchPx - 0.5f,
            )

            // Agent interaction row uses the shared rich layout (title, askLine, kind + machine, no Persona sender).
            rule.onNodeWithText("Photo sync HEIC").assertIsDisplayed()
            rule.onNodeWithText("Support HEIC").assertIsDisplayed()
            rule.onNodeWithText("Plan awaiting approval · Mac Studio").assertIsDisplayed()
            assertEquals("$current should not inject Persona sender", 0, rule.onAllNodesWithText("Persona").fetchSemanticsNodes().size)

            // Source filtering in the inline strip filters the list identically across all 3 styles.
            rule.onNode(inFilterStrip and hasText("Work Mail")).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Release checklist").assertIsDisplayed()
            assertEquals("$current source filter hides other source", 0, rule.onAllNodesWithText("Build green").fetchSemanticsNodes().size)

            // Clear all restores full list.
            rule.onNode(inFilterStrip and hasText("All")).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Build green").assertIsDisplayed()
            rule.onNodeWithText("Release checklist").assertIsDisplayed()
        }
    }
}
