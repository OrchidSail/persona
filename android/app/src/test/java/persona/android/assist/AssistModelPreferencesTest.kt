package persona.android.assist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.ModelPick
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.AgentModel
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.contracts.api.LauncherModels

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AssistModelPreferencesTest {
    @Test
    fun persistsChoicePerHubScopeAndClearsToDefaultCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_assist_model_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AssistModelPreferences(prefs)

        assertTrue(store.read("hub-a").isDefault)
        assertTrue(store.read("hub-b").isDefault)

        val fastChoice = DigitalAssistantModelChoice(
            pick = ModelPick(launcher = "gemini", model = "gemini-2.5-flash"),
            modelLabel = "Gemini 2.5 Flash",
            launcherLabel = "Gemini",
        )
        store.write("hub-a", fastChoice)

        assertEquals(fastChoice, store.read("hub-a"))
        assertTrue("Other paired Hubs must remain isolated", store.read("hub-b").isDefault)

        store.clear("hub-a")
        assertTrue(store.read("hub-a").isDefault)
    }

    @Test
    fun encodeDecodeHandlesRoundTripAndMalformedJsonGracefully() {
        val original = DigitalAssistantModelChoice(
            pick = ModelPick(launcher = "claude", model = "claude-haiku-4-5", effort = "low"),
            modelLabel = "Claude Haiku 4.5",
            launcherLabel = "Claude",
        )
        val encoded = AssistModelPreferences.encodeChoice(original)
        assertEquals(original, AssistModelPreferences.decodeChoice(encoded))
        assertNull(AssistModelPreferences.decodeChoice(""))
        assertNull(AssistModelPreferences.decodeChoice("not-json"))
        assertNull(AssistModelPreferences.decodeChoice("{}"))
    }

    @Test
    fun buildModelGroupsMapsUnlockedModelsAndFallsBackForLockedOrEmptyLaunchers() {
        val launchers = listOf(
            AgentLauncher(name = "claude", displayName = "Claude", model = "claude-haiku-4-5", agentType = "claude"),
            AgentLauncher(name = "locked-cli", displayName = "Locked CLI", model = "fixed-fast", agentType = "claude"),
            AgentLauncher(name = "no-list", displayName = "Custom", model = null, agentType = "codex"),
        )
        val models = mapOf(
            "claude" to LauncherModels(
                agent = "claude",
                models = listOf(
                    AgentModel(id = "claude-haiku-4-5", name = "Haiku 4.5", description = "Fast everyday model"),
                    AgentModel(id = "claude-sonnet-4-5", name = "Sonnet 4.5"),
                ),
            ),
            "locked-cli" to LauncherModels(
                agent = "claude",
                models = listOf(AgentModel(id = "ignored", name = "Ignored")),
                locked = "Configured by launcher flags",
            ),
            "no-list" to null,
        )

        val groups = buildDigitalAssistantModelGroups(launchers, models)
        assertEquals(3, groups.size)

        assertEquals("Claude Code", groups[0].title)
        assertEquals(2, groups[0].options.size)
        assertEquals(ModelPick("claude", "claude-haiku-4-5"), groups[0].options[0].pick)
        assertEquals("Haiku 4.5", groups[0].options[0].modelLabel)
        assertTrue(groups[0].options[0].isDefaultForLauncher)

        assertEquals("Locked CLI", groups[1].title)
        assertEquals(1, groups[1].options.size)
        assertEquals(ModelPick("locked-cli", "fixed-fast"), groups[1].options.single().pick)

        assertEquals("Custom", groups[2].title)
        assertEquals(1, groups[2].options.size)
        assertEquals(ModelPick("no-list", null), groups[2].options.single().pick)
    }

    @Test
    fun persistsOverlayTargetAndModelSelectionPerHubScopeAndHandlesMalformedJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_assist_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AssistModelPreferences(prefs)

        assertEquals(AssistOverlaySelection(), store.readSelection("hub-a"))

        val existingSelection = AssistOverlaySelection(
            target = AssistTargetConversation.Existing(
                key = SessionKey("machine-1", "conv-singscale"),
                title = "SingScale",
            ),
            targetModelChoice = DigitalAssistantModelChoice(
                pick = ModelPick(launcher = "antigravity-cli", model = "gemini-4-neon", effort = "xhigh"),
                modelLabel = "Gemini 4 Neon (XHigh)",
                launcherLabel = "Antigravity CLI",
            ),
        )
        store.writeSelection("hub-a", existingSelection)
        assertEquals(existingSelection, store.readSelection("hub-a"))
        assertEquals("Other Hub must remain isolated", AssistOverlaySelection(), store.readSelection("hub-b"))

        val explicitDefaultSelection = AssistOverlaySelection(
            target = AssistTargetConversation.NewChat,
            targetModelChoice = null,
            explicitDefaultModel = true,
        )
        store.writeSelection("hub-a", explicitDefaultSelection)
        assertEquals(explicitDefaultSelection, store.readSelection("hub-a"))

        store.writeSelection("hub-a", AssistOverlaySelection(target = AssistTargetConversation.Main))
        assertEquals(AssistOverlaySelection(), store.readSelection("hub-a"))

        assertNull(AssistModelPreferences.decodeSelection(""))
        assertNull(AssistModelPreferences.decodeSelection("broken-json"))
        assertNull(AssistModelPreferences.decodeSelection("""{"kind":"existing","machineId":"m1"}"""))
        assertNull(AssistModelPreferences.decodeSelection("""{"kind":"unknown"}"""))
    }

    @Test
    fun formatCompactAssistModelLabelCompactsRawIdsClaudePrefixAndEffort() {
        assertEquals(
            "Sonnet 4.5",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("claude", "claude-sonnet-4-5-20250929"),
                    modelLabel = "claude-sonnet-4-5-20250929",
                    launcherLabel = "Claude Code",
                ),
            ),
        )
        assertEquals(
            "Opus 4.6",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("claude", "us.anthropic.claude-opus-4-6-v1:0"),
                    modelLabel = "us.anthropic.claude-opus-4-6-v1:0",
                    launcherLabel = "Claude Code",
                ),
            ),
        )
        assertEquals(
            "GPT-5.3 Codex · XHigh",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("codex", "openai/gpt-5.3-codex-20260401", effort = "xhigh"),
                    modelLabel = "openai/gpt-5.3-codex-20260401",
                    launcherLabel = "Codex",
                ),
            ),
        )
        assertEquals(
            "Gemini 2.5 Pro",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("gemini", "models/gemini-2.5-pro-preview-06-05"),
                    modelLabel = "models/gemini-2.5-pro-preview-06-05",
                    launcherLabel = "Gemini",
                ),
            ),
        )
        assertEquals(
            "Gemini 4 Neon Medium",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("antigravity-cli", "gemini-4-neon-medium"),
                    modelLabel = "gemini-4-neon-medium",
                    launcherLabel = "Antigravity CLI",
                ),
            ),
        )
        assertEquals(
            "Sonnet 4.5 (High)",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("claude", "claude-sonnet-4-5", effort = "high"),
                    modelLabel = "Claude Sonnet 4.5 (High)",
                    launcherLabel = "Claude Code",
                ),
            ),
        )
        assertEquals(
            "Gemini 4 Neon (XHigh)",
            formatCompactAssistModelLabel(
                DigitalAssistantModelChoice(
                    pick = ModelPick("antigravity-cli", "gemini-4-neon-xhigh"),
                    modelLabel = "Gemini 4 Neon (XHigh)",
                    launcherLabel = "Antigravity CLI",
                ),
            ),
        )
    }
}
