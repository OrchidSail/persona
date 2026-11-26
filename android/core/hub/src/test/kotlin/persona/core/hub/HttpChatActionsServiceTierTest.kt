package persona.core.hub

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import persona.core.contracts.JsonCodec
import persona.core.contracts.Session
import persona.core.contracts.api.ChatAction
import persona.core.contracts.api.ChatActionResult

class HttpChatActionsServiceTierTest {
    private val session = Session(machineId = "mac", sessionId = "s1", status = "idle", agent = "codex")

    private fun okResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/api/v1/sessions/mac/s1/modes").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    @Test fun serviceTierSendsSoleKeyViaSlowTransportAndRejectsCombinedModes() = runBlocking {
        var usedSlow = false
        var sentBody = ""
        val actions = HttpChatActions(
            slowRequest = { path, method, body ->
                usedSlow = true
                assertEquals("/api/v1/sessions/mac/s1/modes", path)
                assertEquals("POST", method)
                sentBody = Buffer().also { body?.writeTo(it) }.readUtf8()
                okResponse()
            },
            request = { _, _, _ -> error("must use slow transport for service_tier") },
        )

        val res = actions.execute(session, ChatAction.Modes(serviceTier = "priority"))
        assertEquals(ChatActionResult.Done, res)
        assertTrue(usedSlow)
        assertEquals("""{"service_tier":"priority"}""", sentBody)

        // Combined keys fail fast before sending anything over the wire
        val invalid = actions.execute(session, ChatAction.Modes(effort = "high", serviceTier = "priority"))
        assertTrue(invalid is ChatActionResult.Failed)
    }

    @Test fun modesAppliedAndSessionDecodeHandleServiceTierFields() {
        val raw = """
            {
              "machine_id": "mac",
              "session_id": "s1",
              "status": "idle",
              "agent": "codex",
              "service_tier": "priority",
              "service_tiers": [
                {"id": "default", "name": "Standard"},
                {"id": "priority", "name": "Fast"}
              ]
            }
        """.trimIndent()
        val decoded = JsonCodec.decodeFromString<Session>(raw)
        assertEquals("priority", decoded.serviceTier)
        assertEquals(2, decoded.serviceTiers.size)
        assertTrue(modesApplied(decoded, ChatAction.Modes(serviceTier = "priority")))
        assertFalse(modesApplied(decoded, ChatAction.Modes(serviceTier = "default")))
    }
}
