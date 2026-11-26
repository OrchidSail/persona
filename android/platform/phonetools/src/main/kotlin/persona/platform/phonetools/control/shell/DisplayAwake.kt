package persona.platform.phonetools.control.shell

import java.lang.reflect.Method

/**
 * Keeps trusted virtual displays with their own display group awake so background rendering
 * and input dispatch never freeze after the system screen-off timeout. Never pokes DEFAULT_DISPLAY_GROUP.
 */
internal class DisplayAwake(
    private val poke: (Int) -> Boolean,
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile
    private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        const val EVENT_TOUCH = 2
        private const val DEFAULT_DISPLAY_GROUP = 0

        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            (flags and ownGroupFlag) != 0 && groupId != DEFAULT_DISPLAY_GROUP

        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf<Class<*>>(
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
    }
}
