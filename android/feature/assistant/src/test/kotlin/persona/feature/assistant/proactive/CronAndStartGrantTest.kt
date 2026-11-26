package persona.feature.assistant.proactive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import persona.core.contracts.Machine
import persona.core.contracts.SessionKey
import persona.core.contracts.api.CronPreview
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask
import persona.core.contracts.api.TaskStartGrant
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import persona.core.hub.HubError
import persona.core.hub.HubFailure

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h900dp")
class CronAndStartGrantTest {
    @get:Rule val rule = createComposeRule()

    private class FakeCronApi(var table: List<ScheduledTask>) : ProactiveApi {
        val previews = mutableListOf<String>()
        val revokes = mutableListOf<Pair<String, String?>>()
        val saved = mutableListOf<ScheduledTask>()

        override suspend fun tasks(): List<ScheduledTask> = table
        override suspend fun saveTask(task: ScheduledTask): ScheduledTask {
            saved += task
            val stored = task.copy(id = task.id.ifBlank { "task_new" })
            table = if (table.any { it.id == stored.id }) table.map { if (it.id == stored.id) stored else it } else table + stored
            return stored
        }
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = SessionKey("server", "run-$id")
        override suspend fun freshTask(id: String) = table.first { it.id == id }
        override suspend fun runs(): List<Run> = emptyList()
        override suspend fun assistantMachine(): String = "server"
        override suspend fun assistantTimezone(): String = "Asia/Shanghai"
        override suspend fun previewCron(expression: String): CronPreview {
            previews += expression
            if (expression == "bad") throw HubFailure(HubError.Protocol, "cron must have 5 fields", status = 400)
            return CronPreview(
                cron = expression,
                text = "Runs on $expression",
                timezone = "Asia/Shanghai",
                nextRuns = listOf("2026-11-03T01:00:00Z", "2026-11-04T01:00:00Z"),
            )
        }
        override suspend fun revokeTaskStartGrant(id: String, kind: String?): ScheduledTask {
            revokes += id to kind
            val task = table.first { it.id == id }
            val next = task.startGrant?.kinds.orEmpty().filterNot { it == kind }
            val updated = task.copy(startGrant = next.takeIf { it.isNotEmpty() }?.let(::TaskStartGrant))
            table = table.map { if (it.id == id) updated else it }
            return updated
        }
    }

    @Test fun cronSegmentGatingAndPreviewValidationAcrossAllStyles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val api = FakeCronApi(emptyList())
            val say = ProactiveText(ApplicationProvider.getApplicationContext())
            val store = ProactiveStore(api, scope, say)
            runBlocking { store.refresh() }

            var style by mutableStateOf(AppStyle.Glass)
            var features by mutableStateOf<Set<String>>(emptySet())
            val now = Instant.parse("2026-11-02T02:00:00Z")
            val zone = ZoneId.of("Asia/Shanghai")

