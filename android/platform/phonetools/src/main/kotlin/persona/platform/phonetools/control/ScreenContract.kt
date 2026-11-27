package persona.platform.phonetools.control

import kotlinx.serialization.Serializable

@Serializable
data class UiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val secret: Boolean = false,
    val placeholder: String? = null,
    val states: Set<String> = emptySet(),
) {
    fun containsPoint(x: Int, y: Int): Boolean {
        if (bounds.size < 4) return false
        return x in bounds[0]..bounds[2] && y in bounds[1]..bounds[3]
    }

    fun area(): Long {
        if (bounds.size < 4) return Long.MAX_VALUE
        val w = (bounds[2] - bounds[0]).coerceAtLeast(0).toLong()
        val h = (bounds[3] - bounds[1]).coerceAtLeast(0).toLong()
        return w * h
    }
}

data class ScreenshotCaptureResult(
    val payload: ScreenshotPayload? = null,
    val omittedReason: String? = null,
)

data class ScreenSnapshot(
    val snapshotId: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val topPackage: String?,
    val elements: List<UiElement>,
    val visibleTexts: List<String>,
    val hasPasswordField: Boolean,
    val uiHash: Long,
    val usingVirtualDisplay: Boolean = false,
    val imeVisible: Boolean = false,
    val windowDiagnostics: String? = null,
    val screenshot: ScreenshotPayload? = null,
    val screenshotOmittedReason: String? = null,
) {
    fun findRef(ref: String): UiElement? = elements.firstOrNull { it.ref == ref }

    /**
     * Resolves the innermost element covering physical pixel coordinates (x, y).
     * Used by `tap_xy` safety gate evaluation to prevent coordinate clicks from bypassing
     * payment or login confirmation gates.
     */
    fun findElementAt(x: Int, y: Int): UiElement? {
        val hits = elements.filter { it.containsPoint(x, y) }
        if (hits.isEmpty()) return null
        val labeled = hits.filter { it.name.isNotBlank() || !it.resourceId.isNullOrBlank() }
        val pool = if (labeled.isNotEmpty()) labeled else hits
        return pool.minWithOrNull(compareBy<UiElement>({ it.area() }, { if (it.clickable) 0 else 1 }))
    }

    fun toPromptSummary(maxElements: Int = 90): String {
        val sb = StringBuilder()
        val _ = sb.appendLine("Display: id=$displayId (${width}x$height, virtual=$usingVirtualDisplay, ime_visible=$imeVisible) | TopPackage: ${topPackage ?: "(unknown)"}")
        if (hasPasswordField) {
            val _ = sb.appendLine("Notice: Screen contains a password/secret input field (masked).")
        }
        val _ = sb.appendLine("Interactive & visible elements (${minOf(elements.size, maxElements)}/${elements.size}):")
        elements.take(maxElements).forEach { e ->
            val flags = buildList {
                if (e.clickable) { val _ = add("click") }
                if (e.editable) { val _ = add("edit") }
                if (e.scrollable) { val _ = add("scroll") }
                if (e.focused) { val _ = add("focused") }
                if (e.secret) { val _ = add("secret") }
                val _ = addAll(e.states)
            }.joinToString(",")
            val label = if (e.secret) "[secret]" else e.name.take(80)
            val res = e.resourceId?.let { " id=$it" }.orEmpty()
            val hint = e.placeholder?.takeIf { it.isNotBlank() }?.let { " hint=\"${it.take(40)}\"" }.orEmpty()
            val _ = sb.appendLine("${e.ref} [${e.role}] \"$label\" @(${e.centerX},${e.centerY}) [$flags]$res$hint")
        }
        return sb.toString()
    }
}

sealed interface ActionOutcome {
    data class Ok(val detail: String, val uiChanged: Boolean = true) : ActionOutcome
    data class Failed(val error: String) : ActionOutcome
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/**
 * Platform abstraction implemented by [AndroidScreenController] and faked in JVM unit tests.
 */
interface ScreenPort {
    suspend fun captureSnapshot(
        includeScreenshot: Boolean,
        screenshotMaxEdge: Int = 1080,
        screenshotQuality: Int = 70,
    ): ScreenSnapshot

    suspend fun tapRef(ref: String, snapshotId: String): ActionOutcome
    suspend fun tapXY(x: Int, y: Int): ActionOutcome
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionOutcome
    suspend fun longPressRef(ref: String, snapshotId: String): ActionOutcome
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320L): ActionOutcome
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionOutcome
    suspend fun inputText(ref: String?, snapshotId: String?, text: String, clearFirst: Boolean = true): ActionOutcome
    suspend fun pressBack(): ActionOutcome
    suspend fun pressHome(): ActionOutcome
    suspend fun pressRecents(): ActionOutcome
    suspend fun launchApp(packageOrName: String, preferVirtualDisplay: Boolean = false): ActionOutcome
    suspend fun listLaunchableApps(): List<LaunchableApp>
    suspend fun findLatestOtp(
        windowMs: Long = 180_000L,
        sinceMs: Long = 0L,
        appHint: String? = null,
        otpId: String? = null,
    ): ExtractedOtp?

    suspend fun consumeOtp(
        windowMs: Long = 180_000L,
        sinceMs: Long = 0L,
        appHint: String? = null,
        otpId: String? = null,
        consume: Boolean = true,
    ): ExtractedOtp?

    suspend fun fillVerificationCode(
        ref: String?,
        snapshotId: String?,
        otpId: String?,
        appHint: String?,
        sinceMs: Long,
        clearFirst: Boolean = true,
    ): ActionOutcome

    suspend fun waitMs(ms: Long)
    fun clearCaches() = Unit
    fun releaseVirtualDisplay() = Unit
    fun syncActiveDisplay(displayId: Int) = Unit
    fun isActuallyUsingVirtualDisplay(): Boolean = false
}
