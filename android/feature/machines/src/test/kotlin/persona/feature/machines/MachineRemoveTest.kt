package persona.feature.machines

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.Machine
import persona.core.contracts.MachineMetrics
import persona.core.contracts.MetricRange
import persona.core.contracts.MetricsDashboard
import persona.core.contracts.MetricsHistory
import persona.core.contracts.Session
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.ClaudeSetting
import persona.core.contracts.api.HerdrState
import persona.core.contracts.api.LauncherQuotaAnswer
import persona.core.contracts.api.MachineManagement
import persona.core.contracts.api.MachineMetricsApi
import persona.core.contracts.api.MachineRemoveResult
import persona.core.contracts.api.SessionsRepository
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalHubFeatures
import persona.core.designsystem.PersonaTheme
import persona.core.hub.HubError
import persona.core.hub.HubFailure
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * Interactive UI coverage for removing a paired computer (W-E01) across Glass, Brief, and Board:
 * feature/kind gating, confirmation dialog cancel vs confirm, online/offline support, error retention,
 * touch target sizing, and list/navigation refresh.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h2400dp-xhdpi")
class MachineRemoveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class FakeSessions(initial: List<Machine>, var features: Set<String> = emptySet()) : SessionsRepository {
        override val machines = MutableStateFlow(initial)
        override val sessions = MutableStateFlow(emptyList<Session>())
        var refreshes = 0
        override suspend fun refresh() { refreshes++ }
        override fun supportsFeature(machineId: String, feature: String): Boolean = feature in features
    }

    private class FakeManagement : MachineManagement {
        val removed = mutableListOf<String>()
        var removeFailure: Exception? = null
        var onRemove: (String) -> Unit = {}
        override suspend fun launchers(machineId: String): List<AgentLauncher> = emptyList()
        override suspend fun launcherQuota(machineId: String, name: String, refresh: Boolean) = LauncherQuotaAnswer()
        override suspend fun reconnect(machineId: String) = Unit
        override suspend fun deploy(machineId: String) = Unit
        override suspend fun herdr(machineId: String) = HerdrState(false, false, false)
        override suspend fun startHerdr(machineId: String) = HerdrState(false, false, false)
        override suspend fun setEngine(machineId: String, engine: String) = Machine(machineId, engine = engine)
        override suspend fun claudeSettings(machineId: String): List<ClaudeSetting> = emptyList()
        override suspend fun setClaudeSetting(machineId: String, key: String, value: JsonElement): List<ClaudeSetting> = emptyList()
        override suspend fun codexSettings(machineId: String): List<ClaudeSetting> = emptyList()
        override suspend fun setCodexSetting(machineId: String, key: String, value: JsonElement): List<ClaudeSetting> = emptyList()
        override suspend fun botmux(machineId: String): Boolean? = null
        override suspend fun remove(machineId: String): MachineRemoveResult {
            removed += machineId
            removeFailure?.let { throw it }
            onRemove(machineId)
            return MachineRemoveResult(restartRequired = false)
        }
    }

    private fun metricsApi(id: String) = object : MachineMetricsApi {
        private val m = MachineMetrics(id, true)
        override suspend fun dashboard() = MetricsDashboard(listOf(m), step = 10)
        override suspend fun history(machineId: String, range: MetricRange) = MetricsHistory(m, range.wire, range.step)
    }

    @Test fun hiddenWithoutFeatureOrForMemberHubComputers() {
        val desk = Machine("desk", name = "Desk", kind = "link", online = true)
        val hubMember = Machine("member", name = "Member Hub", kind = "hub", online = true)
        val repository = FakeSessions(listOf(desk, hubMember))
        val management = FakeManagement()
        var features by mutableStateOf(emptySet<String>())
        var targetId by mutableStateOf("desk")
        val deskModel = MachinesViewModel(metricsApi("desk"), repository, "desk") { 1_000L }
        val memberModel = MachinesViewModel(metricsApi("member"), repository, "member") { 1_000L }
        runBlocking { deskModel.load(MetricRange.Hour); memberModel.load(MetricRange.Hour) }
        val actions = MachineActionsViewModel(management, repository)
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() = Unit
            override fun selectTab(tab: AppTab) = Unit
        }
        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Glass) {
                CompositionLocalProvider(LocalHubFeatures provides features) {
                    MachinesScreen(
                        machineId = targetId,
                        model = if (targetId == "desk") deskModel else memberModel,
                        navigator = navigator,
                        actions = actions,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("machine-remove-row").assertCountEquals(0)

        // With feature advertised, member-hub computer (kind == "hub") still hides Remove computer.
        features = setOf(MachineRemoveFeature)
        targetId = "member"
        rule.waitForIdle()
        rule.onAllNodesWithTag("machine-remove-row").assertCountEquals(0)
    }

    @Test fun cancelAndConfirmRemoveInGlass() = verifyCancelAndConfirmRemove(AppStyle.Glass, online = true)

    @Test fun cancelAndConfirmRemoveInBriefOffline() = verifyCancelAndConfirmRemove(AppStyle.Brief, online = false)

    @Test fun cancelAndConfirmRemoveInBoard() = verifyCancelAndConfirmRemove(AppStyle.Board, online = true)

    private fun verifyCancelAndConfirmRemove(style: AppStyle, online: Boolean) {
        val computer = Machine("desk", name = "Work Mac", kind = "link", online = online)
        val repository = FakeSessions(listOf(computer), features = setOf(MachineRemoveFeature))
        val management = FakeManagement().apply {
            onRemove = { id -> repository.machines.value = repository.machines.value.filterNot { it.id == id } }
        }
        val model = MachinesViewModel(metricsApi("desk"), repository, "desk") { 1_000L }
        runBlocking { model.load(MetricRange.Hour) }
        val actions = MachineActionsViewModel(management, repository)
        var backs = 0
        val removedCallbacks = mutableListOf<String>()
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() { backs++ }
            override fun selectTab(tab: AppTab) = Unit
        }

        rule.setContent {
            PersonaTheme(reduceMotion = true, style = style) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(MachineRemoveFeature)) {
                    MachinesScreen(
                        machineId = "desk",
                        model = model,
                        navigator = navigator,
                        actions = actions,
                        onMachineRemoved = { removedCallbacks += it },
                    )
                }
            }
        }
        rule.waitForIdle()

        val row = rule.onNodeWithTag("machine-remove-row")
        row.performScrollTo()
        row.assertHeightIsAtLeast(44.dp)
        row.performClick()
        rule.waitForIdle()

        // Confirmation dialog names the computer and explains the irreversible pairing revocation.
        val expectedTitle = rule.activity.getString(R.string.machines_remove_title, "Work Mac")
        val expectedConfirm = rule.activity.getString(R.string.machines_remove_confirm)
        rule.onNode(hasText(expectedTitle) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertTrue("Cancel must not call remove in $style", management.removed.isEmpty())
        assertEquals(0, backs)

        // Confirm invokes remove, refreshes sessions, notifies filter callback, and navigates back.
        row.performClick()
        rule.waitForIdle()
        rule.onNode(hasText(expectedConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { backs == 1 }
        rule.waitForIdle()

        assertEquals("style=$style", listOf("desk"), management.removed)
        assertEquals("style=$style", listOf("desk"), removedCallbacks)
        assertTrue("style=$style should refresh sessions", repository.refreshes >= 1)
        assertTrue("style=$style should remove computer from list", repository.machines.value.isEmpty())
    }

    @Test fun serverFailureShowsInlineErrorInsideDialogWithoutNavigatingBack() {
        val computer = Machine("desk", name = "Desk", kind = "link", online = true)
        val repository = FakeSessions(listOf(computer), features = setOf(MachineRemoveFeature))
        val management = FakeManagement().apply {
            removeFailure = HubFailure(HubError.Server, "Switch to Persona Link is running", 409, "link_switch_in_progress")
        }
        val model = MachinesViewModel(metricsApi("desk"), repository, "desk") { 1_000L }
        runBlocking { model.load(MetricRange.Hour) }
        val actions = MachineActionsViewModel(management, repository)
        var backs = 0
        val removedCallbacks = mutableListOf<String>()
        val navigator = object : Navigator {
            override fun navigate(key: NavKey) = Unit
            override fun back() { backs++ }
            override fun selectTab(tab: AppTab) = Unit
        }

        rule.setContent {
            PersonaTheme(reduceMotion = true, style = AppStyle.Board) {
                CompositionLocalProvider(LocalHubFeatures provides setOf(MachineRemoveFeature)) {
                    MachinesScreen(
                        machineId = "desk",
                        model = model,
                        navigator = navigator,
                        actions = actions,
                        onMachineRemoved = { removedCallbacks += it },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("machine-remove-row").performScrollTo().performClick()
        rule.waitForIdle()
        val expectedConfirm = rule.activity.getString(R.string.machines_remove_confirm)
        rule.onNode(hasText(expectedConfirm) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(5_000) { management.removed == listOf("desk") }
        rule.waitForIdle()

        rule.onNode(hasText("Switch to Persona Link is running") and hasAnyAncestor(isDialog())).assertExists()
        assertEquals(0, backs)
        assertTrue(removedCallbacks.isEmpty())
    }
}
