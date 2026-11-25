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
import persona.core.contracts.api.DeviceRelayCall
import persona.core.contracts.api.DeviceRelayCancel
import persona.core.contracts.api.DeviceRelayChannelRegistration
import persona.core.contracts.api.DeviceRelayResult
import persona.core.contracts.api.HubConnection

class HubDeviceRelayTransportTest {
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
    fun routesMultipleChannelsRejectsInvalidPayloadAndEnforcesPerChannelBusyQueue() = runBlocking {
        val socket = FakeSocket()
        val transport = HubDeviceRelayTransport(socket)

        assertFalse(transport.accept(buildJsonObject { put("type", "tool_call") }))

        // Valid call with missing payload defaults to empty object and routes strictly to its channel.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_call")
                    put("channel", "alpha")
                    put("call_id", "dr_a1")
                    put("deadline_ms", 5000)
                },
            ),
        )
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_call")
                    put("channel", "beta")
                    put("call_id", "dr_b1")
                    put("deadline_ms", 8000)
                    put("payload", buildJsonObject { put("ping", 1) })
                },
            ),
        )
        assertEquals(
            DeviceRelayCall("alpha", "dr_a1", 5000L, JsonObject(emptyMap())),
            transport.callsFor("alpha").first(),
        )
        assertEquals(
            DeviceRelayCall("beta", "dr_b1", 8000L, buildJsonObject { put("ping", 1) }),
            transport.callsFor("beta").first(),
        )

        // Non-object payload immediately replies invalid_params on the matching channel.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_call")
                    put("channel", "alpha")
                    put("call_id", "dr_bad")
                    put("payload", JsonArray(emptyList()))
                },
            ),
        )
        val badReply = socket.sent.single()
        assertEquals("device_relay_result", badReply.getValue("type").jsonPrimitive.content)
        assertEquals("alpha", badReply.getValue("channel").jsonPrimitive.content)
        assertEquals("dr_bad", badReply.getValue("call_id").jsonPrimitive.content)
        assertFalse(badReply.getValue("ok").jsonPrimitive.boolean)
        assertEquals("invalid_params", badReply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(JsonNull, badReply.getValue("result"))

        // Cancel frame routes to the matching channel's cancelsFor flow.
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_cancel")
                    put("channel", "beta")
                    put("call_id", "dr_b1")
                    put("reason", "timeout")
                },
            ),
        )
        assertEquals(DeviceRelayCancel("beta", "dr_b1", "timeout"), transport.cancelsFor("beta").first())

        // Per-channel queue overflow (> 8) replies busy on alpha while beta remains open.
        socket.sent.clear()
        for (i in 1..8) {
            assertTrue(
                transport.accept(
                    buildJsonObject {
                        put("type", "device_relay_call")
                        put("channel", "alpha")
                        put("call_id", "dr_q$i")
                    },
                ),
            )
        }
        assertTrue(socket.sent.isEmpty())
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_call")
                    put("channel", "alpha")
                    put("call_id", "dr_overflow")
                },
            ),
        )
        val busyReply = socket.sent.single()
        assertEquals("alpha", busyReply.getValue("channel").jsonPrimitive.content)
        assertEquals("dr_overflow", busyReply.getValue("call_id").jsonPrimitive.content)
        assertEquals("busy", busyReply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)

        // Beta still accepts calls independently of alpha's full queue.
        socket.sent.clear()
        assertTrue(
            transport.accept(
                buildJsonObject {
                    put("type", "device_relay_call")
                    put("channel", "beta")
                    put("call_id", "dr_b2")
                },
            ),
        )
        assertTrue(socket.sent.isEmpty())
        assertEquals("dr_b2", transport.callsFor("beta").first().callId)

        // Register, result, and unregister wire shapes.
        assertTrue(
            transport.register(
                DeviceRelayChannelRegistration(
                    channel = "phone_control",
                    deviceId = "dev_abc",
                    deviceName = "Device A",
                    appVersion = "1.4.0",
                    meta = buildJsonObject { put("ready", true) },
                ),
            ),
        )
        val regWire = socket.sent.single()
        assertEquals("device_relay_register", regWire.getValue("type").jsonPrimitive.content)
        assertEquals("phone_control", regWire.getValue("channel").jsonPrimitive.content)
        assertEquals("dev_abc", regWire.getValue("device_id").jsonPrimitive.content)
        assertTrue(regWire.getValue("meta").jsonObject.getValue("ready").jsonPrimitive.boolean)

        assertTrue(transport.result("phone_control", "dr_ok", DeviceRelayResult.ok(buildJsonObject { put("ok", true) })))
        val okWire = socket.sent.last()
        assertEquals("device_relay_result", okWire.getValue("type").jsonPrimitive.content)
        assertEquals("phone_control", okWire.getValue("channel").jsonPrimitive.content)
        assertTrue(okWire.getValue("ok").jsonPrimitive.boolean)
        assertEquals(JsonNull, okWire.getValue("error"))

        assertTrue(transport.unregister("phone_control"))
        val unregWire = socket.sent.last()
        assertEquals("device_relay_unregister", unregWire.getValue("type").jsonPrimitive.content)
        assertEquals("phone_control", unregWire.getValue("channel").jsonPrimitive.content)

        transport.discardPending("alpha")
        transport.close()
    }
}
