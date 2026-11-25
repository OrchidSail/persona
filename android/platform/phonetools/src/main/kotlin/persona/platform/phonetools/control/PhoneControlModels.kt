package persona.platform.phonetools.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import persona.core.contracts.api.DeviceRelayError
import persona.core.contracts.api.DeviceRelayResult

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

data class PhoneControlCall(
    val callId: String,
    val idempotencyKey: String,
    val op: String,
    val deadlineMs: Long,
    val args: JsonObject,
) {
    companion object {
        fun fromRelayPayload(callId: String, deadlineMs: Long, payload: JsonObject): PhoneControlCall? {
            val op = (payload["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (op.isEmpty()) return null
            val idempotencyKey = (payload["idempotency_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val args = when (val raw = payload["args"]) {
                null, JsonNull -> JsonObject(emptyMap())
                else -> raw as? JsonObject ?: return null
            }
            return PhoneControlCall(
                callId = callId,
                idempotencyKey = idempotencyKey,
                op = op,
                deadlineMs = deadlineMs,
                args = args,
            )
        }
    }
}

data class PhoneControlError(
    val code: String,
    val message: String,
)

data class PhoneControlResult(
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: PhoneControlError? = null,
) {
    fun toRelayResult(): DeviceRelayResult = DeviceRelayResult(
        ok = ok,
        result = result,
        error = error?.let { DeviceRelayError(code = it.code, message = it.message) },
    )

    companion object {
        fun ok(result: JsonObject): PhoneControlResult = PhoneControlResult(ok = true, result = result, error = null)
        fun error(code: String, message: String): PhoneControlResult =
            PhoneControlResult(ok = false, result = null, error = PhoneControlError(code, message))
    }
}

fun buildPhoneControlRelayMeta(
    manufacturer: String,
    model: String,
    androidRelease: String,
    androidSdk: Int,
    ready: Boolean,
    capabilities: List<String>,
    permissions: PhoneControlPermissions,
    safetyPolicy: PhoneControlSafetyPolicy,
    runtime: PhoneControlRuntimeInfo,
): JsonObject = buildJsonObject {
    put("manufacturer", manufacturer)
    put("model", model)
    put("android_release", androidRelease)
    put("android_sdk", androidSdk)
    put("ready", ready)
    put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
    put("permissions", permissions.toWire())
    put("safety_policy", safetyPolicy.toWire())
    put("runtime", runtime.toWire())
}

/**
 * Phone-local safety gate switches; mirrored read-only to Hub and extensions.
 * Defaults:
 * - Notification/SMS OTP auto-login: ON by default.
 * - Real payment / fund transfer interception: ON by default (toggleable anytime on phone).
 * - Normal actions: always allowed directly.
 * - Biometrics / graphic slider CAPTCHAs: always require phone interaction.
 */
data class SafetyGateConfig(
    val allowNotificationOtpAutoLogin: Boolean = true,
    val blockRealPayment: Boolean = true,
) {
    fun toContract(): PhoneControlSafetyPolicy = PhoneControlSafetyPolicy(
        allowNotificationOtpAutoLogin = allowNotificationOtpAutoLogin,
        blockRealPayment = blockRealPayment,
        allowNormalActionsDirectly = true,
        requirePhoneForBiometricOrCaptcha = true,
    )

    fun toJson(): JsonObject = toContract().toWire()
}

/**
 * Lightweight UI projection rendered inside the floating overlay window during active tasks.
 */
data class OverlayTaskSummary(
    val taskId: String?,
    val status: String,
    val step: Int,
    val actionSummary: String,
    val isPaused: Boolean = false,
    val isWaitingOnPhone: Boolean = false,
    val isActive: Boolean = false,
)

/**
 * Snapshot of on-phone floating overlay control flags propagated back to Hub.
 */
data class OverlayControlState(
    val taskId: String? = null,
    val paused: Boolean = false,
    val takeoverRequested: Boolean = false,
    val stopRequested: Boolean = false,
    val resumedFromPhone: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("paused", paused)
        put("takeover_requested", takeoverRequested)
        put("stop_requested", stopRequested)
        put("resumed_from_phone", resumedFromPhone)
    }
}

data class ScreenshotPayload(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val scaleX: Double,
    val scaleY: Double,
    val bytes: Int,
    val dataBase64: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mime_type", mimeType)
        put("width", width)
        put("height", height)
        put("scale_x", scaleX)
        put("scale_y", scaleY)
        put("bytes", bytes)
        put("data_base64", dataBase64)
    }
}

data class OtpMeta(
    val available: Boolean,
    val otpId: String? = null,
    val packageName: String? = null,
    val sender: String? = null,
    val postedAtMs: Long? = null,
    val redactedPreview: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("available", available)
        if (available) {
            otpId?.let { put("otp_id", it) }
            packageName?.let { put("package_name", it) }
            sender?.let { put("sender", it) }
            postedAtMs?.let { put("posted_at_ms", it) }
            redactedPreview?.let { put("redacted_preview", it) }
        }
    }
}

