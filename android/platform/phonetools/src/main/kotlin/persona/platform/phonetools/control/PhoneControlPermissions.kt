package persona.platform.phonetools.control

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Checks the core Android permissions plus notification posting and battery exemption
 * for the phone control shell and launches their corresponding system settings screens.
 */
object PhoneControlPermissionsChecker {

    fun check(context: Context): PhoneControlPermissions = PhoneControlPermissions(
        accessibility = isAccessibilityEnabled(context),
        notificationListener = isNotificationListenerEnabled(context),
        overlay = isOverlayGranted(context),
        screenCapture = false,
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
        postNotifications = isPostNotificationsGranted(context),
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

    fun isPostNotificationsGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationsEnabled = runCatching { nm?.areNotificationsEnabled() ?: true }.getOrDefault(true)
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return notificationsEnabled
        }
        val runtimeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && notificationsEnabled
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

    fun buildPostNotificationSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            val _ = putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }

    fun openPostNotificationSettings(context: Context) {
        val primary = buildPostNotificationSettingsIntent(context.packageName)
        val first = runCatching { launchSettings(context, primary) }
        if (first.isFailure) {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            launchSettings(context, fallback)
        }
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
