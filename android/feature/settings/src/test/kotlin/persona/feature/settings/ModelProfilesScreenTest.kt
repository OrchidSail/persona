package persona.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.api.*
import persona.core.designsystem.*

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h2200dp-xhdpi")
class ModelProfilesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val providers = listOf(
        ModelProvider("launcher.claude", "Claude Code", kind = "launcher", agent = "claude"),
        ModelProvider("launcher.codex", "Codex", kind = "launcher", agent = "codex"),
        ModelProvider("api.fast", "Fast API", kind = "api", api = "responses"),
    )
    private val purposes = listOf(
        ModelPurpose("assistant", "Main chat", "Everyday uses", effective = ModelBinding(provider = "launcher.claude", model = "opus")),
        ModelPurpose("agent.new", "New agent", "Agents", effective = ModelBinding(provider = "launcher.claude", model = "sonnet")),
    )

    @Test fun profilesSectionSwitchesEditsCapturesAndRollsBack() {
        var currentView = ModelProfilesView(
            revision = 2,
            active = "performance",
            rollback = ModelProfileUndo(to = "economy", changes = listOf("assistant")),
            profiles = listOf(
                ModelProfile(
                    id = "performance",
                    name = "Performance",
                    description = "Strong models for complex tasks",
                    purposes = mapOf("assistant" to ModelBinding(provider = "launcher.claude", model = "opus", effort = "high")),
                    tiers = mapOf(
                        "core" to ModelProfileTier(
                            launcher = "claude",
                            model = "opus",
                            effort = "high",
                            description = "Complex work",
                            aliases = listOf("strong"),
                            candidates = listOf(
                                ModelProfileTierCandidate(launcher = "claude", model = "opus", effort = "high"),
                                ModelProfileTierCandidate(launcher = "codex", model = "gpt-5", effort = "medium"),
                            ),
                        ),
                    ),
                    active = true,
                    changes = emptyList(),
                ),
                ModelProfile(
                    id = "economy",
                    name = "Economy",
                    description = "Lower cost models",
                    purposes = mapOf("assistant" to ModelBinding(provider = "launcher.claude", model = "sonnet", effort = "medium")),
                    tiers = mapOf(
                        "dev" to ModelProfileTier(
                            launcher = "claude",
                            model = "sonnet",
                            description = "Routine work",
                            aliases = listOf("standard"),
                        ),
                    ),
                    active = false,
                    changes = listOf("assistant"),
                    trial = ModelProfileTrial(days = 7, dispatches = 15, wouldSwitch = 6),
                ),
            ),
        )
        val candidatesResponse = ModelProfileCandidatesView(
            days = 14,
            tiers = mapOf(
                "dev" to listOf(
                    ModelProfileCandidateStatus(
                        index = 0,
                        launcher = "claude",
                        model = "sonnet",
                        headroom = ModelProfileCandidateHeadroom(known = true, percent = 84.0, stale = false),
                        stats = ModelProfileCandidateStats(n = 10, minutesP50 = 3.5, firstPass = 0.8),
                    ),
                ),
            ),
        )
        val applied = mutableListOf<String>()
        var rollbacks = 0
        val savedInputs = mutableListOf<Pair<String, ModelProfileSaveInput>>()
        var catalogReads = 0

        val api = object : ModelConfiguration {
            override suspend fun catalog(): ModelCatalog {
                catalogReads++
                return ModelCatalog(purposes, providers)
            }
            override suspend fun purpose(id: String) = ModelPurposeDetail(purposes.first { it.id == id }, providers)
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft) = ModelPurposeSave.Conflict
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? = null
            override suspend fun profiles(): ModelProfilesView = currentView
            override suspend fun profileCandidates(id: String): ModelProfileCandidatesView = candidatesResponse
            override suspend fun applyProfile(id: String): ModelProfilesView {
                applied += id
                currentView = currentView.copy(
                    active = id,
                    profiles = currentView.profiles.map { it.copy(active = it.id == id) },
                )
                return currentView
            }
            override suspend fun rollbackProfile(): ModelProfilesView {
                rollbacks++
                currentView = currentView.copy(active = "economy", rollback = null)
                return currentView
            }
            override suspend fun saveProfile(id: String, input: ModelProfileSaveInput): ModelProfileSave {
                savedInputs += id to input
                currentView = currentView.copy(
                    revision = currentView.revision + 1,
                    profiles = currentView.profiles.map {
                        if (it.id == id) it.copy(
                            name = input.name,
                            description = input.description,
                            tiers = input.tiers ?: it.tiers,
                        ) else it
                    },
                )
                return ModelProfileSave.Saved(currentView)
            }
        }

        rule.setContent {
            CompositionLocalProvider(LocalManagerRoster provides null) {
                PersonaTheme(darkTheme = false, reduceMotion = true) {
                    ModelConfigurationPage(api, onBack = {}, onPurpose = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()

        // Profiles, inactive trial summary, and Rollback are rendered
        rule.onNodeWithTag("model-profile-row-performance").assertIsDisplayed()
        rule.onNodeWithTag("model-profile-row-economy").assertIsDisplayed()
        rule.onNodeWithText("Last 7 days: 15 dispatched, 6 would have switched", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("model-profile-rollback").assertIsDisplayed()

        // Open Economy profile detail: check trial card, aliases, capture button, then capture from current
        rule.onNodeWithTag("model-profile-row-economy").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-trial-card").assertIsDisplayed()
        rule.onNodeWithText("Aliases: standard", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("model-profile-capture-button").performClick()
        rule.waitForIdle()
        assertEquals(1, savedInputs.size)
        assertTrue(savedInputs.last().second.capture)
        assertNull("capture must omit tiers so server preserves them", savedInputs.last().second.tiers)

        // Open editor: candidate headroom/stats & aliases visible; add candidate and save
        rule.onNodeWithTag("model-profile-edit-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("cand-status-tier-0-dev-cand-0-0").assertIsDisplayed()
        rule.onNodeWithText("Headroom 84%", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("tier-aliases-tier-0-dev").assertIsDisplayed()
        rule.onNodeWithTag("tier-add-candidate-tier-0-dev").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-save-button").performClick()
        rule.waitForIdle()

        assertEquals(2, savedInputs.size)
        val savedDev = savedInputs.last().second.tiers!!.getValue("dev")
        assertEquals(2, savedDev.candidates?.size)
        assertEquals(listOf("standard"), savedDev.aliases)

        // Apply Economy profile (drifted -> opens drift confirmation dialog -> apply directly)
        rule.onNodeWithTag("model-profile-apply-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-apply-confirm").assertIsDisplayed()
        rule.onNodeWithTag("model-profile-apply-directly-button").performClick()
        rule.waitForIdle()
        assertEquals(listOf("economy"), applied)
        assertTrue("catalog should refresh after save/apply", catalogReads >= 4)

        // Trigger rollback from main page
        rule.onAllNodesWithText(rule.activity.getString(R.string.settings_model_profiles_rollback)).onLast().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, rollbacks)
    }

    private fun assertCaptureActiveFirstInStyle(style: AppStyle) {
        val view = ModelProfilesView(
            revision = 5,
            active = "performance",
            profiles = listOf(
                ModelProfile(id = "performance", name = "Performance", active = true, changes = listOf("assistant")),
                ModelProfile(id = "economy", name = "Economy", active = false, changes = listOf("agent.new")),
            ),
        )
        val ops = mutableListOf<String>()
        val api = object : ModelConfiguration {
            override suspend fun catalog() = ModelCatalog(purposes, providers)
            override suspend fun purpose(id: String) = ModelPurposeDetail(purposes.first { it.id == id }, providers)
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft) = ModelPurposeSave.Conflict
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? = null
            override suspend fun profiles() = view
            override suspend fun saveProfile(id: String, input: ModelProfileSaveInput): ModelProfileSave {
                ops += "capture:$id:${input.capture}"
                return ModelProfileSave.Saved(view.copy(revision = 6))
            }
            override suspend fun applyProfile(id: String): ModelProfilesView {
                ops += "apply:$id"
                return view.copy(revision = 6, active = id)
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalManagerRoster provides null) {
                PersonaTheme(style = style, darkTheme = false, reduceMotion = true) {
                    ModelConfigurationPage(api, onBack = {}, onPurpose = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-row-economy").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-apply-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("model-profile-capture-active-first-button").performClick()
        rule.waitForIdle()
        assertEquals("style=$style", listOf("capture:performance:true", "apply:economy"), ops)
    }

    @Test fun captureActiveFirstWorksInGlass() = assertCaptureActiveFirstInStyle(AppStyle.Glass)
    @Test fun captureActiveFirstWorksInBrief() = assertCaptureActiveFirstInStyle(AppStyle.Brief)
    @Test fun captureActiveFirstWorksInBoard() = assertCaptureActiveFirstInStyle(AppStyle.Board)

    @Test fun oldHubWithoutProfilesHidesProfilesSectionCleanly() {
        val api = object : ModelConfiguration {
            override suspend fun catalog() = ModelCatalog(purposes, providers)
            override suspend fun purpose(id: String) = ModelPurposeDetail(purposes.first { it.id == id }, providers)
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft) = ModelPurposeSave.Conflict
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? = null
            override suspend fun profiles(): ModelProfilesView? = null
        }

        rule.setContent {
            CompositionLocalProvider(LocalManagerRoster provides null) {
                PersonaTheme(darkTheme = false, reduceMotion = true) {
                    ModelConfigurationPage(api, onBack = {}, onPurpose = { _, _ -> })
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Main chat").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_model_profiles_title)).assertDoesNotExist()
    }
}
