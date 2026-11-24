package persona.core.hub

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneControlCall
import persona.core.contracts.api.PhoneControlCancel
import persona.core.contracts.api.PhoneControlRegistration
import persona.core.contracts.api.PhoneControlResult
import persona.core.contracts.api.PhoneControlTransport

/**
 * Feeds `phone_control_call` and `phone_control_cancel` from the shared `/api/v1/ws` text hook
 * without ever mixing them into `register_phone.tools` or `tool_call`.
 */
class HubPhoneControlTransport(private val connection: HubConnection) : PhoneControlTransport, AutoCloseable {
    private val callQueue = Channel<PhoneControlCall>(QUEUE_CAPACITY)
    private val cancelQueue = Channel<PhoneControlCancel>(CANCEL_CAPACITY)

    override val calls = callQueue.receiveAsFlow()
    override val cancels = cancelQueue.receiveAsFlow()

    fun accept(message: JsonObject): Boolean {
        val type = (message["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return when (type) {
            "phone_control_call" -> {
                acceptCall(message)
                true
            }
            "phone_control_cancel" -> {
                acceptCancel(message)
                true
            }
            else -> false
        }
    }

    private fun acceptCall(message: JsonObject) {
        val callId = (message["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val op = (message["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val idempotencyKey = (message["idempotency_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val deadlineMs = ((message["deadline_ms"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_DEADLINE_MS)
            .coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)
        val args = when (val raw = message["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            else -> raw as? JsonObject
        }
        if (op.isEmpty() || args == null) {
            val _ = result(callId, PhoneControlResult.error("invalid_params", "op must be non-empty and args must be a JSON object"))
            return
        }
        val call = PhoneControlCall(
            callId = callId,
            idempotencyKey = idempotencyKey,
            op = op,
            deadlineMs = deadlineMs,
            args = args,
        )
        if (!callQueue.trySend(call).isSuccess) {
            val _ = result(callId, PhoneControlResult.error("busy", "Phone control queue is full; retry shortly"))
        }
    }

    private fun acceptCancel(message: JsonObject) {
        val callId = (message["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val reason = (message["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { "caller_cancelled" }
        val _ = cancelQueue.trySend(PhoneControlCancel(callId = callId, reason = reason))
    }

    override fun register(registration: PhoneControlRegistration): Boolean {
        require(registration.deviceId.isNotBlank())
        return connection.send(registration.toWire())
    }

    override fun result(callId: String, result: PhoneControlResult): Boolean =
        connection.send(result.toWire(callId))

    override fun discardPending() {
        while (callQueue.tryReceive().isSuccess) {
            // Discard queued calls from a previous socket generation.
        }
        while (cancelQueue.tryReceive().isSuccess) {
            // Discard stale cancel frames from a previous socket generation.
        }
    }

    override fun close() {
        val _ = callQueue.close()
        val _ = cancelQueue.close()
    }

    private companion object {
        const val QUEUE_CAPACITY = 8
        const val CANCEL_CAPACITY = 32
        const val DEFAULT_DEADLINE_MS = 15_000L
        const val MIN_DEADLINE_MS = 1_000L
        const val MAX_DEADLINE_MS = 60_000L
    }
}
