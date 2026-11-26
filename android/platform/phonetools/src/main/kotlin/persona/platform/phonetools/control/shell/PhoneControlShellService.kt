package persona.platform.phonetools.control.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

internal class PhoneControlShellService(
    private val allowedUid: Int,
) : IPhoneControlShell.Stub() {

    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val awake: DisplayAwake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "persona-vd-awake").apply { isDaemon = true }
        }
        DisplayAwake(::pokeDisplay) { r ->
            val future = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { val _ = future.cancel(false) }
        }
    }

    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "power") as IBinder
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplayInternal(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            Log.w(TAG, "createDisplay without trusted flags, retrying", t)
            try {
                createVirtualDisplayInternal(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay fallback failed", t2)
                -1
            }
        }
    }

    private fun createVirtualDisplayInternal(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("persona-agent-bg", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        setImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) {
            awake.start(id)
        }
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.w(TAG, "cannot inspect main-screen tasks", t)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        val _ = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            exec("am", "start", "--display", "$displayId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    override fun topTaskId(displayId: Int): Int = try {
        if (!ownDisplay(displayId)) -1
        else tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskId failed", t)
        -1
    }

    override fun topTaskPackage(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null
        else tasksOn(displayId).firstOrNull()?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull {
                (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskPackage failed", t)
        null
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        val _ = taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
                exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun makeEvent(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply {
                x = xs[i].toFloat()
                y = ys[i].toFloat()
                pressure = 1f
                size = 1f
            }
            return MotionEvent.obtain(
                down, down + times[i], action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = makeEvent(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) {
            val _ = send(i, MotionEvent.ACTION_MOVE)
        }
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId) || keyCode !in ALLOWED_KEYS) return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        val uid = topTaskUid(displayId)
        return if (uid != null) {
            sendPair({ injectTo(down, displayId, uid) }, { injectTo(up, displayId, uid) }) {
                sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
                    exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
                }
            }
        } else {
            sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun releaseAll() {
        awake.stopAll()
        val all = synchronized(displays) {
            displays.values.toList().also { displays.clear() }
        }
        all.forEach { val _ = runCatching { it.release() } }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            val _ = method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        val _ = runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            val _ = wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            val _ = exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1")
        }
    }

    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        val _ = runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            val _ = wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    private fun inUseOnMainScreen(packageName: String): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            val pkgs = packagesOf(task)
            packageName in pkgs && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    private fun packagesOf(task: Any): List<String> = listOf("topActivity", "baseActivity").mapNotNull { field ->
        runCatching { (TASK_INFO.getField(field).get(task) as? ComponentName)?.packageName }.getOrNull()
    }

    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun topTaskUid(displayId: Int): Int? = try {
        val task = tasksOn(displayId).firstOrNull() ?: return null
        val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
        val packageName = top?.packageName ?: return null
        val userId = TASK_INFO.getField("userId").getInt(task)
        resolveTargetUid(Build.VERSION.SDK_INT, packageName, userId) { pkg, user ->
            val pm = context.packageManager
            pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                .invoke(pm, pkg, 0, user) as Int
        }
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskUid failed on display $displayId", t)
        null
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        val _ = event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, injectMode(event is KeyEvent)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        val _ = event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    private fun exec(vararg cmd: String): Int {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1
        }
        val out = AtomicReference("")
        val reader = Thread { out.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            val _ = p.destroyForcibly()
            return -1
        }
        reader.join(1_000L)
        return p.exitValue()
    }

    companion object {
        private const val TAG = "PersonaShellSvc"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val IME_POLICY_LOCAL = 0
        private const val VIRTUAL_KEYBOARD = -1
        private const val USER_CURRENT = -2

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3

        internal const val INJECT_MODE_ASYNC = 0
        internal const val INJECT_MODE_WAIT_FOR_RESULT = 1
        internal const val INJECT_MODE_WAIT_FOR_FINISH = 2

        internal const val FLAG_PUBLIC = 1 shl 0
        internal const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        internal const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        internal const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        internal const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        internal const val FLAG_TRUSTED = 1 shl 10
        internal const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        internal const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        internal const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        internal const val FLAG_OWN_FOCUS = 1 shl 14
        internal const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        private val ALLOWED_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        )

        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        internal fun injectMode(isKey: Boolean): Int =
            if (isKey) INJECT_MODE_WAIT_FOR_RESULT else INJECT_MODE_ASYNC

        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        internal fun resolveTargetUid(sdkInt: Int, packageName: String?, userId: Int, uidLookup: (String, Int) -> Int): Int? =
            if (sdkInt < 33 || packageName == null) null else uidLookup(packageName, userId).takeIf { it >= 0 }
    }
}
