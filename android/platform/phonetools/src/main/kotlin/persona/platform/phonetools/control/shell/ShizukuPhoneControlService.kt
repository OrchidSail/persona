package persona.platform.phonetools.control.shell

import android.content.Context
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.Surface
import androidx.annotation.Keep
import kotlin.system.exitProcess

@Keep
class ShizukuPhoneControlService : IPhoneControlShell.Stub {
    private val allowedUid: Int
    private val delegate: PhoneControlShellService

    @Keep
    constructor(context: Context) : super() {
        ensureMainLooper()
        allowedUid = resolveHostUid(context)
        delegate = PhoneControlShellService(allowedUid = allowedUid)
    }

    @Keep
    constructor() : super() {
        ensureMainLooper()
        allowedUid = resolveHostUid(null)
        delegate = PhoneControlShellService(allowedUid = allowedUid)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        delegate.createDisplay(surface, width, height, dpi)

    override fun releaseDisplay(displayId: Int) = delegate.releaseDisplay(displayId)

    override fun startApp(displayId: Int, packageName: String): Int =
        delegate.startApp(displayId, packageName)

    override fun topTaskId(displayId: Int): Int = delegate.topTaskId(displayId)

    override fun topTaskPackage(displayId: Int): String? = delegate.topTaskPackage(displayId)

    override fun moveTask(taskId: Int, displayId: Int): Boolean = delegate.moveTask(taskId, displayId)

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = delegate.injectTap(displayId, x, y)

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        delegate.injectStroke(displayId, xs, ys, times)

    override fun injectKey(displayId: Int, keyCode: Int): Boolean = delegate.injectKey(displayId, keyCode)

    override fun identity(): IntArray = delegate.identity()

    override fun releaseAll() = delegate.releaseAll()

    override fun destroy() {
        val _ = runCatching { delegate.releaseAll() }
        exitProcess(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_AIDL_TRANSACTION || code == DESTROY_RAW_TRANSACTION) {
            val caller = Binder.getCallingUid()
            if (caller != allowedUid && caller != Process.myUid() && caller != ShellHandoff.SHELL_UID && caller != 0) {
                throw SecurityException("caller $caller is not allowed")
            }
            val _ = runCatching { delegate.releaseAll() }
            reply?.writeNoException()
            exitProcess(0)
        }
        return delegate.onTransact(code, data, reply, flags)
    }

    private companion object {
        const val DESTROY_RAW_TRANSACTION = 16777114
        const val DESTROY_AIDL_TRANSACTION = FIRST_CALL_TRANSACTION + DESTROY_RAW_TRANSACTION

        fun ensureMainLooper() {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
        }

        fun resolveHostUid(context: Context?): Int {
            val fromCtx = runCatching {
                context?.applicationInfo?.uid?.takeIf {
                    it > 0 && it != ShellHandoff.SHELL_UID && it != Process.SYSTEM_UID
                }
            }.getOrNull()
            if (fromCtx != null) return fromCtx

            val pm = runCatching { context?.packageManager }.getOrNull() ?: ShellContext.create().packageManager
            val apkPath = System.getProperty("java.class.path")
                ?.split(':')
                ?.firstOrNull { it.endsWith(".apk") }
            if (apkPath != null) {
                val archivePkg = runCatching { pm.getPackageArchiveInfo(apkPath, 0)?.packageName }.getOrNull()
                if (!archivePkg.isNullOrBlank()) {
                    val uid = runCatching { pm.getApplicationInfo(archivePkg, 0).uid }.getOrNull()
                    if (uid != null && uid > 0) return uid
                }
            }
            val caller = Binder.getCallingUid()
            if (caller > 0 && caller != ShellHandoff.SHELL_UID && caller != Process.SYSTEM_UID) {
                return caller
            }
            return Process.myUid()
        }
    }
}
