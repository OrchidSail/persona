package persona.platform.phonetools.control

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import persona.core.contracts.api.PhoneControlPermissions

/**
 * Checks the three core Android permissions plus battery exemption for the phone control shell
 * and launches their corresponding system settings screens.
 */
object PhoneControlPermissionsChecker {

    fun check(context: Context): PhoneControlPermissions = PhoneControlPermissions(
        accessibility = isAccessibilityEnabled(context),
        notificationListener = isNotificationListenerEnabled(context),
        overlay = isOverlayGranted(context),
        screenCapture = false,
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
    )

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (PhoneControlAccessibilityService.instance.value != null) return true
        val expected = ComponentName(context, PhoneControlAccessibilityService::class.java).flattenToString()
        val shortExpected = ComponentName(context, PhoneControlAccessibilityService::class.java).flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            it.equals(expected, ignoreCase = true) || it.equals(shortExpected, ignoreCase = true)
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        if (PhoneControlNotificationListener.isConnected.value) return true
        val expected = ComponentName(context, PhoneControlNotificationListener::class.java).flattenToString()
        val shortExpected = ComponentName(context, PhoneControlNotificationListener::class.java).flattenToShortString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.split(':').any {
            it.equals(expected, ignoreCase = true) || it.equals(shortExpected, ignoreCase = true)
        }
    }

    fun isOverlayGranted(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openAccessibilitySettings(context: Context) {
        launchSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openNotificationListenerSettings(context: Context) {
        launchSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        launchSettings(context, intent)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val first = runCatching { launchSettings(context, intent) }
        if (first.isFailure) {
            val _ = runCatching { launchSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun launchSettings(context: Context, intent: Intent) {
        if (context !is Activity) {
            val _ = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
