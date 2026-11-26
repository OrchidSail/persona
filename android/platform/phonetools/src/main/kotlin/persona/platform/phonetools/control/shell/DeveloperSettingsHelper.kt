package persona.platform.phonetools.control.shell

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

internal object DeveloperSettingsHelper {
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    val wirelessDebuggingUri: Uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)

    fun isWirelessDebuggingEnabled(cr: ContentResolver): Boolean? = runCatching {
        Settings.Global.getString(cr, ADB_WIFI_ENABLED)?.let { it == "1" }
    }.getOrNull()

    fun openWirelessDebuggingSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY)
                .putExtra(
                    EXTRA_SHOW_FRAGMENT_ARGS,
                    Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY) },
                ),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            if (context !is Activity) {
                val _ = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }
}
