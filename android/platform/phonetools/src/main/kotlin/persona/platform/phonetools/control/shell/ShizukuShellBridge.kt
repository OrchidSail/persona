package persona.platform.phonetools.control.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

internal data class ShizukuStatusSnapshot(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    val ready: Boolean get() = running && permissionGranted
}

/**
 * Pure official Shizuku API compatibility bridge sharing [IPhoneControlShell] with built-in wireless ADB.
 */
internal class ShizukuShellBridge(
    context: Context,
    private val hub: PrivilegedShellHub,
    private val onStateChanged: () -> Unit,
) {
    private val app = context.applicationContext
    private val lock = Any()
    private var started = false
    private var bindRequested = false
    private var boundShell: IPhoneControlShell? = null

    private val _status = MutableStateFlow(ShizukuStatusSnapshot())
    val status: StateFlow<ShizukuStatusSnapshot> = _status.asStateFlow()

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        val versionCode = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            info.longVersionCode.toInt()
        }.getOrDefault(1)
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShizukuPhoneControlService::class.java.name))
            .daemon(false)
            .processNameSuffix("pcshizuku")
            .version(versionCode)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val _ = refresh()
        onStateChanged()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val _ = refresh()
        onStateChanged()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) {
            val _ = refresh()
            onStateChanged()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                synchronized(lock) { bindRequested = false }
                onStateChanged()
                return
            }
            val active = synchronized(lock) { bindRequested }
            if (!active) {
                val _ = runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
                return
            }
            val attached = hub.attachShizuku(binder)
            synchronized(lock) {
                if (attached != null && bindRequested) {
                    boundShell = attached
                } else {
                    bindRequested = false
                }
            }
            if (attached == null) {
                val _ = runCatching { Shizuku.unbindUserService(userServiceArgs, this, true) }
            }
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val prev = synchronized(lock) { boundShell.also { boundShell = null } }
            if (prev != null) hub.detachShizuku(prev)
            val _ = refresh()
            onStateChanged()
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val _ = refresh()
        val _ = runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        val _ = runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    fun refresh(): ShizukuStatusSnapshot {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = running || isPackageInstalled()
        val granted = running && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val next = ShizukuStatusSnapshot(installed = installed, running = running, permissionGranted = granted)
        _status.value = next
        return next
    }

    fun bindIfReady(): Boolean {
        val snap = refresh()
        if (!snap.ready) return false
        synchronized(lock) {
            if (boundShell != null) return true
            bindRequested = true
        }
        return runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
            true
        }.onFailure {
            Log.w(TAG, "Shizuku bindUserService failed", it)
            synchronized(lock) { bindRequested = false }
        }.getOrDefault(false)
    }

    fun unbind() {
        val (prev, shouldUnbind) = synchronized(lock) {
            val active = bindRequested || boundShell != null
            bindRequested = false
            val cur = boundShell
            boundShell = null
            cur to active
        }
        if (prev != null) hub.detachShizuku(prev)
        if (shouldUnbind) {
            val _ = runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    fun requestPermissionOrOpenApp(context: Context): Boolean {
        val snap = refresh()
        if (snap.running && !snap.permissionGranted) {
            val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
            if (!rationale) {
                val ok = runCatching { Shizuku.requestPermission(REQUEST_CODE); true }.getOrDefault(false)
                if (ok) return true
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        val _ = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun isPackageInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val _ = app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            val _ = app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "PersonaShizuku"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 1901
    }
}
