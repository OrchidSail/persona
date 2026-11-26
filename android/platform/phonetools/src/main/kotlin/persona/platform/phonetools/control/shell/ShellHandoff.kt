package persona.platform.phonetools.control.shell

import java.security.MessageDigest
import java.security.SecureRandom

internal object ShellHandoff {
    const val SHELL_UID = 2000
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "persona.platform.phonetools.control.shell.PhoneControlShellMain"

    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"

    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String): String = "$packageName.phonecontrol.helper"

    fun processName(packageName: String): String = "$packageName:pcshell"

    /**
     * Command executed via adbd `exec:` service. Nonce is delivered on stdin so it never appears
     * in `/proc/<pid>/cmdline`. `setsid` detaches the helper from the adbd session group while
     * `read _` keeps the shell open until handoff completes.
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c ->
            MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    enum class Reject {
        NotShell,
        IdMismatch,
        StaleApk,
        NoBinder,
        BadNonce,
        BinderElsewhere,
    }

    fun check(
        callingUid: Int,
        callingPid: Int,
        reportedUid: Int,
        reportedPid: Int,
        helperApk: String?,
        appApk: String,
        hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

internal data class NonceSlots(
    val resume: String? = null,
    val launch: String? = null,
    val launchAt: Long = 0L,
)

internal interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

internal class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also {
        store.save(store.load().copy(launch = it, launchAt = clock()))
    }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        return same(nonce, s.resume) || same(nonce, launch)
    }

    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
    }

    private fun same(a: String, b: String?): Boolean =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val LAUNCH_TTL_MS = 30_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
