package persona.platform.phonetools.control

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import persona.core.contracts.api.PhoneControlPermissions
import persona.core.contracts.api.PhoneControlRuntimeInfo
import persona.core.contracts.api.PhoneControlSafetyPolicy

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
