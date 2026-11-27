package persona.platform.phonetools.control.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.os.Bundle
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
    private val streamTouches = HashMap<Int, StreamTouchState>()
    private val launchLock = Any()

    private class StreamTouchState(
        var active: Boolean = false,
        var serverDownTime: Long = 0L,
        var lastEventTime: Long = 0L,
        var clientDownTime: Long = 0L,
        var lastX: Float = 0f,
        var lastY: Float = 0f,
    )

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
        val vd = synchronized(displays) {
            val _ = streamTouches.remove(displayId)
            displays.remove(displayId)
        }
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
        if (displayId != Display.DEFAULT_DISPLAY && !ownDisplay(displayId)) -1
        else tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskId failed", t)
        -1
    }

    override fun topTaskPackage(displayId: Int): String? = try {
        if (displayId != Display.DEFAULT_DISPLAY && !ownDisplay(displayId)) null
        else tasksOn(displayId).firstOrNull()?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull {
                (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskPackage failed", t)
        null
    }

    override fun captureMainForegroundTask(): String? = try {
        val task = tasksOn(Display.DEFAULT_DISPLAY).firstOrNull()
        if (task == null) {
            "-1||1"
        } else {
            val taskId = runCatching { TASK_INFO.getField("taskId").getInt(task) }.getOrDefault(-1)
            val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull {
                runCatching { (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName }.getOrNull()
            }.orEmpty()
            val activityType = runCatching { TASK_INFO.getField("topActivityType").getInt(task) }.getOrNull()
                ?: runCatching {
                    val cfg = TASK_INFO.getField("configuration").get(task)
                    val wc = cfg?.javaClass?.getField("windowConfiguration")?.get(cfg)
                    wc?.javaClass?.getMethod("getActivityType")?.invoke(wc) as? Int
                }.getOrNull()
            val isHome = activityType == ACTIVITY_TYPE_HOME || isDefaultLauncherPackage(pkg)
            formatMainForegroundTask(taskId, pkg, isHome)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "captureMainForegroundTask failed", t)
        null
    }

    @SuppressLint("MissingPermission")
    override fun restoreMainTask(taskId: Int): Boolean = try {
        if (taskId <= 0) {
            navigateMainDisplayHome()
        } else {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
            val fromRecents = runCatching {
                val m = taskManager.javaClass.getMethod("startActivityFromRecents", Int::class.java, Bundle::class.java)
                val res = m.invoke(taskManager, taskId, options) as Int
                res >= 0
            }.getOrDefault(false)
            if (fromRecents) {
                true
            } else {
                val moved = runCatching {
                    val am = context.getSystemService(ActivityManager::class.java)
                    if (am != null) {
                        am.moveTaskToFront(taskId, 0)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (moved) true else navigateMainDisplayHome()
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "restoreMainTask($taskId) failed", t)
        navigateMainDisplayHome()
    }

    override fun setDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        if (!ownDisplay(displayId) || surface == null || !surface.isValid) return false
        val vd = synchronized(displays) { displays[displayId] } ?: return false
        return try {
            vd.surface = surface
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplaySurface($displayId) failed", t)
            false
        }
    }

    override fun injectMotionEvent(
        displayId: Int,
        action: Int,
        downTime: Long,
        eventTime: Long,
        x: Float,
        y: Float,
        pressure: Float,
    ): Boolean {
        if (!ownDisplay(displayId)) return false
        val masked = action and MotionEvent.ACTION_MASK
        if (masked !in ALLOWED_STREAM_MOTION_ACTIONS) return false
        val now = SystemClock.uptimeMillis()
        val safePressure = pressure.takeIf { it.isFinite() }?.coerceIn(0.05f, 1f) ?: 1f
        val safeX = if (x.isFinite()) x.coerceAtLeast(0f) else 0f
        val safeY = if (y.isFinite()) y.coerceAtLeast(0f) else 0f

        var syntheticCancel: MotionEvent? = null
        val mainEvent: MotionEvent? = synchronized(displays) {
            if (displayId !in displays) return@synchronized null
            val st = streamTouches.getOrPut(displayId) { StreamTouchState() }
            when (masked) {
                MotionEvent.ACTION_DOWN -> {
                    if (st.active) {
                        val cancelTime = maxOf(st.lastEventTime, now)
                        syntheticCancel = obtainTouchEvent(
                            st.serverDownTime, cancelTime, MotionEvent.ACTION_CANCEL, st.lastX, st.lastY, 0.5f,
                        )
                    }
                    st.active = true
                    st.serverDownTime = now
                    st.lastEventTime = now
                    st.clientDownTime = downTime
                    st.lastX = safeX
                    st.lastY = safeY
                    obtainTouchEvent(st.serverDownTime, st.lastEventTime, MotionEvent.ACTION_DOWN, safeX, safeY, safePressure)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!st.active) return@synchronized null
                    val nextTime = sanitizeStreamEventTime(st.serverDownTime, st.lastEventTime, st.clientDownTime, eventTime, now)
                    st.lastEventTime = nextTime
                    st.lastX = safeX
                    st.lastY = safeY
                    obtainTouchEvent(st.serverDownTime, nextTime, MotionEvent.ACTION_MOVE, safeX, safeY, safePressure)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!st.active) return@synchronized null
                    val nextTime = sanitizeStreamEventTime(st.serverDownTime, st.lastEventTime, st.clientDownTime, eventTime, now)
                    st.lastEventTime = nextTime
                    st.lastX = safeX
                    st.lastY = safeY
                    st.active = false
                    obtainTouchEvent(st.serverDownTime, nextTime, masked, safeX, safeY, safePressure)
                }
                else -> null
            }
        }
        syntheticCancel?.let { cancelEv ->
            try {
                val _ = inject(cancelEv, displayId)
            } finally {
                cancelEv.recycle()
            }
        }
        val ev = mainEvent ?: return false
        return try {
            inject(ev, displayId)
        } finally {
            ev.recycle()
        }
    }

    private fun obtainTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun isDefaultLauncherPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
            resolved == pkg
        }.getOrDefault(false)
    }

    private fun navigateMainDisplayHome(): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return sendPair({ inject(down, Display.DEFAULT_DISPLAY) }, { inject(up, Display.DEFAULT_DISPLAY) }) {
            exec("input", "-d", "0", "keyevent", "${KeyEvent.KEYCODE_HOME}") == 0 ||
                exec("am", "start", "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_HOME) == 0
        }
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
            streamTouches.clear()
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
        private const val ACTIVITY_TYPE_HOME = 2

        private val ALLOWED_STREAM_MOTION_ACTIONS = setOf(
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
        )

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

        internal fun formatMainForegroundTask(taskId: Int, pkg: String, isHome: Boolean): String =
            "$taskId|${pkg.replace("|", "")}|${if (isHome) 1 else 0}"

        internal fun sanitizeStreamEventTime(
            serverDownTime: Long,
            lastEventTime: Long,
            clientDownTime: Long,
            clientEventTime: Long,
            now: Long,
        ): Long {
            val delta = (clientEventTime - clientDownTime).coerceIn(0L, 60_000L)
            val candidate = serverDownTime + delta
            return maxOf(lastEventTime, minOf(candidate, now + 50L))
        }
    }
}
