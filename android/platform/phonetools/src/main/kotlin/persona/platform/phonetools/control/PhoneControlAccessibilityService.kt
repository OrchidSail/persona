package persona.platform.phonetools.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure JPEG screenshot scaler and hard 2 MiB size guard shared by API 30+ accessibility capture
 * and optional lazy VirtualDisplay capture.
 */
internal object ScreenshotEncoder {
    const val DEFAULT_MAX_EDGE = 1080
    const val MIN_MAX_EDGE = 360
    const val MAX_MAX_EDGE = 1440
    const val DEFAULT_QUALITY = 70
    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 90
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val FALLBACK_MAX_EDGE = 720
    private const val FALLBACK_QUALITY = 55

    fun encodeJpeg(
        source: Bitmap,
        requestedMaxEdge: Int = DEFAULT_MAX_EDGE,
        requestedQuality: Int = DEFAULT_QUALITY,
        maxBytes: Int = MAX_BYTES,
    ): ScreenshotCaptureResult {
        val origW = source.width
        val origH = source.height
        if (origW <= 0 || origH <= 0) {
            return ScreenshotCaptureResult(omittedReason = "empty_bitmap")
        }
        val clampedEdge = requestedMaxEdge.coerceIn(MIN_MAX_EDGE, MAX_MAX_EDGE)
        val clampedQuality = requestedQuality.coerceIn(MIN_QUALITY, MAX_QUALITY)

        val first = compressScaled(source, origW, origH, clampedEdge, clampedQuality)
        if (first != null && first.bytes <= maxBytes) {
            return ScreenshotCaptureResult(payload = first)
        }
        val fallbackEdge = minOf(clampedEdge, FALLBACK_MAX_EDGE)
        val fallbackQuality = minOf(clampedQuality, FALLBACK_QUALITY)
        val retry = compressScaled(source, origW, origH, fallbackEdge, fallbackQuality)
        if (retry != null && retry.bytes <= maxBytes) {
            return ScreenshotCaptureResult(payload = retry)
        }
        return ScreenshotCaptureResult(payload = null, omittedReason = "size_limit")
    }

