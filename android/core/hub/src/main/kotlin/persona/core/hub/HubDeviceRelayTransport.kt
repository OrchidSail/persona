package persona.core.hub

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import persona.core.contracts.api.DeviceRelayCall
import persona.core.contracts.api.DeviceRelayCancel
import persona.core.contracts.api.DeviceRelayChannelRegistration
import persona.core.contracts.api.DeviceRelayResult
import persona.core.contracts.api.DeviceRelayTransport
import persona.core.contracts.api.HubConnection

/**
 * Feeds `device_relay_call` and `device_relay_cancel` from the shared `/api/v1/ws` text hook,
 * routing each frame strictly by its `channel` without ever touching `register_phone.tools` or `tool_call`.
 */
class HubDeviceRelayTransport(private val connection: HubConnection) : DeviceRelayTransport, AutoCloseable {
    private class ChannelQueues {
        val calls = Channel<DeviceRelayCall>(QUEUE_CAPACITY)
        val cancels = Channel<DeviceRelayCancel>(CANCEL_CAPACITY)
    }

    private val lock = Any()
    private val channels = LinkedHashMap<String, ChannelQueues>()
    private var closed = false

    private fun queuesFor(channel: String, createIfMissing: Boolean): ChannelQueues? = synchronized(lock) {
        if (closed) return null
        channels[channel]?.let { return it }
        if (!createIfMissing || channels.size >= MAX_CHANNELS) return null
        ChannelQueues().also { channels[channel] = it }
    }

    override fun callsFor(channel: String): Flow<DeviceRelayCall> {
        requireValidChannel(channel)
        return checkNotNull(queuesFor(channel, createIfMissing = true)) {
            "Cannot open relay channel queue for $channel"
        }.calls.receiveAsFlow()
    }

    override fun cancelsFor(channel: String): Flow<DeviceRelayCancel> {
        requireValidChannel(channel)
        return checkNotNull(queuesFor(channel, createIfMissing = true)) {
            "Cannot open relay cancel queue for $channel"
        }.cancels.receiveAsFlow()
    }

    fun accept(message: JsonObject): Boolean {
        val type = (message["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return when (type) {
            "device_relay_call" -> {
                acceptCall(message)
                true
            }
            "device_relay_cancel" -> {
                acceptCancel(message)
                true
            }
            else -> false
        }
    }

    private fun acceptCall(message: JsonObject) {
        val channel = (message["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (!CHANNEL_PATTERN.matches(channel)) return
        val callId = (message["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val deadlineMs = ((message["deadline_ms"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_DEADLINE_MS)
            .coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)
        val payload = when (val raw = message["payload"]) {
            null, JsonNull -> JsonObject(emptyMap())
            else -> raw as? JsonObject
        }
        if (payload == null) {
            val _ = result(channel, callId, DeviceRelayResult.error("invalid_params", "payload must be a JSON object"))
            return
        }
        val queues = queuesFor(channel, createIfMissing = true)
        if (queues == null) {
            val _ = result(channel, callId, DeviceRelayResult.error("channel_unavailable", "Relay channel limit reached or transport closed"))
            return
        }
        val call = DeviceRelayCall(
            channel = channel,
            callId = callId,
            deadlineMs = deadlineMs,
            payload = payload,
        )
        if (!queues.calls.trySend(call).isSuccess) {
            val _ = result(channel, callId, DeviceRelayResult.error("busy", "Device relay queue is full; retry shortly"))
        }
    }

    private fun acceptCancel(message: JsonObject) {
        val channel = (message["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (!CHANNEL_PATTERN.matches(channel)) return
        val callId = (message["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val reason = (message["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { "caller_cancelled" }
        val queues = queuesFor(channel, createIfMissing = true) ?: return
        val _ = queues.cancels.trySend(DeviceRelayCancel(channel = channel, callId = callId, reason = reason))
    }

    override fun register(registration: DeviceRelayChannelRegistration): Boolean {
        requireValidChannel(registration.channel)
        require(registration.deviceId.isNotBlank())
        return connection.send(registration.toWire())
    }

    override fun unregister(channel: String): Boolean {
        requireValidChannel(channel)
        discardPending(channel)
        return connection.send(
            buildJsonObject {
                put("type", "device_relay_unregister")
                put("channel", channel)
            },
        )
    }

    override fun result(channel: String, callId: String, result: DeviceRelayResult): Boolean {
        requireValidChannel(channel)
        require(callId.isNotBlank())
        return connection.send(result.toWire(channel, callId))
    }

    override fun discardPending(channel: String) {
        requireValidChannel(channel)
        val queues = queuesFor(channel, createIfMissing = false) ?: return
        while (queues.calls.tryReceive().isSuccess) {
            // Discard queued calls from a previous socket generation.
        }
        while (queues.cancels.tryReceive().isSuccess) {
            // Discard stale cancel frames from a previous socket generation.
        }
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            closed = true
            channels.values.toList().also { channels.clear() }
        }
        snapshot.forEach { queues ->
            val _ = queues.calls.close()
            val _ = queues.cancels.close()
        }
    }

    private companion object {
        val CHANNEL_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        const val MAX_CHANNELS = 16
        const val QUEUE_CAPACITY = 8
        const val CANCEL_CAPACITY = 32
        const val DEFAULT_DEADLINE_MS = 15_000L
        const val MIN_DEADLINE_MS = 1_000L
        const val MAX_DEADLINE_MS = 60_000L

        fun requireValidChannel(channel: String) {
            require(CHANNEL_PATTERN.matches(channel)) { "Invalid device relay channel: $channel" }
        }
    }
}