data class DeviceControlSnapshot(
    val masterEnabled: Boolean,
    val interactive: Boolean,
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
    val secure: Boolean,
    val helperAvailable: Boolean,
    val userUnlocked: Boolean,
    val virtualDisplayActive: Boolean = false,
    val overlayActive: Boolean = false,
    val residentConnection: Boolean = false,
    val permissions: PhoneControlPermissions = PhoneControlPermissions(),
    val safetyPolicy: SafetyGateConfig = SafetyGateConfig(),
) {
    val ready: Boolean
        get() = masterEnabled && permissions.accessibility && helperAvailable && userUnlocked && !deviceLocked && !keyguardShowing

    val status: String
        get() = when {
            !masterEnabled -> "control_disabled"
            !permissions.accessibility || !helperAvailable -> "accessibility_disabled"
            !userUnlocked || deviceLocked || keyguardShowing -> "locked"
            ready -> "ready"
            else -> "not_ready"
        }

    val message: String
        get() = when (status) {
            "control_disabled" -> "Remote phone control is turned off on the phone."
            "accessibility_disabled" -> "Accessibility service is not enabled on the phone."
            "locked" -> "Phone screen is locked. Unlock the phone to run UI tasks."
            "ready" -> "Ready for remote phone control."
            else -> "Phone control shell is not ready."
        }

    fun toRuntimeInfo(): PhoneControlRuntimeInfo = PhoneControlRuntimeInfo(
        interactive = interactive,
        keyguardShowing = keyguardShowing,
        deviceLocked = deviceLocked,
        overlayActive = overlayActive,
        virtualDisplayActive = virtualDisplayActive,
        residentConnection = residentConnection,
    )

    fun toDeviceJson(): JsonObject = buildJsonObject {
        put("interactive", interactive)
        put("keyguard_showing", keyguardShowing)
        put("device_locked", deviceLocked)
        put("secure", secure)
        put("helper_available", helperAvailable)
        put("user_unlocked", userUnlocked)
        put("ready", ready)
        put("virtual_display_active", virtualDisplayActive)
        put("overlay_active", overlayActive)
        put("resident_connection", residentConnection)
    }

    fun toResultJson(activeTaskId: String, overlayControl: OverlayControlState): JsonObject = buildJsonObject {
        put("status", status)
        put("ready", ready)
        put("message", message)
        put("device", toDeviceJson())
        put("permissions", permissions.toWire())
        put("safety_policy", safetyPolicy.toJson())
        put("active_task_id", activeTaskId)
        put("overlay_control", overlayControl.toJson())
    }
}

fun interface DeviceControlStateProvider {
    fun snapshot(): DeviceControlSnapshot
}

class PhoneControlException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

internal fun JsonNullOrObject(obj: JsonObject?) = obj ?: JsonNull
