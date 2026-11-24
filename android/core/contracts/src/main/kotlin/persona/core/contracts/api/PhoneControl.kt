package persona.core.contracts.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneControlPermissions(
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
    val overlay: Boolean = false,
    val screenCapture: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("accessibility", accessibility)
        put("notification_listener", notificationListener)
        put("overlay", overlay)
        put("screen_capture", screenCapture)
        put("battery_optimization_ignored", batteryOptimizationIgnored)
    }
}

data class PhoneControlSafetyPolicy(
    val allowNotificationOtpAutoLogin: Boolean = true,
    val blockRealPayment: Boolean = true,
    val allowNormalActionsDirectly: Boolean = true,
    val requirePhoneForBiometricOrCaptcha: Boolean = true,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("allow_notification_otp_auto_login", allowNotificationOtpAutoLogin)
        put("block_real_payment", blockRealPayment)
        put("allow_normal_actions_directly", allowNormalActionsDirectly)
        put("require_phone_for_biometric_or_captcha", requirePhoneForBiometricOrCaptcha)
    }
}

data class PhoneControlRuntimeInfo(
    val interactive: Boolean = false,
    val keyguardShowing: Boolean = false,
    val deviceLocked: Boolean = false,
    val overlayActive: Boolean = false,
    val virtualDisplayActive: Boolean = false,
    val residentConnection: Boolean = false,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("interactive", interactive)
        put("keyguard_showing", keyguardShowing)
        put("device_locked", deviceLocked)
        put("overlay_active", overlayActive)
        put("virtual_display_active", virtualDisplayActive)
        put("resident_connection", residentConnection)
    }
}

data class PhoneControlRegistration(
    val protocolVersion: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val platform: String = "android",
    val androidRelease: String,
    val androidSdk: Int,
    val appVersion: String,
    val enabled: Boolean,
    val ready: Boolean,
    val capabilities: List<String>,
    val permissions: PhoneControlPermissions,
    val safetyPolicy: PhoneControlSafetyPolicy,
    val runtime: PhoneControlRuntimeInfo,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("type", "register_phone_control")
        put("protocol_version", protocolVersion)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("manufacturer", manufacturer)
        put("model", model)
        put("platform", platform)
        put("android_release", androidRelease)
        put("android_sdk", androidSdk)
        put("app_version", appVersion)
        put("enabled", enabled)
        put("ready", ready)
        put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
        put("permissions", permissions.toWire())
        put("safety_policy", safetyPolicy.toWire())
        put("runtime", runtime.toWire())
    }
}

data class PhoneControlCall(
    val callId: String,
    val idempotencyKey: String,
    val op: String,
    val deadlineMs: Long,
    val args: JsonObject,
)

data class PhoneControlCancel(
    val callId: String,
    val reason: String,
)

data class PhoneControlError(
    val code: String,
    val message: String,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }
}

data class PhoneControlResult(
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: PhoneControlError? = null,
) {
    fun toWire(callId: String): JsonObject = buildJsonObject {
        put("type", "phone_control_result")
        put("call_id", callId)
        put("ok", ok)
        put("result", result ?: JsonNull)
        put("error", error?.toWire() ?: JsonNull)
    }

    companion object {
        fun ok(result: JsonObject): PhoneControlResult = PhoneControlResult(ok = true, result = result, error = null)
        fun error(code: String, message: String): PhoneControlResult =
            PhoneControlResult(ok = false, result = null, error = PhoneControlError(code, message))
    }
}

/** Bound to the same authenticated /api/v1/ws connection and pairing generation as HubConnection. */
interface PhoneControlTransport {
    val calls: Flow<PhoneControlCall>
    val cancels: Flow<PhoneControlCancel>
    fun discardPending()
    fun register(registration: PhoneControlRegistration): Boolean
    fun result(callId: String, result: PhoneControlResult): Boolean
}
