package persona.platform.phonetools.control.shell

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal enum class PrivilegedBackendKind(val wireName: String) {
    None("none"),
    BuiltinWirelessAdb("builtin_wireless_adb"),
    BuiltinLoopbackTcpAdb("builtin_loopback_tcp_adb"),
    Shizuku("shizuku"),
    ;

    companion object {
        fun fromWireNameOrDefault(raw: String?, fallback: PrivilegedBackendKind = BuiltinWirelessAdb): PrivilegedBackendKind =
            entries.firstOrNull { it.wireName == raw } ?: fallback
    }
}

internal data class ActivePrivilegedShell(
    val backend: PrivilegedBackendKind,
    val shell: IPhoneControlShell,
)

/**
 * Process-wide landing point for the privileged uid-2000 [IPhoneControlShell] Binder,
 * shared by the built-in wireless ADB helper, loopback TCP ADB fallback helper, and optional Shizuku UserService.
 */
internal class PrivilegedShellHub(context: Context) {
    private val app = context.applicationContext
    private val token = Binder()
    private val detachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val nonceStore: PrefsNonceStore by lazy { PrefsNonceStore(app) }
    private val nonces: HandoffNonces by lazy {
        HandoffNonces(nonceStore, SystemClock::elapsedRealtime)
    }

    @Volatile
    private var pendingLaunchBackend: PrivilegedBackendKind = PrivilegedBackendKind.BuiltinWirelessAdb

    private val _active = MutableStateFlow<ActivePrivilegedShell?>(null)
    val active: StateFlow<ActivePrivilegedShell?> = _active.asStateFlow()

    @Volatile
    var lastHandoffRejection: String? = null
        private set

    fun issueLaunchNonce(backend: PrivilegedBackendKind = PrivilegedBackendKind.BuiltinWirelessAdb): String {
        lastHandoffRejection = null
        pendingLaunchBackend = backend
        return nonces.issueLaunch()
    }

    fun forget() {
        nonces.clear()
        nonceStore.clearResumeBackend()
        pendingLaunchBackend = PrivilegedBackendKind.BuiltinWirelessAdb
        lastHandoffRejection = null
        val prev = synchronized(this) { _active.value.also { _active.value = null } }
        if (prev != null) {
            detachScope.launch { val _ = runCatching { prev.shell.destroy() } }
        }
    }

    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(ShellHandoff.KEY_BINDER)
        ShellHandoff.check(
            callingUid = callingUid,
            callingPid = callingPid,
            reportedUid = extras?.getInt(ShellHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(ShellHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(ShellHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(it) }

        val nonce = extras?.getString(ShellHandoff.KEY_NONCE)
        val slotsBefore = nonceStore.load()
        val isLaunchNonce = nonce != null &&
            slotsBefore.launch != null &&
            MessageDigest.isEqual(nonce.toByteArray(Charsets.UTF_8), slotsBefore.launch.toByteArray(Charsets.UTF_8))
        if (!nonces.matches(nonce)) return reject(ShellHandoff.Reject.BadNonce)

        val shell = IPhoneControlShell.Stub.asInterface(binder!!) ?: return reject(ShellHandoff.Reject.NoBinder)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(ShellHandoff.Reject.BinderElsewhere)
        }
        val next = nonces.accept(nonce) ?: return reject(ShellHandoff.Reject.BadNonce)
        val resolvedBackend = if (isLaunchNonce) {
            pendingLaunchBackend.also { nonceStore.saveResumeBackend(it) }
        } else {
            nonceStore.loadResumeBackend()
        }
        val entry = ActivePrivilegedShell(resolvedBackend, shell)
        try {
            binder.linkToDeath({
                synchronized(this@PrivilegedShellHub) {
                    if (_active.value === entry) _active.value = null
                }
            }, 0)
        } catch (_: Exception) {
            return reject(ShellHandoff.Reject.NoBinder)
        }
        lastHandoffRejection = null
        _active.value = entry
        Log.i(TAG, "ADB helper pid $callingPid attached (${resolvedBackend.wireName})")
        return Bundle().apply {
            putBoolean(ShellHandoff.KEY_ACCEPTED, true)
            putString(ShellHandoff.KEY_NEXT, next)
            putBinder(ShellHandoff.KEY_TOKEN, token)
        }
    }

    @Synchronized
    fun attachShizuku(binder: IBinder): IPhoneControlShell? {
        val shell = IPhoneControlShell.Stub.asInterface(binder) ?: return null
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || (ids[0] != ShellHandoff.SHELL_UID && ids[0] != 0)) {
            Log.w(TAG, "shizuku shell identity rejected")
            return null
        }
        val entry = ActivePrivilegedShell(PrivilegedBackendKind.Shizuku, shell)
        try {
            binder.linkToDeath({
                synchronized(this@PrivilegedShellHub) {
                    if (_active.value === entry) _active.value = null
                }
            }, 0)
        } catch (_: Exception) {
            return null
        }
        lastHandoffRejection = null
        _active.value = entry
        Log.i(TAG, "shizuku helper pid ${ids[1]} attached")
        return shell
    }

    fun detachShizuku(shell: IPhoneControlShell? = null) {
        val target = synchronized(this) {
            val current = _active.value
            if (current != null && current.backend == PrivilegedBackendKind.Shizuku &&
                (shell == null || current.shell === shell)
            ) {
                _active.value = null
                current.shell
            } else {
                null
            }
        }
        if (target != null) {
            detachScope.launch { val _ = runCatching { target.releaseAll() } }
        }
    }

    private fun reject(why: ShellHandoff.Reject): Bundle {
        lastHandoffRejection = why.name
        Log.w(TAG, "helper handoff rejected: ${why.name}")
        return Bundle().apply {
            putBoolean(ShellHandoff.KEY_ACCEPTED, false)
            putString(ShellHandoff.KEY_REASON, why.name)
        }
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load(): NonceSlots = NonceSlots(
            resume = prefs.getString("resume", null),
            launch = prefs.getString("launch", null),
            launchAt = prefs.getLong("launchAt", 0L),
        )

        override fun save(slots: NonceSlots) {
            val _ = prefs.edit()
                .putString("resume", slots.resume)
                .putString("launch", slots.launch)
                .putLong("launchAt", slots.launchAt)
                .commit()
        }

        fun loadResumeBackend(): PrivilegedBackendKind =
            PrivilegedBackendKind.fromWireNameOrDefault(prefs.getString(KEY_RESUME_BACKEND, null))

        fun saveResumeBackend(backend: PrivilegedBackendKind) {
            prefs.edit(commit = true) { putString(KEY_RESUME_BACKEND, backend.wireName) }
        }

        fun clearResumeBackend() {
            prefs.edit(commit = true) { remove(KEY_RESUME_BACKEND) }
        }
    }

    private companion object {
        const val TAG = "PersonaShellHub"
        const val PREFS = "persona_phone_control_helper_nonces"
        const val KEY_RESUME_BACKEND = "resume_backend"
    }
}
