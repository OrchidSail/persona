package persona.core.contracts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import persona.core.contracts.api.*

class ModelConfigurationScopeTest {
    @Test fun capturedOwnersSurviveSelectionChangesAndLateReplies() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String?>>()
        val item = ModelPurpose("agent.new")
        val source = object : ModelConfiguration {
            private suspend fun record(operation: String) {
                calls += operation to currentCoroutineContext()[RequestManager]?.id
            }
            override suspend fun catalog(): ModelCatalog {
                record("catalog")
                if (currentCoroutineContext()[RequestManager]?.id == "assistant") {
                    entered.complete(Unit); release.await()
                }
                return ModelCatalog(listOf(item))
            }
            override suspend fun purpose(id: String): ModelPurposeDetail {
                record("purpose"); return ModelPurposeDetail(item)
            }
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft): ModelPurposeSave {
                record("save"); return ModelPurposeSave.Saved(ModelPurposeDetail(item))
            }
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? {
                record("provider"); return null
            }
        }
        val first = source.forManager("assistant")
        val second = first.forManager("work")
        val pending = async { first.catalog() }
        entered.await()
        second.catalog(); second.purpose("agent.new")
        second.save(item, ModelConfigurationDraft()); second.providerModels("demo", false, null)
        release.complete(Unit); pending.await()
        first.save(item, ModelConfigurationDraft())
        assertEquals(listOf("catalog" to "assistant", "catalog" to "work", "purpose" to "work",
            "save" to "work", "provider" to "work", "save" to "assistant"), calls)
        assertNull(currentCoroutineContext()[RequestManager])
        assertTrue(runCatching { source.forManager("") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun forManagerForwardsModelProfileOperations() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()
        val emptyView = ModelProfilesView(revision = 2, active = "performance")
        val source = object : ModelConfiguration {
            private suspend fun record(op: String) {
                calls += op to currentCoroutineContext()[RequestManager]?.id
            }
            override suspend fun catalog() = ModelCatalog()
            override suspend fun purpose(id: String) = throw ModelPurposeNotFound(id)
            override suspend fun save(purpose: ModelPurpose, draft: ModelConfigurationDraft) = ModelPurposeSave.Conflict
            override suspend fun providerModels(provider: String, refresh: Boolean, machine: String?): ProviderModels? = null
            override suspend fun profiles(): ModelProfilesView { record("profiles"); return emptyView }
            override suspend fun profileCandidates(id: String): ModelProfileCandidatesView? { record("candidates:$id"); return ModelProfileCandidatesView() }
            override suspend fun applyProfile(id: String): ModelProfilesView { record("apply:$id"); return emptyView }
            override suspend fun rollbackProfile(): ModelProfilesView { record("rollback"); return emptyView }
            override suspend fun saveProfile(id: String, input: ModelProfileSaveInput): ModelProfileSave {
                record("save:$id"); return ModelProfileSave.Saved(emptyView)
            }
        }
        val scoped = source.forManager("work")
        scoped.profiles()
        scoped.profileCandidates("economy")
        scoped.applyProfile("economy")
        scoped.rollbackProfile()
        scoped.saveProfile("performance", ModelProfileSaveInput(revision = 2, name = "Performance"))
        assertEquals(
            listOf("profiles" to "work", "candidates:economy" to "work", "apply:economy" to "work", "rollback" to "work", "save:performance" to "work"),
            calls,
        )
    }

    @Test fun profileTierCandidatesAliasesTrialAndCaptureRoundTrip() {
        val raw = """
            {
              "revision": 7,
              "active": "performance",
              "rollbacks": [
                {"id": "rb-1", "to": "economy", "at": "2026-11-25T00:00:00Z", "changes": ["assistant"], "redo": true}
              ],
              "profiles": [
                {
                  "id": "economy",
                  "name": "Economy",
                  "description": "Balanced",
                  "active": false,
                  "changes": ["assistant"],
                  "trial": {"days": 7, "dispatches": 18, "would_switch": 5},
                  "tiers": {
                    "core": {
                      "launcher": "claude",
                      "model": "opus",
                      "effort": "high",
                      "speed": "fast",
                      "aliases": ["strong"],
                      "candidates": [
                        {"launcher": "claude", "model": "opus", "effort": "high", "speed": "fast"},
                        {"launcher": "codex", "model": "gpt-5", "effort": "medium", "speed": "standard"}
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val view = JsonCodec.decodeFromString<ModelProfilesView>(raw)
        assertEquals(1, view.rollbacks.size)
        assertEquals("rb-1", view.rollbacks.single().id)
        assertTrue(view.rollbacks.single().redo)
        val profile = view.profiles.single()
        assertEquals(ModelProfileTrial(days = 7, dispatches = 18, wouldSwitch = 5), profile.trial)
        val tier = profile.tiers.getValue("core")
        assertEquals(listOf("strong"), tier.aliases)
        assertEquals(2, tier.candidates?.size)
        val serializedTier = tier.toJson().toString()
        assertTrue(serializedTier.contains("\"candidates\""))
        assertTrue(serializedTier.contains("\"aliases\":[\"strong\"]"))
        assertFalse("standard speed must be omitted for older hub compat", serializedTier.contains("\"standard\""))

        val capture = ModelProfileSaveInput.capture(profile.copy(description = ""), revision = 7).toJson().toString()
        assertTrue(capture.contains("\"capture\":true"))
        assertFalse("empty description omitted on capture", capture.contains("\"description\""))
        assertFalse("tiers omitted on capture so server preserves them", capture.contains("\"tiers\""))
    }

    @Test fun profileSaveInputAndDraftPermissionModeSerializeCleanly() {
        val input = ModelProfileSaveInput(
            revision = 3,
            name = "Economy",
            description = "Lower cost",
            purposes = mapOf(
                "assistant" to ModelBinding(provider = "launcher.claude", model = "sonnet", effort = "medium", permissionMode = "plan"),
            ),
            launchers = mapOf(
                "gateway" to ModelProfileLauncherModels(model = "glm-5", smallModel = "", effort = null),
            ),
            tiers = mapOf(
                "fast" to ModelProfileTier(launcher = "claude", model = "haiku", effort = "low", speed = "fast", permissionMode = "acceptEdits", description = "Quick edits"),
            ),
        )
        val json = input.toJson().toString()
        assertTrue(json.contains("\"revision\":3"))
        assertTrue(json.contains("\"permission_mode\":\"plan\""))
        assertTrue(json.contains("\"small_model\":\"\""))
        assertFalse(json.contains("\"opus_model\""))
        assertFalse(json.contains("config_dir"))

        val launcherPurpose = ModelPurpose(
            id = "agent.new",
            kind = "launcher",
            revision = 5,
            fields = listOf("provider", "model", "effort", "permission_mode"),
        )
        val draft = ModelConfigurationDraft(provider = "launcher.claude", model = "opus", effort = "high", permissionMode = "auto")
        val patch = draft.patch(launcherPurpose)
        assertEquals("auto", patch["permission_mode"]?.toString()?.trim('"'))
        assertEquals("", draft.choosingProvider("launcher.codex").permissionMode)

        // Capture input emits only minimal { revision, name, description?, capture: true }.
        val captureWithDesc = ModelProfileSaveInput.capture(revision = 7, name = "Performance", description = "Fast models").toJson()
        assertEquals(setOf("revision", "name", "description", "capture"), captureWithDesc.keys)
        assertEquals("true", captureWithDesc["capture"]?.toString())
        val captureNoDesc = ModelProfileSaveInput.capture(revision = 2, name = "Economy", description = "  ").toJson()
        assertEquals(setOf("revision", "name", "capture"), captureNoDesc.keys)
        assertEquals("true", captureNoDesc["capture"]?.toString())

        // Cross-version compatibility: default speed ("standard") and empty permission_mode are omitted.
        val compat = ModelProfileSaveInput(
            revision = 4,
            name = "Compat",
            purposes = mapOf("assistant" to ModelBinding(provider = "launcher.claude", model = "sonnet", permissionMode = "")),
            tiers = mapOf(
                "standard" to ModelProfileTier(launcher = "claude", model = "sonnet", speed = "standard", permissionMode = ""),
                "quick" to ModelProfileTier(launcher = "claude", speed = "fast", permissionMode = "plan"),
            ),
        ).toJson().toString()
        assertFalse(compat.contains("\"speed\":\"standard\""))
        assertFalse(compat.contains("\"permission_mode\":\"\""))
        assertTrue(compat.contains("\"speed\":\"fast\""))
        assertTrue(compat.contains("\"permission_mode\":\"plan\""))
    }
}
