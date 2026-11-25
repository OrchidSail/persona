package persona.feature.assistant.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Machine
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantConfig
import persona.core.contracts.api.AssistantConfigUpdate
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantNotes
import persona.core.contracts.api.DefaultRepositoryProviders
import persona.core.contracts.api.DirectionLeader
import persona.core.contracts.api.DirectionNotesConflictException
import persona.core.contracts.api.DirectionNotesResult
import persona.core.contracts.api.GovernanceUnsupportedException
import persona.core.contracts.api.HubManager
import persona.core.contracts.api.ManagerRoster
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.RepositoryBinding
import persona.core.contracts.api.RepositoryCatalog
import persona.core.contracts.api.RepositoryIdentity
import persona.core.contracts.api.RepositoryInput
import persona.core.contracts.api.RepositoryPreview
import persona.core.contracts.api.RepositoryProvider
import persona.core.contracts.api.RepositoryRemoval
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask
import persona.core.contracts.api.TaskTrustKind
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalManagerRoster
import persona.core.designsystem.PersonaTheme

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h900dp")
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantGovernanceTest {
    @get:Rule val rule = createComposeRule()

    private class FakeGovernanceManagement : AssistantManagement {
        var config = AssistantConfig(
            machineId = "mac",
            launcher = "claude",
            cwd = "/home/me/.persona/assistant",
            timezone = "Asia/Shanghai",
            manager = "assistant",
            readonlyMode = "sandbox",
            startRequestedTasks = true,
            deliveryGate = "merged",
            taskWorkspacePreference = "prefer-existing",
            autoSupervisionMessages = true,
            taskLessons = false,
            agentAutoNaming = true,
            agentAutoNamingMax = 3,
            agentAutoNamingIntervalHours = 0,
            cacheKeepalive = true,
            cacheKeepaliveMinutes = 4,
            cacheKeepaliveMax = 6,
            foldRoutineRelayReplies = true,
            agentTasks = true,
            inboxSignals = true,
            inboxTodoForward = false,
            idleCloseHours = 24,
            archiveClosedDays = 7,
            claudeInteractive = true,
        )
        val updates = mutableListOf<AssistantConfigUpdate>()
        var echoOverride: ((AssistantConfigUpdate, AssistantConfig) -> AssistantConfig)? = null
        var trustList = mutableListOf(
            TaskTrustKind(key = "mac|/repo/app|edit", machine = "mac", cwd = "/repo/app", readonly = false),
            TaskTrustKind(key = "box|/docs|readonly", machine = "box", cwd = "/docs", readonly = true, ambiguous = true),
        )
        var trustUnsupported = false
        val revokedTrust = mutableListOf<String>()
        var leaderList = mutableListOf(
            DirectionLeader(id = "dir-1", name = "Android", project = "mobile", ended = false, notes = "Keep all 3 styles identical", notesAt = "2026-11-01T10:00:00Z"),
            DirectionLeader(id = "dir-ended", name = "Archived", ended = true, notes = "old"),
        )
        var conflictOnce: DirectionNotesConflictException? = null
        val savedDirectionNotes = mutableListOf<Triple<String, String, String?>>()
        var repos = mutableListOf(
            RepositoryBinding(
                id = "repo_1",
                provider = "github",
                instanceUrl = "https://github.com",
                path = "acme/persona",
                canonicalUrl = "https://github.com/acme/persona",
                name = "Persona",
                aliases = listOf("main"),
                purpose = "Core repo",
                taskWorkflow = "isolated",
            ),
        )
        var removedStore = mutableMapOf<String, RepositoryBinding>()
        var catalogRev = 5L

        override suspend fun warm() = Unit
        override suspend fun config(): AssistantConfig = config
        override suspend fun updateConfig(update: AssistantConfigUpdate): AssistantConfig {
            updates += update
            val next = config.copy(
                readonlyMode = update.readonlyMode ?: config.readonlyMode,
                startRequestedTasks = update.startRequestedTasks ?: config.startRequestedTasks,
                deliveryGate = update.deliveryGate ?: config.deliveryGate,
                taskWorkspacePreference = update.taskWorkspacePreference ?: config.taskWorkspacePreference,
                autoSupervisionMessages = update.autoSupervisionMessages ?: config.autoSupervisionMessages,
                taskLessons = update.taskLessons ?: config.taskLessons,
                agentAutoNaming = update.agentAutoNaming ?: config.agentAutoNaming,
                agentAutoNamingMax = update.agentAutoNamingMax ?: config.agentAutoNamingMax,
                agentAutoNamingIntervalHours = update.agentAutoNamingIntervalHours ?: config.agentAutoNamingIntervalHours,
                cacheKeepalive = update.cacheKeepalive ?: config.cacheKeepalive,
                cacheKeepaliveMinutes = update.cacheKeepaliveMinutes ?: config.cacheKeepaliveMinutes,
                cacheKeepaliveMax = update.cacheKeepaliveMax ?: config.cacheKeepaliveMax,
                foldRoutineRelayReplies = update.foldRoutineRelayReplies ?: config.foldRoutineRelayReplies,
                agentTasks = update.agentTasks ?: config.agentTasks,
                inboxSignals = update.inboxSignals ?: config.inboxSignals,
                inboxTodoForward = update.inboxTodoForward ?: config.inboxTodoForward,
                idleCloseHours = update.idleCloseHours ?: config.idleCloseHours,
                archiveClosedDays = update.archiveClosedDays ?: config.archiveClosedDays,
                claudeInteractive = update.claudeInteractive ?: config.claudeInteractive,
            )
            config = echoOverride?.invoke(update, next) ?: next
            return config
        }
        override suspend fun endConversation(session: SessionKey) = Unit
        override suspend fun tasks(): List<ScheduledTask> = emptyList()
        override suspend fun saveTask(task: ScheduledTask) = task
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = SessionKey("mac", id)
        override suspend fun freshTask(id: String) = ScheduledTask(id = id)
        override suspend fun runs(): List<Run> = emptyList()
        override suspend fun notes() = AssistantNotes()
        override suspend fun setNotes(text: String) = Unit
        override suspend fun memory(): List<MemoryFile> = emptyList()
        override suspend fun saveMemory(name: String, content: String) = Unit
        override suspend fun deleteMemory(name: String) = Unit

        override suspend fun taskTrust(): List<TaskTrustKind> {
            if (trustUnsupported) throw GovernanceUnsupportedException()
            return trustList.toList()
        }
        override suspend fun revokeTaskTrust(key: String) {
            revokedTrust += key
            trustList.removeAll { it.key == key }
        }
        override suspend fun leaders(): List<DirectionLeader> = leaderList.toList()
        override suspend fun updateDirectionNotes(id: String, notes: String, notesAt: String?): DirectionNotesResult {
            conflictOnce?.let { err ->
                conflictOnce = null
                throw err
            }
            savedDirectionNotes += Triple(id, notes, notesAt)
            val newAt = "2026-11-02T12:00:00Z"
            leaderList = leaderList.map { if (it.id == id) it.copy(notes = notes, notesAt = newAt) else it }.toMutableList()
            return DirectionNotesResult(notes = notes, notesAt = newAt)
        }
        override suspend fun repositories(): RepositoryCatalog =
            RepositoryCatalog(manager = "assistant", catalogRevision = catalogRev, repositories = repos.toList())
        override suspend fun repositoryProviders(): List<RepositoryProvider> = DefaultRepositoryProviders
        override suspend fun previewRepository(url: String, provider: String): RepositoryPreview {
            val clean = url.trim().removeSuffix("/")
            val path = clean.substringAfter("github.com/", "acme/new")
            return RepositoryPreview(
                manager = "assistant",
                identity = RepositoryIdentity(provider = provider.ifBlank { "github" }, instanceUrl = "https://github.com", path = path, canonicalUrl = clean),
            )
        }
        override suspend fun createRepository(input: RepositoryInput): RepositoryBinding {
            catalogRev++
            val created = RepositoryBinding(
                id = "repo_${repos.size + 1}",
                provider = input.provider.ifBlank { "github" },
                instanceUrl = "https://github.com",
                path = input.url.substringAfter("github.com/", "acme/new"),
                canonicalUrl = input.url.trim(),
                name = input.name,
                aliases = input.aliases,
                purpose = input.purpose,
                taskWorkflow = input.taskWorkflow,
            )
            repos += created
            return created
        }
        override suspend fun updateRepository(id: String, input: RepositoryInput): RepositoryBinding {
            catalogRev++
            val updated = repos.first { it.id == id }.copy(
                canonicalUrl = input.url.trim(),
                name = input.name,
                aliases = input.aliases,
                purpose = input.purpose,
                taskWorkflow = input.taskWorkflow,
            )
            repos = repos.map { if (it.id == id) updated else it }.toMutableList()
            return updated
        }
        override suspend fun deleteRepository(id: String): RepositoryRemoval {
            catalogRev++
            val target = repos.first { it.id == id }
            repos.removeAll { it.id == id }
            removedStore[id] = target
            return RepositoryRemoval(manager = "assistant", receipt = "rcpt-$id")
        }
        override suspend fun restoreRepository(id: String, receipt: String): RepositoryBinding {
            catalogRev++
            val restored = removedStore.remove(id) ?: error("no receipt")
            repos += restored
            return restored
        }
    }

    private class FakeZone : TimezoneChoice {
        override val phoneZone: String = "Asia/Shanghai"
        override var followPhone: Boolean = true
    }

    @Test fun governanceStateVerifiesEchoEnforcesBoundsAndHandlesConflictsAndUndo() = runTest {
        val hub = FakeGovernanceManagement()
        val state = AssistantSettingsState(hub.settingsApi(), FakeZone())
        state.load()
        assertEquals("sandbox", state.state.value.config?.readonlyMode)

        // Verified-echo guard rolls back readonly_mode if hub returns mismatched value.
        hub.echoOverride = { _, cfg -> cfg.copy(readonlyMode = "sandbox") }
        assertFalse(state.setReadonlyMode("prompt"))
        assertEquals("sandbox", state.state.value.config?.readonlyMode)
        hub.echoOverride = null

        assertTrue(state.setReadonlyMode("prompt"))
        assertEquals("prompt", state.state.value.config?.readonlyMode)

        // Legacy done_needs_delivery fallback maps to effectiveDeliveryGate.
        val legacy = AssistantConfig(doneNeedsDelivery = true)
        assertEquals("live", legacy.effectiveDeliveryGate)
        assertTrue(state.setDeliveryGate("live"))
        assertEquals("live", state.state.value.config?.deliveryGate)

        // Numeric bounds reject out-of-range edits without sending requests.
        val beforeCount = hub.updates.size
        assertFalse(state.setAgentAutoNaming(max = 0))
        assertFalse(state.setAgentAutoNaming(intervalHours = 9000))
        assertFalse(state.setCacheKeepalive(minutes = 0))
        assertFalse(state.setCacheKeepalive(minutes = 56))
        assertFalse(state.setCacheKeepalive(max = 145))
        assertFalse(state.setIdleCloseHours(-1))
        assertFalse(state.setArchiveClosedDays(4000))
        assertEquals(beforeCount, hub.updates.size)

        assertTrue(state.setAgentAutoNaming(max = 5, intervalHours = 24))
        assertTrue(state.setCacheKeepalive(minutes = 15, max = 12))
        assertTrue(state.setIdleCloseHours(0))
        assertTrue(state.setArchiveClosedDays(14))
        assertEquals(5, state.state.value.config?.agentAutoNamingMax)
        assertEquals(15, state.state.value.config?.cacheKeepaliveMinutes)

        // Task trust unsupported hides cleanly; supported loads & revokes.
        hub.trustUnsupported = true
        state.loadTaskTrust()
        assertFalse(state.state.value.taskTrust.supported)
        hub.trustUnsupported = false
        state.loadTaskTrust()
        assertTrue(state.state.value.taskTrust.supported)
        assertEquals(2, state.state.value.taskTrust.kinds?.size)
        assertTrue(state.revokeTaskTrust(state.state.value.taskTrust.kinds!!.first()))
        assertEquals(listOf("mac|/repo/app|edit"), hub.revokedTrust)
        assertEquals(1, state.state.value.taskTrust.kinds?.size)

        // Direction notes filters ended leaders and handles 409 conflict + force overwrite.
        state.loadLeaders()
        assertEquals(listOf("dir-1"), state.state.value.directionNotes.leaders?.map { it.id })
        hub.conflictOnce = DirectionNotesConflictException("changed", "Server latest", "2026-11-01T15:00:00Z")
        assertFalse(state.saveDirectionNotes("dir-1", "My draft", "2026-11-01T10:00:00Z"))
        assertEquals("Server latest", state.state.value.directionNotes.conflict?.currentNotes)
        assertTrue(state.saveDirectionNotes("dir-1", "My draft", "2026-11-01T10:00:00Z", forceOverwrite = true))
        assertNull(state.state.value.directionNotes.conflict)
        assertEquals("2026-11-01T15:00:00Z", hub.savedDirectionNotes.last().third)

        // Repositories CRUD + undo restore + metadata validation.
        state.loadRepositories()
        assertTrue(state.state.value.repositories.supported)
        assertEquals(1, state.state.value.repositories.catalog?.repositories?.size)
        assertNotNull(validateRepositoryInput(RepositoryInput(url = "")))
        assertNotNull(validateRepositoryInput(RepositoryInput(url = "https://github.com/a/b", aliases = (1..11).map { "a$it" })))
        assertEquals(listOf("web", "api"), parseRepositoryAliases("web, api, WEB, "))

        val created = state.saveRepository(null, RepositoryInput(url = "https://github.com/acme/web", name = "Web", aliases = listOf("ui"), taskWorkflow = "shared_branch"))
        assertNotNull(created)
        assertTrue(state.deleteRepository(created!!))
        assertEquals(created.id, state.state.value.repositories.undo?.id)
        assertTrue(state.undoDeleteRepository())
        assertNull(state.state.value.repositories.undo)
        assertTrue(state.state.value.repositories.catalog!!.repositories.any { it.id == created.id })
    }

    @Test fun governanceControlsWorkConsistentlyAcrossGlassBriefAndBoard() {
        val hub = FakeGovernanceManagement()
        val machines = listOf(Machine("mac~g0a1b2c3d", name = "Work Mac", online = true))
        val roster = ManagerRoster(listOf(HubManager("assistant", "Persona", online = true, isDefault = true)), "assistant")
        var style by mutableStateOf(AppStyle.Glass)

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                CompositionLocalProvider(LocalManagerRoster provides roster) {
                    AssistantSettingsScreen(management = hub, machines = machines, onBack = {})
                }
            }
        }
        rule.waitForIdle()

        // Verify governance entry rows and high-frequency switches appear identically across Glass, Brief, Board.
        for (nextStyle in AppStyle.entries) {
            rule.runOnIdle { style = nextStyle }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-task-trust"))
            rule.onNodeWithTag("governance-entry-task-trust").assertIsDisplayed()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-direction-notes"))
            rule.onNodeWithTag("governance-entry-direction-notes").assertIsDisplayed()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-repositories"))
            rule.onNodeWithTag("governance-entry-repositories").assertIsDisplayed()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-switch-start-requested"))
            rule.onNodeWithTag("governance-switch-start-requested").assertIsDisplayed()
        }

        // Toggle Task lessons and switch Delivery gate to Live.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-switch-task-lessons"))
        rule.onNodeWithTag("governance-switch-task-lessons").performClick()
        rule.waitForIdle()
        assertEquals(true, hub.config.taskLessons)

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-delivery-gate"))
        rule.onNodeWithText("Live").performClick()
        rule.waitForIdle()
        assertEquals("live", hub.config.deliveryGate)

        // Open Task Trust sub-page, verify resolved machine name, confirm revoke.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-task-trust"))
        rule.onNodeWithTag("governance-entry-task-trust").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Work Mac · /repo/app").assertIsDisplayed()
        rule.onNodeWithTag("task-trust-revoke-mac|/repo/app|edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Revoke remembered approval?").assertIsDisplayed()
        rule.onAllNodesWithText("Revoke").onLast().performClick()
        rule.waitForIdle()
        assertEquals(listOf("mac|/repo/app|edit"), hub.revokedTrust)
    }

    @Test fun repositoriesAndDirectionNotesSubPagesSupportPreviewUndoAndConflictAcrossStyles() {
        val hub = FakeGovernanceManagement()
        val roster = ManagerRoster(listOf(HubManager("assistant", "Persona", online = true, isDefault = true)), "assistant")
        var style by mutableStateOf(AppStyle.Brief)

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                CompositionLocalProvider(LocalManagerRoster provides roster) {
                    AssistantSettingsScreen(management = hub, machines = emptyList(), onBack = {})
                }
            }
        }
        rule.waitForIdle()

        // Navigate into Repositories, edit existing, delete & undo restore across Board style too.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-repositories"))
        rule.onNodeWithTag("governance-entry-repositories").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("repository-row-repo_1").assertIsDisplayed()
        rule.runOnIdle { style = AppStyle.Board }
        rule.onNodeWithTag("repositories-add-button").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("repository-editor-save").assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("repository-editor-url")))
            .performTextInput("https://github.com/acme/docs")
        rule.onNodeWithTag("repository-editor-preview").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("repository-editor-preview-ok").assertIsDisplayed()
        rule.onNodeWithTag("repository-editor-save").assertIsEnabled().performClick()
        rule.waitForIdle()

        // Open newly created repository, delete it, verify Undo restores it.
        rule.onNodeWithTag("repository-row-repo_2").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("repository-editor-delete"))
        rule.onNodeWithTag("repository-editor-delete").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Remove repository").onLast().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("docs").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("repositories-undo-notice").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("repository-row-repo_2").assertIsDisplayed()
    }

    @Test fun directionNotesSubPageHandlesConflictAndOverwriteInUi() {
        val hub = FakeGovernanceManagement()
        val roster = ManagerRoster(listOf(HubManager("assistant", "Persona", online = true, isDefault = true)), "assistant")
        var style by mutableStateOf(AppStyle.Glass)

        rule.setContent {
            PersonaTheme(darkTheme = false, reduceMotion = true, style = style) {
                CompositionLocalProvider(LocalManagerRoster provides roster) {
                    AssistantSettingsScreen(management = hub, machines = emptyList(), onBack = {})
                }
            }
        }
        rule.waitForIdle()

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("governance-entry-direction-notes"))
        rule.onNodeWithTag("governance-entry-direction-notes").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("direction-notes-row-dir-1").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.runOnIdle { style = AppStyle.Brief }
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("direction-notes-field")))
            .performTextReplacement("Updated direction guidelines")
        hub.conflictOnce = DirectionNotesConflictException("changed", "Server notes", "2026-11-01T15:00:00Z")
        rule.onNodeWithTag("direction-notes-save").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("direction-notes-conflict").assertIsDisplayed()

        rule.onNodeWithTag("direction-notes-overwrite").performClick()
        rule.waitForIdle()
        assertEquals("Updated direction guidelines", hub.savedDirectionNotes.last().second)
        assertEquals("2026-11-01T15:00:00Z", hub.savedDirectionNotes.last().third)
    }
}
