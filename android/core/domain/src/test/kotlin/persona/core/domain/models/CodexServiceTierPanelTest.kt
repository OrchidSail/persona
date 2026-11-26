package persona.core.domain.models

import org.junit.Assert.*
import org.junit.Test
import persona.core.contracts.ModelPick
import persona.core.contracts.ServiceTier
import persona.core.contracts.Session
import persona.core.contracts.api.AgentLauncher
import persona.core.contracts.api.AgentModel
import persona.core.contracts.api.AssistantLaunchers
import persona.core.contracts.api.LauncherDefault
import persona.core.contracts.api.LauncherModels
import persona.core.domain.sort.canChangeServiceTier
import persona.core.domain.sort.hasFastServiceTier

class CodexServiceTierPanelTest {
    private val fastTiers = listOf(
        ServiceTier("default", "Standard"),
        ServiceTier("priority", "Fast"),
    )

    private fun codexSession(
        assistant: Boolean = false,
        tier: String? = "default",
        tiers: List<ServiceTier> = fastTiers,
        waiting: Boolean? = null,
    ) = Session(
        machineId = "mac",
        sessionId = "s1",
        status = "idle",
        agent = "codex",
        launcher = "codex",
        model = "gpt-5",
        assistant = assistant,
        serviceTier = tier,
        serviceTiers = tiers,
        waiting = waiting,
    )

    @Test fun sessionSourceAndKeepingDetailsPreserveServiceTierFields() {
        val full = codexSession(tier = "priority", tiers = fastTiers)
        assertTrue(full.hasFastServiceTier)
        assertTrue(full.canChangeServiceTier)

        val summary = codexSession(tier = null, tiers = emptyList(), waiting = false)
        val merged = summary.keepingDetails(full)
        assertEquals("priority", merged.serviceTier)
        assertEquals(fastTiers, merged.serviceTiers)
    }

    @Test fun agentModelPanelEnablesFastOnlyOnCurrentCodexRowAndReflectsPillState() {
        val session = codexSession(tier = "priority")
        val models = listOf(
            AgentModel(id = "gpt-5", name = "GPT-5", serviceTiers = listOf(ServiceTier("priority", "Fast"))),
            AgentModel(id = "gpt-5-mini", name = "GPT-5 mini", serviceTiers = listOf(ServiceTier("priority", "Fast"))),
        )
        val launchers = listOf(AgentLauncher(name = "codex", agentType = "codex"))
        val modelList = ModelList(read = true, list = LauncherModels(agent = "codex", models = models))

        assertTrue(AgentModelPanel.switches(session).second)
        val panel = AgentModelPanel.panel(session, launchers, { modelList }, emptySet())
        assertEquals(true, panel.state.current?.fast)
        val rows = panel.providers.single().models.orEmpty()
        assertTrue("current codex model supports fast", rows.first { it.id == "gpt-5" }.fast)
        assertFalse("non-current model in live agent does not show fast", rows.first { it.id == "gpt-5-mini" }.fast)

        val pill = AgentModelPanel.pill(session, modelList, launchers)
        assertTrue(pill.setup.fast)

        // Row with fast=true and empty efforts must still be customizable
        val row = PanelRow(
            key = "r",
            kind = PanelRow.Kind.Model,
            provider = panel.providers.single(),
            model = PanelModel("gpt-5", "GPT-5", efforts = emptyList(), fast = true),
            setup = PanelConfig("codex", "gpt-5", fast = true),
            current = true,
        )
        assertTrue(ModelPanel.rowCustomizable(row, panel.state, live = true))
    }

    @Test fun personaModelPanelSupportsLiveAndStarterFastGating() {
        val liveSession = codexSession(assistant = true, tier = "priority")
        val models = listOf(
            AgentModel(id = "gpt-5", name = "GPT-5", isDefault = true, serviceTiers = listOf(ServiceTier("priority", "Fast"))),
            AgentModel(id = "gpt-5-mini", name = "GPT-5 mini", serviceTiers = emptyList()),
        )
        val info = AssistantLaunchers(
            launchers = listOf(AgentLauncher(name = "codex", agentType = "codex")),
            default = LauncherDefault(provider = "codex", model = "gpt-5"),
        )
        val list = ModelList(read = true, list = LauncherModels(agent = "codex", models = models))

        // Live assistant chat: current row only
        val liveChat = PersonaModelPanel.Chat(liveSession, isMain = false, kept = true)
        val liveSetup = PersonaModelPanel.setup(liveChat, info)
        assertTrue(liveSetup.fast)
        val livePanel = PersonaModelPanel.panel(liveSetup, info, { list }, typed = false, engine = "codex", running = true, session = liveSession)
        assertEquals(true, livePanel.state.current?.fast)
        assertTrue(livePanel.providers.single().models!!.first { it.id == "gpt-5" }.fast)
        assertFalse(livePanel.providers.single().models!!.first { it.id == "gpt-5-mini" }.fast)
        assertTrue(PersonaModelPanel.pill(liveChat, liveSetup, info, list).setup.fast)

        // Starter chat: gated on startFastSupported and catalog priority tier
        val starterChat = PersonaModelPanel.Chat(
            session = null,
            isMain = false,
            kept = true,
            pagePick = ModelPick("codex", "gpt-5"),
            pageFast = true,
            startFastSupported = true,
        )
        val starterSetup = PersonaModelPanel.setup(starterChat, info)
        assertTrue(starterSetup.fast)
        val starterPanel = PersonaModelPanel.panel(starterSetup, info, { list }, typed = false, startFastSupported = true)
        assertEquals(true, starterPanel.state.current?.fast)
        assertTrue(starterPanel.providers.single().models!!.first { it.id == "gpt-5" }.fast)
        assertFalse(starterPanel.providers.single().models!!.first { it.id == "gpt-5-mini" }.fast)

        // Unsupported starter clears fast
        val blockedChat = starterChat.copy(startFastSupported = false)
        assertFalse(PersonaModelPanel.setup(blockedChat, info).fast)
    }
}
