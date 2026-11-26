package persona.platform.phonetools.control.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import androidx.annotation.Keep

/**
 * Exported ContentProvider gated by `android.permission.INTERACT_ACROSS_USERS_FULL`
 * (held by shell uid 2000, never by ordinary apps). Routes verified attach calls to [PrivilegedShellHub].
 */
@Keep
class PhoneControlHandoffProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ShellHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return PrivilegedShellRegistry.get(ctx).hub.offer(
            extras = extras,
            callingUid = Binder.getCallingUid(),
            callingPid = Binder.getCallingPid(),
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
