package persona.core.hub

import persona.core.contracts.JsonCodec
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AgentSkill
import persona.core.contracts.api.AlwaysGrant
import persona.core.contracts.api.AssistantConfigUpdate
import persona.core.contracts.api.AssistantRefused
import persona.core.contracts.api.ConversationSwitchRequest
import persona.core.contracts.api.ConversationSwitchState
import persona.core.contracts.api.ConversationTagFilters
import persona.core.contracts.api.AssistantSkills
import persona.core.contracts.api.DefaultRepositoryProviders
import persona.core.contracts.api.DirectionNotesConflictException
import persona.core.contracts.api.GovernanceUnsupportedException
import persona.core.contracts.api.NewMainResult
import persona.core.contracts.api.RepositoryInput
import persona.core.contracts.api.ScheduledTask
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import persona.core.hub.ProtocolCompatibilityMockServer as MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AssistantManagementTest {
    @Test fun mainAssistantCatalogReadsOwnPurposeWithoutReplacingEligibility() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            val shared = """{"launchers":[{"name":"claude"}],"default":{"provider":"claude","model":"shared","effort":"low"}}"""
            for ((own, expected) in listOf(
                MockResponse().setBody("""{"launchers":[{"name":"not-eligible"}],"default":{"provider":"claude","model":"main","effort":"high"}}""") to "main",
                MockResponse().setBody("""{"launchers":[]}""") to "shared",
                MockResponse().setResponseCode(404).setBody("""{"error":"unknown purpose"}""") to "shared",
            )) {
                server.enqueue(MockResponse().setBody(shared))
                server.enqueue(own)
                val result = api.mainAssistantLauncherList("box", refresh = true)
                assertEquals(listOf("claude"), result.launchers.map { it.name })
                assertEquals(expected, result.default?.model)
                assertEquals("assistant", server.takeRequest().requestUrl?.queryParameter("purpose"))
                assertEquals("assistant.main", server.takeRequest().requestUrl?.queryParameter("purpose"))
            }
            server.enqueue(MockResponse().setBody(shared))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily unavailable"}"""))
            val failure = runCatching { api.mainAssistantLauncherList("box") }.exceptionOrNull() as HubFailure
            assertEquals(503, failure.status)
        }
    }

    /** "Push when done" travels both ways; a task without it never sends it, so a hub without `task_push` is not sent it. */
    @Test fun taskPushIsReadAndSentOnlyWhenSet() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()) { true }
            server.enqueue(MockResponse().setBody("""{"tasks":[{"id":"t1","repeat":"interval","every_minutes":30,"push":"asked"},{"id":"t2","repeat":"daily"}]}"""))
            val (asked, plain) = api.tasks()
            server.takeRequest()
            assertTrue(asked.pushesOnlyAsked)
            assertNull(plain.push)
            assertFalse(plain.pushesOnlyAsked)

            server.enqueue(MockResponse().setBody("""{"task":{"id":"t1","repeat":"interval","push":"asked"}}"""))
            api.saveTask(asked)
            assertEquals("asked", JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("push").jsonPrimitive.content)
            server.enqueue(MockResponse().setBody("""{"task":{"id":"t1","repeat":"interval","push":""}}"""))
            api.saveTask(asked.copy(push = ""))
            assertEquals("", JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("push").jsonPrimitive.content)
            server.enqueue(MockResponse().setBody("""{"task":{"id":"t2","repeat":"daily"}}"""))
            api.saveTask(plain)
            assertFalse(JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("push"))
        }
    }

    @Test fun migratedActionsPreserveDefaultsPathsAndPairingScope() = runBlocking {
        MockWebServer().use { server ->
            var current = true
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()) { current }
            server.enqueue(MockResponse().setBody("""{"task":{"id":"task_1","repeat":"daily","time":"09:00","enabled":true,"next_run":"2026-11-02T09:00:00+08:00"}}"""))
            val created = api.saveTask(ScheduledTask(title = "早报", prompt = "整理安排", repeats = ScheduledTask.DAILY, time = "09:00",
                enabled = true, nextRun = "server-owned", runSessionId = "server-owned"))
            val post = server.takeRequest()
            assertEquals("POST", (post.method).zhAny())
            assertEquals("/api/v1/assistant/tasks", (post.path).zhAny())
            assertEquals("Bearer test-token", (post.getHeader("Authorization")).zhAny())
            val body = JsonCodec.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals("09:00", (body.getValue("time").jsonPrimitive.content).zhAny())
            assertEquals("daily", (body.getValue("repeat").jsonPrimitive.content).zhAny())
            assertTrue(body.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(JsonArray(emptyList()), body["weekdays"])
            assertEquals("", (body.getValue("permission_mode").jsonPrimitive.content).zhAny())
            assertFalse(body.containsKey("id"))
            assertFalse(body.containsKey("next_run"))
            assertFalse(body.containsKey("run_session_id"))
            assertFalse(body.containsKey("push"))
            assertEquals("task_1", (created.id).zhAny())
            assertNotNull(created.nextRun)

            server.enqueue(MockResponse().setBody("""{"task":{"id":"task_1","repeat":"interval","every_minutes":30,"enabled":false}}"""))
            api.saveTask(created.copy(repeats = ScheduledTask.INTERVAL, everyMinutes = 30, enabled = false, permissionMode = "auto"))
            val put = server.takeRequest()
            assertEquals("PUT", (put.method).zhAny())
            assertEquals("/api/v1/assistant/tasks/task_1", (put.path).zhAny())
            val edited = JsonCodec.parseToJsonElement(put.body.readUtf8()).jsonObject
            assertEquals(30, edited.getValue("every_minutes").jsonPrimitive.int)
            assertEquals("auto", (edited.getValue("permission_mode").jsonPrimitive.content).zhAny())

            server.enqueue(MockResponse().setBody("""{"task":{"id":"task_1","repeat":"daily"}}"""))
            assertNull(api.freshTask("task_1").runSessionId)
            assertEquals("/api/v1/assistant/tasks/task_1/fresh", (server.takeRequest().path).zhAny())
            server.enqueue(MockResponse().setBody("{}"))
            api.endConversation(SessionKey("demo", "branch/one"))
            val close = server.takeRequest()
            assertEquals("POST", (close.method).zhAny())
            assertEquals("/api/v1/sessions/demo/branch%2Fone/close", (close.path).zhAny())

            server.enqueue(MockResponse().setBody("""{"machine_id":"demo","session_id":"started"}"""))
            assertEquals(SessionKey("demo", "started"), api.runTask("task_1"))
            assertEquals("/api/v1/assistant/tasks/task_1/run", (server.takeRequest().path).zhAny())
            server.enqueue(MockResponse().setBody("{}"))
            api.setNotes("")
            assertEquals("{\"notes\":\"\"}", (server.takeRequest().body.readUtf8()).zhAny())
            server.enqueue(MockResponse().setBody("{}"))
            api.saveMemory("example.md", "")
            val memory = server.takeRequest()
            assertEquals("/api/v1/assistant/memory/example.md", (memory.path).zhAny())
            assertEquals("{\"content\":\"\"}", (memory.body.readUtf8()).zhAny())

            current = false
            try { api.warm(); fail("Stale pairings must not send requests") }
            catch (_: CancellationException) { }
            assertEquals(7, server.requestCount)
        }
    }

    @Test fun archivingPostsToTheConversationsArchiveRoute() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"ok":true,"archived":true}"""))
            api.archiveConversation(SessionKey("demo", "side-1"))
            val archive = server.takeRequest()
            assertEquals("POST", (archive.method).zhAny())
            assertEquals("/api/v1/assistant/conversations/side-1/archive", (archive.path).zhAny())
        }
    }

    @Test fun conversationTagsReplaceTheWholeSetOnTheHub() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()).forManager("work")
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.setConversationTags(SessionKey("hub", "side-1"), listOf("planning", "trip"))
            val update = server.takeRequest()
            assertEquals("PUT", update.method)
            assertEquals("/api/v1/assistant/conversations/side-1/tags", update.requestUrl?.encodedPath)
            assertEquals("work", update.requestUrl?.queryParameter(ManagerQuery))
            assertEquals("work", update.getHeader(ManagerHeader))
            assertEquals("""{"tags":["planning","trip"]}""", update.body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.setConversationTags(SessionKey("hub", "side-1"), emptyList())
            assertEquals("""{"tags":[]}""", server.takeRequest().body.readUtf8())
        }
    }

    @Test fun conversationTagFiltersPutExactNullEmptyAndOrderedSets() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()).forManager("work")
            val values = listOf(
                ConversationTagFilters(null) to """{"conversation_tag_filters":null}""",
                ConversationTagFilters(emptyList()) to """{"conversation_tag_filters":[]}""",
                ConversationTagFilters(listOf("second", "first")) to """{"conversation_tag_filters":["second","first"]}""",
            )
            for ((value, body) in values) {
                server.enqueue(MockResponse().setBody("""{"conversation_tag_filters":${body.substringAfter(':').substringBeforeLast('}')}}"""))
                val answer = api.updateConfig(AssistantConfigUpdate(conversationTagFilters = value))
                assertEquals(value, answer.conversationTagFilters)
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/api/v1/assistant", request.requestUrl?.encodedPath)
                assertEquals("work", request.requestUrl?.queryParameter(ManagerQuery))
                assertEquals("work", request.getHeader(ManagerHeader))
                assertEquals(body, request.body.readUtf8())
            }
        }
    }

    /**
     * #5055: a Persona chat's pick is kept with `PUT …/model` (the launcher, the model unless it is the default entry, the
     * effort, the chat's computer) and dropped with `DELETE`; a refusal keeps the hub's words. The panel's providers come
     * from `?purpose=assistant`, with the default and what can't run there.
     */
    @Test fun aPersonaChatsPickIsKeptAndDroppedOnTheHub() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"ok":true,"model_pick":{"launcher":"team","model":"sonnet","effort":"low"}}"""))
            val kept = api.setModelPick(SessionKey("mac", "c2"), persona.core.contracts.ModelPick("team", "sonnet", "low"))
            assertEquals(persona.core.contracts.ModelPick("team", "sonnet", "low"), kept)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/api/v1/assistant/conversations/c2/model", put.path)
            assertEquals(buildJsonObject { put("launcher", "team"); put("model", "sonnet"); put("effort", "low"); put("machine_id", "mac") },
                JsonCodec.parseToJsonElement(put.body.readUtf8()))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.setModelPick(SessionKey("mac", "c2"), persona.core.contracts.ModelPick("claude"))
            assertEquals(buildJsonObject { put("launcher", "claude"); put("machine_id", "mac") }, JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.clearModelPick(SessionKey("mac", "c2"))
            val delete = server.takeRequest()
            assertEquals("DELETE" to "/api/v1/assistant/conversations/c2/model", delete.method to delete.path)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"这台电脑不在这个服务商的范围内","code":"launcher_out_of_scope"}"""))
            val refused = runCatching { api.setModelPick(SessionKey("mac", "c2"), persona.core.contracts.ModelPick("team")) }.exceptionOrNull() as HubFailure
            assertEquals(409 to "launcher_out_of_scope", refused.status to refused.code)
            assertEquals("这台电脑不在这个服务商的范围内", refused.message)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"launchers":[{"name":"team","display_name":"Team API","agent_type":"claude"}],
                "default":{"provider":"claude","model":"opus","effort":"high"},
                "unavailable":[{"name":"resp","kind":"api","api":"responses","code":"api_protocol","reason":"用的是 Responses 协议"}]}"""))
            val launchers = api.assistantLauncherList("mac")
            assertEquals("/api/v1/machines/mac/launchers?purpose=assistant", server.takeRequest().path)
            assertEquals("Team API", launchers.launchers.single().title)
            assertEquals("opus", launchers.default?.model)
            assertEquals("api_protocol" to "用的是 Responses 协议", launchers.unavailable.single().let { it.code to it.reason })
            for (refresh in listOf(false, true)) {
                server.enqueue(MockResponse().setBody("""{"launchers":[]}"""))
                api.assistantLauncherList("mac", refresh)
                val url = requireNotNull(server.takeRequest().requestUrl)
                assertEquals("/api/v1/machines/mac/launchers", url.encodedPath)
                assertEquals("assistant", url.queryParameter("purpose"))
                assertEquals("1".takeIf { refresh }, url.queryParameter("refresh"))
            }
        }
    }

    /** C12 (#4339, iOS `AssistantSidebar`): Rename…, Archived (N) and Unarchive on the routes iOS uses. */
    @Test fun renameUnarchiveAndTheArchivedListUseTheirRoutes() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()).forManager("work")
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.renameConversation(SessionKey("hub", "side-1"), "周报")
            val rename = server.takeRequest()
            assertEquals("POST", rename.method)
            assertEquals("/api/v1/sessions/hub/side-1/rename", rename.requestUrl?.encodedPath)
            assertEquals("""{"title":"周报"}""", rename.body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.unarchiveConversation(SessionKey("hub", "side-1"))
            val unarchive = server.takeRequest()
            assertEquals("POST", unarchive.method)
            assertEquals("/api/v1/assistant/conversations/side-1/unarchive", unarchive.requestUrl?.encodedPath)

            // Only the archived ones, never a health chat, each on the list's machine when it names none, newest first.
            server.enqueue(MockResponse().setBody("""{"machine_id":"hub","main":"main","conversations":[
                {"machine_id":"hub","session_id":"main","assistant":true,"updated_at":"2026-11-12T09:00:00Z"},
                {"session_id":"old","assistant":true,"archived":true,"updated_at":"2026-11-10T09:00:00Z"},
                {"machine_id":"hub","session_id":"health","assistant":true,"archived":true,"space":"health","updated_at":"2026-11-12T08:00:00Z"},
                {"machine_id":"hub","session_id":"new","assistant":true,"archived":true,"updated_at":"2026-11-11T09:00:00Z"}]}"""))
            val archived = api.archivedConversations()
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/v1/assistant", list.requestUrl?.encodedPath)
            assertEquals("1", list.requestUrl?.queryParameter("archived"))
            assertEquals("the assistant shown", "work", list.requestUrl?.queryParameter(ManagerQuery))
            assertEquals(listOf(SessionKey("hub", "new"), SessionKey("hub", "old")), archived.map { it.key })
        }
    }

    @Test fun alwaysGrantsAreListedAndRevokedPerConversationAndAMissingRouteShowsNothing() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            val session = SessionKey("demo", "side-1")
            val hash = "a".repeat(64)
            server.enqueue(MockResponse().setBody("""{"grants":[{"key_hash":"$hash","tool_name":"Bash","created_at":"2026-11-08T09:30:00Z"}]}"""))
            assertEquals(listOf(AlwaysGrant(hash, "Bash", "2026-11-08T09:30:00Z")), api.alwaysGrants(session))
            val list = server.takeRequest()
            assertEquals("GET", (list.method).zhAny())
            assertEquals("/api/v1/assistant/conversations/side-1/always", (list.path).zhAny())
            server.enqueue(MockResponse().setBody("""{"ok":true,"restart_required":true}"""))
            assertTrue(api.revokeAlwaysGrant(session, hash))
            val revoke = server.takeRequest()
            assertEquals("DELETE", (revoke.method).zhAny())
            assertEquals("/api/v1/assistant/conversations/side-1/always/$hash", (revoke.path).zhAny())
            server.enqueue(MockResponse().setBody("""{"ok":true,"restart_required":false}"""))
            assertFalse(api.revokeAlwaysGrant(session, hash))
            server.takeRequest()
            // A hub before the route, or a device without full access: nothing to show rather than an error.
            for (status in listOf(404, 403)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"no"}"""))
                assertNull(api.alwaysGrants(session))
                server.takeRequest()
            }
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"permission store unavailable"}"""))
            try { api.alwaysGrants(session); fail("A server failure must throw") } catch (_: HubFailure) { }
        }
    }

    @Test fun compactAndNewMainSendTheirBodiesAndTurnRefusalsIntoTheHubsWords() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.compactConversation(SessionKey("demo", "main-1"))
            val compact = server.takeRequest()
            assertEquals("POST", (compact.method).zhAny())
            assertEquals("/api/v1/assistant/conversations/main-1/compact", (compact.path).zhAny())
            assertEquals("""{"machine_id":"demo"}""", (compact.body.readUtf8()).zhAny())

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"busy","error":"正在回答，等这一轮结束再压缩"}"""))
            try { api.compactConversation(SessionKey("demo", "main-1")); fail("A refusal must throw") }
            catch (refused: AssistantRefused) {
                assertEquals("busy", (refused.code).zhAny())
                assertEquals("正在回答，等这一轮结束再压缩", (refused.message).zhAny())
            }
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"ok":true,"archived":"main-1","carried":true}"""))
            assertEquals(NewMainResult("main-1", true), api.newMainConversation(true, "main-1"))
            val created = server.takeRequest()
            assertEquals("/api/v1/assistant/main/new", (created.path).zhAny())
            assertEquals("""{"carry":"summary","expect":"main-1"}""", (created.body.readUtf8()).zhAny())

            server.enqueue(MockResponse().setBody("""{"ok":true,"archived":"","carried":false}"""))
            assertEquals(NewMainResult(), api.newMainConversation(false, null))
            assertEquals("""{"carry":"none"}""", (server.takeRequest().body.readUtf8()).zhAny())

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"main_changed","error":"主对话已经换过了，刷新后再试"}"""))
            try { api.newMainConversation(true, "main-1"); fail("A refusal must throw") }
            catch (refused: AssistantRefused) { assertEquals("main_changed", (refused.code).zhAny()) }
            server.takeRequest()

            // Anything else stays the hub failure it is.
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            try { api.newMainConversation(true, "main-1"); fail("A failure must throw") }
            catch (failure: HubFailure) { assertEquals(500, failure.status) }
        }
    }

    // Clear context (#5100, the hub's #5095): the main conversation on screen as `expect`, the one put away back; a 409 with
    // a code is the hub's refusal in its words, anything else the failure it is.
    @Test fun clearMainSendsTheExpectedConversationAndTurnsRefusalsIntoTheHubsWords() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"ok":true,"archived":"main-1"}"""))
            assertEquals("main-1", api.clearMainConversation("main-1"))
            val cleared = server.takeRequest()
            assertEquals("POST", cleared.method)
            assertEquals("/api/v1/assistant/main/clear", cleared.path)
            assertEquals("""{"expect":"main-1"}""", cleared.body.readUtf8())

            // No main conversation known: no `expect`, and nothing put away.
            server.enqueue(MockResponse().setBody("""{"ok":true,"archived":""}"""))
            assertEquals("", api.clearMainConversation(null))
            assertEquals("{}", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"main_changed","error":"主对话已经在别的设备上换过了"}"""))
            try { api.clearMainConversation("main-1"); fail("A refusal must throw") }
            catch (refused: AssistantRefused) {
                assertEquals("main_changed", refused.code)
                assertEquals("主对话已经在别的设备上换过了", refused.message)
            }
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            try { api.clearMainConversation("main-1"); fail("A failure must throw") }
            catch (failure: HubFailure) { assertEquals(500, failure.status) }
        }
    }

    @Test fun skillsReadTheAssistantsMachineAndTurnItsReasonIntoTheHubsWords() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"machine_id":"macbook","machine":"MacBook","skills":[
                {"name":"lark-gateway","description":"Feishu / Lark","source":"builtin"},
                {"name":"weekly-report","source":"user","extra":1},{"name":""}]}"""))
            assertEquals(AssistantSkills("macbook", "MacBook", listOf(AgentSkill("lark-gateway", "Feishu / Lark", "builtin"), AgentSkill("weekly-report", null, "user"))),
                api.skills())
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/api/v1/skills", get.path)

            server.enqueue(MockResponse().setBody("""{"machine_id":"macbook","skills":[]}"""))
            assertEquals(AssistantSkills("macbook", null, emptyList()), api.skills())
            server.takeRequest()
            server.enqueue(MockResponse().setBody("{}"))
            assertEquals(AssistantSkills(), api.skills())
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"conflict","error":"no machine runs the assistant"}"""))
            try { api.skills(); fail("A refusal must throw") }
            catch (refused: AssistantRefused) { assertEquals("no machine runs the assistant", refused.message) }
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"code":"offline","error":"machine is offline"}"""))
            try { api.skills(); fail("A refusal must throw") }
            catch (refused: AssistantRefused) { assertEquals("offline", refused.code) }
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"invalid_token","error":"invalid token"}"""))
            try { api.skills(); fail("A failure must throw") }
            catch (failure: HubFailure) { assertEquals(HubError.PairingExpired, failure.kind) }
        }
    }

    /** A missing search route is not an answer; a body that is not one fails so the app keeps its local filter; limit stays in 1..50. */
    @Test fun searchChatsTreats404AsUnsupportedAndABadBodyAsAFailureAndSendsABoundedLimit() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no such route"}"""))
            assertNull(api.searchChats("lane"))
            val missing = server.takeRequest()
            assertEquals("GET", missing.method)
            assertTrue(missing.path!!.startsWith("/api/v1/assistant/search?"))
            assertTrue(missing.path!!.contains("q=lane"))

            server.enqueue(MockResponse().setBody("not json"))
            try {
                api.searchChats("lane")
                fail("a body that is not the search answer must not become one")
            } catch (failure: HubFailure) {
                fail("a malformed body is not a missing route")
            } catch (_: Exception) { }
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"results":[{"session_id":"s","machine_id":"m","title":"Trip"}],"partial":true}"""))
            val answer = api.searchChats("  lane  ", limit = 0)
            val low = server.takeRequest()
            assertTrue(low.path!!.contains("q=lane"))
            assertTrue(low.path!!.contains("limit=1"))
            assertEquals("s", answer!!.results.single().sessionId)
            assertEquals("m", answer.results.single().machineId)
            assertTrue(answer.partial)

            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            assertTrue(api.searchChats("lane", limit = 80)!!.results.isEmpty())
            assertTrue(server.takeRequest().path!!.contains("limit=50"))
        }
    }

    @Test fun conversationSwitchReadsSegmentsAndSubmitsSwitchWithExactWireFields() = runBlocking {
        MockWebServer().use { server ->
            var current = true
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()) { current }
                .forManager("work")
            val session = SessionKey("mac/1", "chat/2")
            server.enqueue(MockResponse().setBody("""{"enabled":true,"epoch":4,"supervision_required":true}"""))
            assertEquals(ConversationSwitchState(enabled = true, epoch = 4, supervisionRequired = true), api.conversationSwitchState(session))
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/api/v1/assistant/conversations/chat%2F2/segments", get.requestUrl?.encodedPath)
            assertEquals("mac/1", get.requestUrl?.queryParameter("machine_id"))
            assertEquals("work", get.requestUrl?.queryParameter(ManagerQuery))
            assertEquals("work", get.getHeader(ManagerHeader))

            val request = ConversationSwitchRequest(
                operationId = "op-123",
                expectedEpoch = 4,
                sourceMachineId = "mac/1",
                machineId = "mac/1",
                launcher = "agy",
                model = "gemini-3",
                effort = "high",
                permissionMode = "acceptEdits",
            )
            server.enqueue(MockResponse().setBody("""{"operation_id":"op-123","phase":"preparing","epoch":4}"""))
            api.submitConversationSwitch(session, request)
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/api/v1/assistant/conversations/chat%2F2/switch", post.requestUrl?.encodedPath)
            assertEquals("work", post.requestUrl?.queryParameter(ManagerQuery))
            assertEquals("work", post.getHeader(ManagerHeader))
            assertEquals(
                buildJsonObject {
                    put("operation_id", "op-123")
                    put("expected_epoch", 4)
                    put("source_machine_id", "mac/1")
                    put("machine_id", "mac/1")
                    put("launcher", "agy")
                    put("model", "gemini-3")
                    put("effort", "high")
                    put("permission_mode", "acceptEdits")
                },
                JsonCodec.parseToJsonElement(post.body.readUtf8()),
            )

            // Blank optional fields are omitted rather than sent empty.
            server.enqueue(MockResponse().setBody("""{"operation_id":"op-124","phase":"preparing"}"""))
            api.submitConversationSwitch(
                session,
                ConversationSwitchRequest("op-124", 1, "mac/1", "mac/1", "codex", model = "", effort = null, permissionMode = "  "),
            )
            assertEquals(
                buildJsonObject {
                    put("operation_id", "op-124")
                    put("expected_epoch", 1)
                    put("source_machine_id", "mac/1")
                    put("machine_id", "mac/1")
                    put("launcher", "codex")
                },
                JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()),
            )

            // Definitive refusal preserves delivered = "none" and the hub's message.
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"busy","error":"Conversation is busy","delivered":"none"}"""))
            val refused = runCatching { api.submitConversationSwitch(session, request) }.exceptionOrNull() as HubFailure
            assertEquals(409, refused.status)
            assertEquals("busy", refused.code)
            assertEquals("none", refused.delivered)
            assertEquals("Conversation is busy", refused.message)
            server.takeRequest()

            // Uncertain failure preserves delivered = "unknown".
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"code":"timeout","error":"response unconfirmed","delivered":"unknown"}"""))
            val uncertain = runCatching { api.submitConversationSwitch(session, request) }.exceptionOrNull() as HubFailure
            assertEquals(502, uncertain.status)
            assertEquals("unknown", uncertain.delivered)
            server.takeRequest()

            // Stale pairing guard stops requests before sending.
            current = false
            assertTrue(runCatching { api.conversationSwitchState(session) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { api.submitConversationSwitch(session, request) }.exceptionOrNull() is CancellationException)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun cronAndStartGrantRoundTripPreviewAndRevoke() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient())
            server.enqueue(MockResponse().setBody("""{
                "tasks":[{
                    "id":"cron_1",
                    "title":"Weekday check",
                    "prompt":"Check build status",
                    "repeat":"cron",
                    "cron":"0 9 * * 1-5",
                    "cron_text":"At 09:00, Monday through Friday",
                    "next_runs":["2026-11-03T09:00:00+08:00","2026-11-04T09:00:00+08:00"],
                    "start_grant":{"kinds":["mac|/repo|edit","box|/srv/app|readonly"]},
                    "enabled":true
                }]
            }"""))
            val task = api.tasks().single()
            server.takeRequest()
            assertEquals(ScheduledTask.CRON, task.repeats)
            assertEquals("0 9 * * 1-5", task.cron)
            assertEquals("At 09:00, Monday through Friday", task.cronText)
            assertEquals(2, task.nextRuns.size)
            assertEquals(listOf("mac|/repo|edit", "box|/srv/app|readonly"), task.startGrant?.kinds)

            // Saving a cron task writes its cron expression; switching away writes explicit empty string.
            server.enqueue(MockResponse().setBody("""{"task":{"id":"cron_1","repeat":"cron","cron":"*/30 9-18 * * 1-5"}}"""))
            api.saveTask(task.copy(cron = "*/30 9-18 * * 1-5"))
            val cronPut = JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("cron", cronPut.getValue("repeat").jsonPrimitive.content)
            assertEquals("*/30 9-18 * * 1-5", cronPut.getValue("cron").jsonPrimitive.content)

            server.enqueue(MockResponse().setBody("""{"task":{"id":"cron_1","repeat":"daily","time":"09:00"}}"""))
            api.saveTask(task.copy(repeats = ScheduledTask.DAILY, time = "09:00", cron = null))
            val nonCronPut = JsonCodec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("daily", nonCronPut.getValue("repeat").jsonPrimitive.content)
            assertEquals("", nonCronPut.getValue("cron").jsonPrimitive.content)

            // Cron preview endpoint sends ?cron= query and surfaces 400 validation errors.
            server.enqueue(MockResponse().setBody("""{"cron":"0 9 * * 1-5","text":"At 09:00 on weekdays","timezone":"Asia/Shanghai","next_runs":["2026-11-03T01:00:00Z"]}"""))
            val preview = api.previewCron("0 9 * * 1-5")
            val previewReq = server.takeRequest()
            assertEquals("GET", previewReq.method)
            assertEquals("/api/v1/assistant/tasks/cron", previewReq.requestUrl?.encodedPath)
            assertEquals("0 9 * * 1-5", previewReq.requestUrl?.queryParameter("cron"))
            assertEquals("At 09:00 on weekdays", preview.text)
            assertEquals("Asia/Shanghai", preview.timezone)
            assertEquals(listOf("2026-11-03T01:00:00Z"), preview.nextRuns)

            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"cron must have 5 fields"}"""))
            val bad = runCatching { api.previewCron("bad") }.exceptionOrNull() as HubFailure
            assertEquals(400, bad.status)
            assertEquals("cron must have 5 fields", bad.message)
            server.takeRequest()

            // Start-grant revocation sends DELETE with optional kind body and returns updated task.
            server.enqueue(MockResponse().setBody("""{"task":{"id":"cron_1","repeat":"cron","cron":"0 9 * * 1-5","start_grant":{"kinds":["box|/srv/app|readonly"]}}}"""))
            val afterOne = api.revokeTaskStartGrant("cron_1", "mac|/repo|edit")
            val revokeReq = server.takeRequest()
            assertEquals("DELETE", revokeReq.method)
            assertEquals("/api/v1/assistant/tasks/cron_1/start-grant", revokeReq.requestUrl?.encodedPath)
            assertEquals("""{"kind":"mac|/repo|edit"}""", revokeReq.body.readUtf8())
            assertEquals(listOf("box|/srv/app|readonly"), afterOne.startGrant?.kinds)

            server.enqueue(MockResponse().setBody("""{"task":{"id":"cron_1","repeat":"cron","cron":"0 9 * * 1-5"}}"""))
            val afterAll = api.revokeTaskStartGrant("cron_1", null)
            val revokeAllReq = server.takeRequest()
            assertEquals("DELETE", revokeAllReq.method)
            assertEquals("", revokeAllReq.body.readUtf8())
            assertNull(afterAll.startGrant)
        }
    }

    @Test fun governanceTaskTrustDirectionNotesAndRepositoriesWireContracts() = runBlocking {
        assertTrue(isCurrentAssistantPath("https://hub.invalid/api/v1/task-trust".toHttpUrl(), "GET"))
        assertTrue(isCurrentAssistantPath("https://hub.invalid/api/v1/task-trust?key=a".toHttpUrl(), "DELETE"))

        MockWebServer().use { server ->
            val api = HttpAssistantManagement(server.url("/").toString(), "test-token", OkHttpClient()).forManager("work")

            // Task trust list & revoke + 404 unsupported.
            server.enqueue(MockResponse().setBody("""{"kinds":[{"key":"mac|/repo|edit","machine":"mac","cwd":"/repo","readonly":false,"ambiguous":true}]}"""))
            val kinds = api.taskTrust()
            val trustReq = server.takeRequest()
            assertEquals("/api/v1/task-trust", trustReq.requestUrl?.encodedPath)
            assertEquals("work", trustReq.getHeader(ManagerHeader))
            assertEquals(1, kinds.size)
            assertEquals("mac|/repo|edit", kinds.single().key)
            assertTrue(kinds.single().ambiguous)

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            api.revokeTaskTrust("mac|/repo|edit")
            val revokeTrust = server.takeRequest()
            assertEquals("DELETE", revokeTrust.method)
            assertEquals("mac|/repo|edit", revokeTrust.requestUrl?.queryParameter("key"))

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
            assertTrue(runCatching { api.taskTrust() }.exceptionOrNull() is GovernanceUnsupportedException)
            server.takeRequest()

            // Direction leaders + notes save (with explicit null notes_at) + 409 notes_changed conflict.
            server.enqueue(MockResponse().setBody("""{"leaders":[{"id":"dir-1","name":"Platform","project":"infra","ended":false,"notes":"Ship weekly","notes_at":"2026-11-01T10:00:00Z"},{"id":"dir-old","name":"Old","ended":true}]}"""))
            val leaders = api.leaders()
            server.takeRequest()
            assertEquals(2, leaders.size)
            assertEquals("Platform", leaders[0].displayName)
            assertEquals("2026-11-01T10:00:00Z", leaders[0].notesAt)

            server.enqueue(MockResponse().setBody("""{"notes":"New standing note","notes_at":"2026-11-02T11:00:00Z"}"""))
            val savedNote = api.updateDirectionNotes("dir-1", "New standing note", null)
            val noteReq = server.takeRequest()
            assertEquals("PUT", noteReq.method)
            assertEquals("/api/v1/assistant/directions/dir-1/notes", noteReq.requestUrl?.encodedPath)
            val noteBody = JsonCodec.parseToJsonElement(noteReq.body.readUtf8()).jsonObject
            assertEquals("New standing note", noteBody.getValue("notes").jsonPrimitive.content)
            assertTrue(noteBody["notes_at"] is JsonNull)
            assertEquals("2026-11-02T11:00:00Z", savedNote.notesAt)

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"notes_changed","error":"Notes updated elsewhere","notes":"Remote text","notes_at":"2026-11-03T12:00:00Z"}"""))
            val conflict = runCatching { api.updateDirectionNotes("dir-1", "Mine", "2026-11-01T10:00:00Z") }.exceptionOrNull() as DirectionNotesConflictException
            assertEquals("Remote text", conflict.currentNotes)
            assertEquals("2026-11-03T12:00:00Z", conflict.currentNotesAt)
            server.takeRequest()

            // Repositories list, providers (404 fallback), preview, create, update, delete, restore.
            server.enqueue(MockResponse().setBody("""{"manager":"work","catalog_revision":7,"repositories":[{"id":"repo_1","provider":"github","instance_url":"https://github.com","path":"acme/app","canonical_url":"https://github.com/acme/app","name":"App","aliases":["frontend"],"purpose":"Main app","task_workflow":"shared_branch"}]}"""))
            val catalog = api.repositories()
            server.takeRequest()
            assertEquals(7L, catalog.catalogRevision)
            assertEquals("acme/app", catalog.repositories.single().path)
            assertEquals("shared_branch", catalog.repositories.single().taskWorkflow)

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
            assertEquals(DefaultRepositoryProviders, api.repositoryProviders())
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"manager":"work","identity":{"provider":"github","instance_url":"https://github.com","path":"acme/api","canonical_url":"https://github.com/acme/api"}}"""))
            val preview = api.previewRepository("https://github.com/acme/api", "github")
            assertEquals("https://github.com/acme/api", preview.identity.canonicalUrl)
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"manager":"work","repository":{"id":"repo_2","provider":"github","instance_url":"https://github.com","path":"acme/api","canonical_url":"https://github.com/acme/api","name":"API"}}"""))
            val created = api.createRepository(RepositoryInput(url = "https://github.com/acme/api", provider = "github", name = "API", aliases = listOf("backend"), purpose = "API service", taskWorkflow = "isolated"))
            val createReq = server.takeRequest()
            assertEquals("POST", createReq.method)
            val createJson = JsonCodec.parseToJsonElement(createReq.body.readUtf8()).jsonObject
            assertEquals("isolated", createJson.getValue("task_workflow").jsonPrimitive.content)
            assertFalse(createJson.containsKey("clone_url"))
            assertEquals("repo_2", created.id)

            server.enqueue(MockResponse().setBody("""{"manager":"work","receipt":"rcpt_abc"}"""))
            val removed = api.deleteRepository("repo_2")
            assertEquals("rcpt_abc", removed.receipt)
            assertEquals("DELETE", server.takeRequest().method)

            server.enqueue(MockResponse().setBody("""{"manager":"work","repository":{"id":"repo_2","provider":"github","instance_url":"https://github.com","path":"acme/api","canonical_url":"https://github.com/acme/api","name":"API"}}"""))
            val restored = api.restoreRepository("repo_2", "rcpt_abc")
            val restoreReq = server.takeRequest()
            assertEquals("/api/v1/assistant/repositories/repo_2/restore", restoreReq.requestUrl?.encodedPath)
            assertEquals("""{"receipt":"rcpt_abc"}""", restoreReq.body.readUtf8())
            assertEquals("repo_2", restored.id)
        }
    }
}
