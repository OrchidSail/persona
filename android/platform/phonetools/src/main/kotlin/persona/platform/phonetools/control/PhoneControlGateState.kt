package persona.platform.phonetools.control

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lock-free O(1) process-wide gate checked at the entry of AccessibilityService and
 * NotificationListenerService callbacks so disabled or idle state performs zero allocation.
 */
object PhoneControlGateState {
    private val masterEnabled = AtomicBoolean(false)
    private val otpAllowed = AtomicBoolean(true)

    val notificationBuffer: NotificationBuffer = NotificationBuffer()

    @Volatile
    internal var activeOverlay: TaskStatusOverlay? = null

    fun isMasterEnabled(): Boolean = masterEnabled.get()

    fun isOtpAllowed(): Boolean = otpAllowed.get()

    fun isOtpIngestionActive(): Boolean = masterEnabled.get() && otpAllowed.get()

    fun update(master: Boolean, otp: Boolean) {
        masterEnabled.set(master)
        otpAllowed.set(otp)
        if (!master || !otp) {
            notificationBuffer.clear()
        }
    }

    fun resetForShutdown() {
        masterEnabled.set(false)
        otpAllowed.set(true)
        notificationBuffer.clear()
        activeOverlay = null
    }
}
