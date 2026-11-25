package persona.core.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.Injected
import persona.core.contracts.Item
import persona.core.contracts.JsonCodec

class InjectedMessagesTest {
    @Test fun sharedFixtures() {
        val cases = FixtureReader.read("injected")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.value.getValue("name").jsonPrimitive.content
            val text = case.value.getValue("input").jsonObject.getValue("text").jsonPrimitive.content
            val expected = case.value.getValue("expected").let {
                if (it is JsonNull) null else JsonCodec.decodeFromJsonElement(Injected.serializer(), it)
            }
            assertEquals(name, expected, parseInjected(text))
        }
    }

    private val notice = "🔔 任务「跑测试」做完了\n（这是 Persona 的任务通知，不是用户发的消息。）\n\nAgent 最后说的：\n好了"

    @Test fun hubMarkFirst() {
        val mark = Injected("task_notice", "done", "x", todoId = "td_1", line = "任务「x」做完了")
        assertEquals("td_1", (injectedOf(Item("1", role = "user", kind = "text", text = notice, injected = mark))?.todoId).zhAny())
    }

    @Test fun olderHubReadOffTheText() {
        assertEquals("任务「跑测试」做完了", (injectedOf(Item("1", role = "user", kind = "text", text = notice))?.line).zhAny())
    }

    @Test fun leadingPersonaSnapshotsAreSkipped() {
        val text = "<persona-context>\n实时快照\n</persona-context>\n<persona-context kind=\"context\" title=\"t\">x</persona-context>\n$notice"
        assertEquals("任务「跑测试」做完了", parseInjected(text)?.line)
    }

    @Test fun sentFromAnAppStaysTheUsers() {
        assertNull(injectedOf(Item("1", role = "user", kind = "text", text = notice, clientMsgId = "c1")))
        assertNull(injectedOf(Item("1", role = "assistant", kind = "text", text = notice)))
    }

    @Test fun decodesTheWireField() {
        val item = JsonCodec.decodeFromString(Item.serializer(),
            """{"id":"1","role":"user","kind":"text","text":"x","injected":{"kind":"followup","state":"daily","title":"早报","line":"定时任务「早报」开始运行"}}""")
        assertEquals("daily", (item.injected?.state).zhAny())
    }

    @Test fun decodesTheSupervisionLevel() {
        // The hub marks user-visible supervision with level="user" (server/internal/prompts/injected.go); older hubs omit it.
        val marked = JsonCodec.decodeFromString(Item.serializer(),
            """{"id":"1","role":"user","kind":"text","text":"x","injected":{"kind":"system","state":"supervise","level":"user","line":"督办：2 个派出去的任务要处理"}}""")
        assertEquals("user", marked.injected?.level)
        val oldHub = JsonCodec.decodeFromString(Item.serializer(),
            """{"id":"1","role":"user","kind":"text","text":"x","injected":{"kind":"system","state":"supervise","line":"督办"}}""")
        assertNull(oldHub.injected?.level)
    }

    @Test fun supervisionFallbackRecognizesUnmarkedSuperviseMessages() {
        val triage = "📋 督办：你派出去的任务有 2 个要你处理\n（这是 Persona 的督办消息，不是用户发的消息。）\n<persona-supervision level=\"triage\" />"
        val parsed = parseInjected(triage)
        assertEquals(Injected("system", "supervise", line = "督办：2 个派出去的任务要处理", level = "triage"), parsed)
        assertEquals(parsed, injectedOf(Item("1", role = "user", kind = "text", text = triage)))
        assertEquals("supervision", sourceOf(Item("1", role = "user", kind = "text", text = triage))?.kind)

        val userLevel = "📋 督办：你派出去的任务有 1 个要你处理\n（这是 Persona 的督办消息）\n<persona-supervision level=\"user\" />"
        assertEquals("user", parseInjected(userLevel)?.level)

        val legacy = "<persona-context>snapshot</persona-context>\n📋 督办：你派出去的任务有 3 个要你处理\n（这是 Persona 的督办消息）"
        assertEquals("triage", parseInjected(legacy)?.level)

        // Without the marker, or when sent by an app/peer/protected user context, it stays an ordinary user message.
        assertNull(parseInjected("📋 督办：你派出去的任务有 2 个要你处理\nno marker"))
        assertNull(injectedOf(Item("2", role = "user", kind = "text", text = triage, clientMsgId = "app-1")))
        assertNull(sourceOf(Item("2", role = "user", kind = "text", text = triage, clientMsgId = "app-1")))
        val protected = "<persona-context kind=\"message-source\">{\"author\":\"user\"}</persona-context>\n$triage"
        assertNull(injectedOf(Item("3", role = "user", kind = "text", text = protected)))
        assertNull(sourceOf(Item("3", role = "user", kind = "text", text = protected)))
    }
}

class InjectedTaskTest {
    private fun todo(id: String, origin: String, at: String) =
        persona.core.contracts.Todo(id = id, title = "跑测试", origin = origin, createdAt = at)
    private val n = Injected("task_notice", "done", "跑测试", line = "任务「跑测试」做完了")

    @Test fun hubIdFirst() = assertEquals("td_9", (taskOfInjected(n.copy(todoId = "td_9"), emptyList(), "main")).zhAny())

    @Test fun newestOfThatTitleFromThisConversation() {
        val list = listOf(todo("a", "main", "2026-11-01T00:00:00Z"), todo("b", "main", "2026-11-04T00:00:00Z"), todo("c", "other", "2026-11-05T00:00:00Z"))
        assertEquals("b", (taskOfInjected(n, list, "main")).zhAny())
        assertNull(taskOfInjected(n, list, "none"))
    }
}