            rule.setContent {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                    val state by store.state.collectAsState()
                    CompositionLocalProvider(LocalHubFeatures provides features) {
                        TaskEditorPage(
                            id = null,
                            template = TaskDraft(title = "Build patrol", prompt = "Check build"),
                            state = state,
                            store = store,
                            now = now,
                            zone = zone,
                            onClose = {},
                            onOpenConversation = {},
                            onAllRuns = {},
                            onRunNow = {},
                        )
                    }
                }
            }

            // Without task_cron feature, Cron option is hidden.
            assertTrue(rule.onAllNodesWithText("Cron").fetchSemanticsNodes().isEmpty())

            // Enable task_cron and verify across Glass, Brief, Board.
            rule.runOnIdle { features = setOf(ScheduledTask.CRON_FEATURE) }
            for (nextStyle in AppStyle.entries) {
                rule.runOnIdle { style = nextStyle }
                rule.onNodeWithText("Cron").assertIsDisplayed()
            }

            // Switch to Cron and verify example chip + debounced preview + invalid error blocking Save.
            fun awaitDebounce(expectedExpr: String, expectedTag: String) {
                rule.mainClock.advanceTimeBy(400)
                ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
                rule.waitUntil(3_000) {
                    ShadowLooper.idleMainLooper()
                    api.previews.lastOrNull() == expectedExpr &&
                        rule.onAllNodesWithTag(expectedTag).fetchSemanticsNodes().isNotEmpty()
                }
                rule.waitForIdle()
            }

            rule.onNodeWithText("Cron").performClick()
            awaitDebounce("0 9 * * 1-5", "cron-preview-text")
            rule.onNodeWithTag("cron-preview-text").assertTextContains("Runs on 0 9 * * 1-5")

            rule.onNodeWithTag("cron-example-0 10 1 * *").performClick()
            awaitDebounce("0 10 1 * *", "cron-preview-text")
            rule.onNodeWithTag("cron-preview-text").assertTextContains("Runs on 0 10 1 * *")
            rule.onNodeWithText("Add").assertIsEnabled()

            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("cron-expression-field")))
                .performTextReplacement("bad")
            awaitDebounce("bad", "cron-preview-error")
            rule.onNodeWithTag("cron-preview-error").assertTextContains("cron must have 5 fields")
            rule.onNodeWithText("Add").assertIsNotEnabled()
        } finally {
            scope.cancel()
        }
    }

    @Test fun startGrantShowsResolvedMachineAndRevokePreservesUnsavedDraftAcrossStyles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val existing = ScheduledTask(
                id = "t1",
                title = "Nightly check",
                prompt = "Inspect build",
                repeats = ScheduledTask.DAILY,
                time = "09:00",
                weekdays = listOf(1, 2, 3, 4, 5),
                enabled = true,
                startGrant = TaskStartGrant(listOf("mac|/repo/app|edit", "box|/docs|readonly")),
            )
            val api = FakeCronApi(listOf(existing))
            val say = ProactiveText(ApplicationProvider.getApplicationContext())
            val store = ProactiveStore(api, scope, say)
            runBlocking { store.refresh() }

            var style by mutableStateOf(AppStyle.Glass)
            val machines = listOf(Machine("mac~g0a1b2c3d", name = "Work Mac"))
            val now = Instant.parse("2026-11-02T02:00:00Z")
            val zone = ZoneId.of("Asia/Shanghai")

            rule.setContent {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                    val state by store.state.collectAsState()
                    TaskEditorPage(
                        id = "t1",
                        template = null,
                        state = state,
                        store = store,
                        now = now,
                        zone = zone,
                        onClose = {},
                        onOpenConversation = {},
                        onAllRuns = {},
                        onRunNow = {},
                        machines = machines,
                    )
                }
            }

            // Edit the title without saving, then check start-grant section across styles and revoke one kind.
            rule.onNode(hasSetTextAction() and hasText("Nightly check")).performTextReplacement("Edited title")
            for (nextStyle in AppStyle.entries) {
                rule.runOnIdle { style = nextStyle }
                rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Starts without asking"))
                rule.onNodeWithText("Starts without asking").assertIsDisplayed()
                rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Work Mac · /repo/app"))
                rule.onNodeWithText("Work Mac · /repo/app").assertIsDisplayed()
            }

            rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTestTag("start-grant-remove-mac|/repo/app|edit"))
            rule.onNodeWithTag("start-grant-remove-mac|/repo/app|edit").performClick()
            rule.waitForIdle()
            assertEquals(listOf("t1" to "mac|/repo/app|edit"), api.revokes)
            assertTrue(rule.onAllNodesWithText("Work Mac · /repo/app").fetchSemanticsNodes().isEmpty())
            rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("box · /docs"))
            rule.onNodeWithText("box · /docs").assertIsDisplayed()
            // Unsaved title edit survived the grant revocation!
            rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Edited title"))
            rule.onNode(hasSetTextAction() and hasText("Edited title")).assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
