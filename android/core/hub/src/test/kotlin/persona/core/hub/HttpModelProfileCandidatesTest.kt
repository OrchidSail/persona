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
import persona.core.contracts.api.ModelProfile
import persona.core.contracts.api.ModelProfileSave
import persona.core.contracts.api.ModelProfileSaveInput

class HttpModelProfileCandidatesTest {
    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/api/v1/model-profiles").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "ERR")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test fun profileCandidatesDecodesStatsAndReturnsNullOn404() = runBlocking {
        var seenPath = ""
        var seenMethod = ""
        val api = HttpModelConfiguration { path, method, _, _ ->
            seenPath = path
            seenMethod = method
            response(
                200,
                """
                {
                  "days": 14,
                  "tiers": {
                    "core": [
                      {
                        "index": 0,
                        "launcher": "claude",
                        "model": "opus",
                        "headroom": {"known": true, "percent": 72.4, "stale": true},
                        "stats": {"n": 9, "minutes_p50": 4.2, "first_pass": 0.78}
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
        val view = api.profileCandidates("performance")
        assertEquals("/api/v1/model-profiles/performance/candidates", seenPath)
        assertEquals("GET", seenMethod)
        requireNotNull(view)
        assertEquals(14, view.days)
        val status = view.tiers.getValue("core").single()
        assertEquals("claude", status.launcher)
        assertTrue(status.headroom.known)
        assertTrue(status.headroom.stale)
        assertEquals(72.4, status.headroom.percent, 0.01)
        assertEquals(9, status.stats.n)
        assertEquals(4.2, status.stats.minutesP50 ?: 0.0, 0.01)
        assertEquals(0.78, status.stats.firstPass ?: 0.0, 0.01)

        val oldHub = HttpModelConfiguration { _, _, _, _ -> response(404, """{"error":"not found"}""") }
        assertNull(oldHub.profileCandidates("performance"))
    }

    @Test fun saveProfileSendsCapturePayloadAndHandles409Conflict() = runBlocking {
        var sentBody = ""
        var sentPath = ""
        val api = HttpModelConfiguration { path, method, body, _ ->
            sentPath = "$method $path"
            sentBody = Buffer().also { body?.writeTo(it) }.readUtf8()
            response(200, """{"revision":4,"active":"performance","profiles":[]}""")
        }
        val capture = ModelProfileSaveInput.capture(ModelProfile(id = "performance", name = "Performance"), revision = 3)
        val result = api.saveProfile("performance", capture)
        assertEquals("PUT /api/v1/model-profiles/performance", sentPath)
        assertTrue(sentBody.contains("\"capture\":true"))
        assertTrue(sentBody.contains("\"revision\":3"))
        assertTrue(result is ModelProfileSave.Saved)

        val conflictApi = HttpModelConfiguration { _, _, _, _ -> response(409, """{"error":"conflict"}""") }
        assertEquals(ModelProfileSave.Conflict, conflictApi.saveProfile("performance", capture))
    }
}
