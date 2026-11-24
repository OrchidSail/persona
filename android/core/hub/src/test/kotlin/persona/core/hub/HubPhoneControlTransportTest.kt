package persona.core.hub

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.ServerEvent
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneControlCall
import persona.core.contracts.api.PhoneControlCancel
import persona.core.contracts.api.PhoneControlPermissions
import persona.core.contracts.api.PhoneControlRegistration
import persona.core.contracts.api.PhoneControlResult
import persona.core.contracts.api.PhoneControlRuntimeInfo
import persona.core.contracts.api.PhoneControlSafetyPolicy

class HubPhoneControlTransportTest {
    private class FakeSocket : HubConnection {
        val sent = mutableListOf<JsonObject>()
        override val state = MutableStateFlow(ConnectionState.Online)
        override val events = MutableSharedFlow<ServerEvent>()
        override fun reconnect() = Unit
        override fun send(message: JsonObject): Boolean {
            sent += message
            return true
        }
    }

    @Test
    fun parsesCallsCancelsRejectsInvalidArgsAndEnforcesBusyQueueLimit() = runBlocking {
        val socket = FakeSocket()
        val transport = HubPhoneControlTransport(socket)

        assertFalse(transport.accept(buildJsonObject { put("type", "tool_call") }))

        // Valid call with missing args defaults to empty object.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "phone_control_call")
                    put("call_id", "pc_1")
                    put("idempotency_key", "k1")
                    put("op", "device_state")
                    put("deadline_ms", 5000)
                },
            ),
        )
        assertEquals(
            PhoneControlCall("pc_1", "k1", "device_state", 5000L, JsonObject(emptyMap())),
            transport.calls.first(),
        )

        // Non-object args immediately returns invalid_params.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "phone_control_call")
                    put("call_id", "pc_bad")
                    put("op", "observe")
                    put("args", JsonArray(emptyList()))
                },
            ),
        )
        val badReply = socket.sent.single()
        assertEquals("phone_control_result", badReply.getValue("type").jsonPrimitive.content)
        assertEquals("pc_bad", badReply.getValue("call_id").jsonPrimitive.content)
        assertFalse(badReply.getValue("ok").jsonPrimitive.boolean)
        assertEquals("invalid_params", badReply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(JsonNull, badReply.getValue("result"))

        // Cancel frame forwards to cancels flow.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "phone_control_cancel")
                    put("call_id", "pc_1")
                    put("reason", "timeout")
                },
            ),
        )
        assertEquals(PhoneControlCancel("pc_1", "timeout"), transport.cancels.first())

        // Queue overflow (> 8) replies busy immediately.
        socket.sent.clear()
        for (i in 1..8) {
            assertTrue(
                transport.accept(
                    buildJsonObject {
                        put("type", "phone_control_call")
                        put("call_id", "pc_q$i")
                        put("op", "device_state")
                    },
                ),
            )
        }
        assertTrue(socket.sent.isEmpty())
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "phone_control_call")
                    put("call_id", "pc_overflow")
                    put("op", "device_state")
                },
            ),
        )
        val busyReply = socket.sent.single()
        assertEquals("pc_overflow", busyReply.getValue("call_id").jsonPrimitive.content)
        assertEquals("busy", busyReply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)

        // Registration and ok result wire shapes.
        socket.sent.clear()
        assertTrue(
            transport.register(
                PhoneControlRegistration(
                    deviceId = "dev_abc",
                    deviceName = "Samsung SM-A165F",
                    manufacturer = "samsung",
                    model = "SM-A165F",
                    androidRelease = "16",
                    androidSdk = 36,
                    appVersion = "1.2-overlay",
                    enabled = true,
                    ready = true,
                    capabilities = listOf("device_state", "observe"),
                    permissions = PhoneControlPermissions(accessibility = true),
                    safetyPolicy = PhoneControlSafetyPolicy(),
                    runtime = PhoneControlRuntimeInfo(interactive = true),
                ),
            ),
        )
        assertEquals("register_phone_control", socket.sent.single().getValue("type").jsonPrimitive.content)
        assertTrue(transport.result("pc_ok", PhoneControlResult.ok(buildJsonObject { put("ready", true) })))
        val okWire = socket.sent.last()
        assertTrue(okWire.getValue("ok").jsonPrimitive.boolean)
        assertEquals(JsonNull, okWire.getValue("error"))

        transport.discardPending()
        transport.close()
    }
}