    private fun compressScaled(
        source: Bitmap,
        origW: Int,
        origH: Int,
        maxEdge: Int,
        quality: Int,
    ): ScreenshotPayload? {
        val longest = max(origW, origH)
        val targetW: Int
        val targetH: Int
        if (longest > maxEdge) {
            val ratio = maxEdge.toDouble() / longest.toDouble()
            targetW = (origW * ratio).roundToInt().coerceAtLeast(1)
            targetH = (origH * ratio).roundToInt().coerceAtLeast(1)
        } else {
            targetW = origW
            targetH = origH
        }
        val working = if (targetW != origW || targetH != origH) {
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        } else {
            source
        }
        return try {
            val out = ByteArrayOutputStream()
            val ok = working.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (!ok) return null
            val bytes = out.toByteArray()
            ScreenshotPayload(
                mimeType = "image/jpeg",
                width = targetW,
                height = targetH,
                scaleX = origW.toDouble() / targetW.toDouble(),
                scaleY = origH.toDouble() / targetH.toDouble(),
                bytes = bytes.size,
                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        } finally {
            if (working !== source) {
                working.recycle()
            }
        }
    }
}

/**
 * AccessibilityService powering remote UI tree collection, gesture dispatch, text input, and
 * API 30+ JPEG screenshots. Performs zero work when [PhoneControlGateState.isMasterEnabled] is false.
 */
class PhoneControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo?.let { info ->
            val needed = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            if ((info.flags and needed) != needed) {
                info.flags = info.flags or needed
                serviceInfo = info
            }
        }
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // O(1) short-circuit: zero allocations in disabled or idle state; snapshots are collected on-demand.
        if (!PhoneControlGateState.isMasterEnabled()) return
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        val _ = _instance.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        val _ = _instance.compareAndSet(this, null)
        super.onDestroy()
    }

    fun windowsOnDisplay(displayId: Int): List<AccessibilityWindowInfo> {
        if (!PhoneControlGateState.isMasterEnabled()) return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowsOnAllDisplays[displayId].orEmpty()
        }
        if (displayId != Display.DEFAULT_DISPLAY) return emptyList()
        @Suppress("DEPRECATION")
        return windows.orEmpty()
    }

    suspend fun tap(displayId: Int, x: Float, y: Float, durationMs: Long = 60L): Boolean {
        if (!PhoneControlGateState.isMasterEnabled()) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatchStrokes(displayId, listOf(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))))
    }

    suspend fun doubleTap(displayId: Int, x: Float, y: Float): Boolean {
        if (!PhoneControlGateState.isMasterEnabled()) return false
        val path = Path().apply { moveTo(x, y) }
        val s1 = GestureDescription.StrokeDescription(path, 0L, 45L)
        val s2 = GestureDescription.StrokeDescription(path, 110L, 45L)
        return dispatchStrokes(displayId, listOf(s1, s2))
    }

    suspend fun longPress(displayId: Int, x: Float, y: Float, durationMs: Long = 650L): Boolean {
        if (!PhoneControlGateState.isMasterEnabled()) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatchStrokes(displayId, listOf(GestureDescription.StrokeDescription(path, 0L, durationMs)))
    }

    suspend fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
        if (!PhoneControlGateState.isMasterEnabled()) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchStrokes(
            displayId,
            listOf(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 2000L))),
        )
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String, clearFirst: Boolean): Boolean {
        if (!PhoneControlGateState.isMasterEnabled()) return false
        val _ = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (clearFirst) {
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val _ = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private suspend fun dispatchStrokes(
        displayId: Int,
        strokes: List<GestureDescription.StrokeDescription>,
    ): Boolean {
        val overlay = PhoneControlGateState.activeOverlay
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val _ = builder.setDisplayId(displayId)
        }
        strokes.forEach { stroke -> val _ = builder.addStroke(stroke) }
        val gesture = builder.build()

        suspend fun send(): Boolean = suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!dispatchGesture(gesture, callback, null) && cont.isActive) {
                cont.resume(false)
            }
        }

        return overlay?.passGesturesThrough { send() } ?: send()
    }

    /**
     * API 30+ accessibility screenshot. Automatically hides the floating overlay during capture,
     * retries once after Android's 1s screenshot throttle, scales proportionally, and enforces a 2 MiB JPEG cap.
     */
    suspend fun captureScreenshotJpeg(
        displayId: Int = Display.DEFAULT_DISPLAY,
        maxEdge: Int = ScreenshotEncoder.DEFAULT_MAX_EDGE,
        quality: Int = ScreenshotEncoder.DEFAULT_QUALITY,
    ): ScreenshotCaptureResult {
        if (!PhoneControlGateState.isMasterEnabled()) {
            return ScreenshotCaptureResult(omittedReason = "control_disabled")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult(omittedReason = "unsupported_sdk")
        }
        val overlay = PhoneControlGateState.activeOverlay
        suspend fun capture(): ScreenshotCaptureResult {
            val first = screenshotOnce(displayId, maxEdge, quality)
            if (first != ScreenshotAttempt.Throttled) {
                return (first as? ScreenshotAttempt.Done)?.result
                    ?: ScreenshotCaptureResult(omittedReason = "capture_failed")
            }
            delay(1_050L)
            return (screenshotOnce(displayId, maxEdge, quality) as? ScreenshotAttempt.Done)?.result
                ?: ScreenshotCaptureResult(omittedReason = "capture_throttled")
        }
        return overlay?.hiddenWhile { capture() } ?: capture()
    }

    private sealed interface ScreenshotAttempt {
        data class Done(val result: ScreenshotCaptureResult) : ScreenshotAttempt
        data object Throttled : ScreenshotAttempt
        data object Failed : ScreenshotAttempt
    }

    private suspend fun screenshotOnce(
        displayId: Int,
        maxEdge: Int,
        quality: Int,
    ): ScreenshotAttempt {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ScreenshotAttempt.Failed
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val encoded = runCatching {
                        result.hardwareBuffer.use { buffer ->
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?: return@runCatching ScreenshotCaptureResult(omittedReason = "hardware_buffer_failed")
                            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (sw == null) {
                                return@runCatching ScreenshotCaptureResult(omittedReason = "bitmap_copy_failed")
                            }
                            try {
                                ScreenshotEncoder.encodeJpeg(sw, maxEdge, quality)
                            } finally {
                                sw.recycle()
                            }
                        }
                    }.getOrElse { ScreenshotCaptureResult(omittedReason = "capture_error") }
                    if (cont.isActive) {
                        cont.resume(ScreenshotAttempt.Done(encoded))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!cont.isActive) return
                    val attempt = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        ScreenshotAttempt.Throttled
                    } else {
                        ScreenshotAttempt.Failed
                    }
                    cont.resume(attempt)
                }
            })
        }
    }

    companion object {
        private val _instance = MutableStateFlow<PhoneControlAccessibilityService?>(null)
        val instance: StateFlow<PhoneControlAccessibilityService?> = _instance.asStateFlow()
    }
}
