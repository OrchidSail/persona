package persona.core.contracts.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DeviceRelayChannelRegistration(
    val channel: String,
    val protocolVersion: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val appVersion: String = "",
    val meta: JsonObject = JsonObject(emptyMap()),
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("type", "device_relay_register")
        put("channel", channel)
        put("protocol_version", protocolVersion)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("platform", platform)
        if (appVersion.isNotEmpty()) {
            put("app_version", appVersion)
        }
        put("meta", meta)
    }
}

data class DeviceRelayCall(
    val channel: String,
    val callId: String,
    val deadlineMs: Long,
    val payload: JsonObject,
)

data class DeviceRelayCancel(
    val channel: String,
    val callId: String,
    val reason: String,
)

data class DeviceRelayError(
    val code: String,
    val message: String,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }
}

data class DeviceRelayResult(
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: DeviceRelayError? = null,
) {
    fun toWire(channel: String, callId: String): JsonObject = buildJsonObject {
        put("type", "device_relay_result")
        put("channel", channel)
        put("call_id", callId)
        put("ok", ok)
        put("result", result ?: JsonNull)
        put("error", error?.toWire() ?: JsonNull)
    }

    companion object {
        fun ok(result: JsonObject): DeviceRelayResult =
            DeviceRelayResult(ok = true, result = result, error = null)

        fun error(code: String, message: String): DeviceRelayResult =
            DeviceRelayResult(ok = false, result = null, error = DeviceRelayError(code, message))
    }
}

/**
 * Generic named relay channel transport bound to the same authenticated `/api/v1/ws` connection
 * and pairing generation as [HubConnection].
 */
interface DeviceRelayTransport {
    fun callsFor(channel: String): Flow<DeviceRelayCall>
    fun cancelsFor(channel: String): Flow<DeviceRelayCancel>
    fun discardPending(channel: String)
    fun register(registration: DeviceRelayChannelRegistration): Boolean
    fun unregister(channel: String): Boolean
    fun result(channel: String, callId: String, result: DeviceRelayResult): Boolean
}
