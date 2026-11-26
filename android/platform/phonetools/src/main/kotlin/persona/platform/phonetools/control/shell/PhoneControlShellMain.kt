package persona.platform.phonetools.control.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

@Keep
object PhoneControlShellMain {
    private const val TAG = "PersonaShellMain"

    @JvmStatic
    @Keep
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: PhoneControlShellMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        val _ = runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val service = PhoneControlShellService(allowedUid = appUid)
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    private class Helper(
        private val service: PhoneControlShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = ShellHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        private val worker = Handler(HandlerThread("persona-shell-handoff").apply { start() }.looper)
        private var nonce: String? = null
        private var appToken: IBinder? = null
        private var retrying = false

        fun start(launchNonce: String) {
            val _ = worker.post {
                when (deliver(launchNonce)) {
                    Outcome.Accepted -> {
                        killOtherInstances()
                        watchUid()
                    }
                    Outcome.Rejected -> exitProcess(0)
                    Outcome.Failed -> exitProcess(1)
                }
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName signing mismatch; exiting")
                val _ = runCatching { service.releaseAll() }
                exitProcess(0)
            }
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(ShellHandoff.KEY_NONCE, n)
                    putBinder(ShellHandoff.KEY_BINDER, service)
                    putInt(ShellHandoff.KEY_UID, Process.myUid())
                    putInt(ShellHandoff.KEY_PID, Process.myPid())
                    putString(ShellHandoff.KEY_APK, System.getProperty("java.class.path"))
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return Outcome.Failed
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, ShellHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(ShellHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(ShellHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(ShellHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(ShellHandoff.KEY_NEXT)
                appToken = appBinder
                try {
                    appBinder.linkToDeath({ val _ = worker.post { onAppDied(appBinder) } }, 0)
                } catch (_: Throwable) {
                    val _ = worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                val _ = runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser",
                        String::class.java,
                        IBinder::class.java,
                        Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            val _ = runCatching { service.releaseAll() }
            if (!installed()) exitProcess(0)
            Log.i(TAG, "$packageName exited; released virtual displays and awaiting reconnect")
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            val _ = context.packageManager.javaClass.getMethod(
                "getPackageUidAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is PackageManager.NameNotFoundException
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            val _ = runCatching {
                val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                    .apply { isAccessible = true }.newInstance(context, worker)
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(PhoneControlShellMain::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> {
                            onUidImportance(a[0] as Int, a[1] as Int)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "PersonaShellUidListener"
                        else -> null
                    }
                }
                val _ = ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            val _ = worker.post { if (appToken == null && !retrying) redeliver(0) }
        }

        private fun redeliver(attempt: Int) {
            if (appToken != null) return
            when (deliver(nonce)) {
                Outcome.Accepted -> retrying = false
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> {
                    retrying = attempt + 1 < RETRY_DELAYS_MS.size
                    if (retrying) {
                        val _ = worker.postDelayed({ redeliver(attempt + 1) }, RETRY_DELAYS_MS[attempt + 1])
                    }
                }
            }
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (userId == 0) {
                pm.getPackageInfo(packageName, flags)
            } else {
                pm.javaClass.getMethod(
                    "getPackageInfoAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(pm, packageName, flags, userId) as PackageInfo
            }
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && ShellHandoff.certPin(signers) == certPin
        }.getOrDefault(false)

        private fun killOtherInstances() {
            val me = Process.myPid()
            val name = ShellHandoff.processName(packageName)
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val argv0 = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == name) {
                    Process.killProcess(pid)
                }
            }
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private val RETRY_DELAYS_MS = longArrayOf(0L, 1_000L, 3_000L, 10_000L)
        }
    }
}
