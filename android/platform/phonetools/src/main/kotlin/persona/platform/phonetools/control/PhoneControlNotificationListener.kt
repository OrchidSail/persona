package persona.platform.phonetools.control

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listens for incoming SMS/app push notifications so remote phone control can read verification codes
 * when BOTH the master remote control switch AND the local OTP auto-login switch are enabled.
 *
 * Short-circuits in O(1) with zero allocation whenever either switch is OFF.
 */
class PhoneControlNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        if (!PhoneControlGateState.isOtpIngestionActive()) return
        val _ = runCatching { activeNotifications?.forEach(::recordIfAllowed) }
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!PhoneControlGateState.isOtpIngestionActive()) return
        if (sbn != null) recordIfAllowed(sbn)
    }

    private fun recordIfAllowed(sbn: StatusBarNotification) {
        if (!PhoneControlGateState.isOtpIngestionActive()) return
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val text = bigText.ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        PhoneControlGateState.notificationBuffer.record(
            NotificationEntry(
                id = "${sbn.key}:${sbn.postTime}",
                packageName = sbn.packageName.orEmpty(),
                title = title,
                text = text,
                subText = subText,
                postedAtMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
