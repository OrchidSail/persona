package persona.platform.phonetools.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import persona.platform.phonetools.control.shell.DisplayFrameGeometry
import persona.platform.phonetools.control.shell.IPhoneControlShell
import persona.platform.phonetools.control.shell.uprightDisplayFrame

/**
 * Strictly lazy, zero-MediaProjection background [VirtualDisplay] controller backed by the
 * privileged uid-2000 [IPhoneControlShell] helper (built-in wireless ADB or Shizuku).
 *
 * When no privileged shell is active, [ensureCreated] returns null so the executor falls back
 * cleanly to foreground accessibility instead of creating an unusable untrusted presentation display.
 * Publishes throttled, deduplicated live preview bitmaps (~5fps, short side <= 320px) for the
 * fixed-size mini-screen floating overlay while retaining full frames for JPEG grounding captures.
 */
internal class PhoneControlVirtualDisplay(
    private val context: Context,
    private val shellProvider: () -> IPhoneControlShell? = { null },
) {
    private val lock = Any()
    private val displayManager: DisplayManager?
        get() = context.getSystemService(DisplayManager::class.java)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var activeShell: IPhoneControlShell? = null
    private var bufferWidth: Int = 0
    private var bufferHeight: Int = 0
    private var fullFrame: Bitmap? = null
    private var hasFrame: Boolean = false
    private var lastFrameProcessMs: Long = 0L
    private var lastPreviewHash: Long = 0L

    private val _displayId = MutableStateFlow<Int?>(null)
    val displayId: StateFlow<Int?> = _displayId.asStateFlow()

    private val _previewFrames = MutableStateFlow<Bitmap?>(null)
    val previewFrames: StateFlow<Bitmap?> = _previewFrames.asStateFlow()

    private val _previewFrameProtected = MutableStateFlow(false)
    val previewFrameProtected: StateFlow<Boolean> = _previewFrameProtected.asStateFlow()

    val isActive: Boolean
        get() = _displayId.value != null

    val isBackendAvailable: Boolean
        get() = shellProvider() != null

    val currentWidth: Int
        get() = currentGeometry().width

    val currentHeight: Int
        get() = currentGeometry().height

    fun ensureCreated(): Int? = synchronized(lock) {
        if (!PhoneControlGateState.isMasterEnabled()) return null
        _displayId.value?.let { existingId ->
            val sh = activeShell
            if (sh != null && runCatching { sh.identity() }.isSuccess) {
                return existingId
            }
            releaseLocked()
        }
        val shell = shellProvider() ?: return null

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceIn(720, 1440)
        val height = metrics.heightPixels.coerceIn(1280, 3200)
        val dpi = metrics.densityDpi.coerceIn(160, 640)

        ensureThreadLocked()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        bufferWidth = width
        bufferHeight = height
        imageReader = reader
        hasFrame = false
        lastPreviewHash = 0L
        _previewFrameProtected.value = false
        // Register listener BEFORE creating the virtual display so the very first rendered frame is never missed.
        reader.setOnImageAvailableListener({ r -> captureFromReader(r) }, frameHandler)
        val id = runCatching {
            shell.createDisplay(reader.surface, width, height, dpi)
        }.getOrDefault(-1)

        if (id <= Display.DEFAULT_DISPLAY) {
            val _ = runCatching { reader.setOnImageAvailableListener(null, null) }
            val _ = runCatching { reader.close() }
            imageReader = null
            tearDownThreadLocked()
            _displayId.value = null
            return null
        }

        activeShell = shell
        _displayId.value = id
        return id
    }

    fun topTaskPackage(): String? {
        val id = _displayId.value ?: return null
        val sh = synchronized(lock) { activeShell } ?: return null
        return runCatching { sh.topTaskPackage(id) }.getOrNull()
    }

    fun bringTopTaskToMainDisplay(): Boolean {
        val id = _displayId.value ?: return false
        val sh = synchronized(lock) { activeShell } ?: return false
        val taskId = runCatching { sh.topTaskId(id) }.getOrDefault(-1)
        if (taskId <= 0) return false
        return runCatching { sh.moveTask(taskId, Display.DEFAULT_DISPLAY) }.getOrDefault(false)
    }

    fun latestFrameJpeg(
        maxEdge: Int = ScreenshotEncoder.DEFAULT_MAX_EDGE,
        quality: Int = ScreenshotEncoder.DEFAULT_QUALITY,
    ): ScreenshotCaptureResult? {
        val upright = synchronized(lock) {
            val bmp = fullFrame
            if (!hasFrame || bmp == null || bmp.isRecycled) null
            else uprightDisplayFrame(bmp, currentGeometry(), paint = paint)
        } ?: return null
        return try {
            ScreenshotEncoder.encodeJpeg(upright, maxEdge, quality)
        } finally {
            upright.recycle()
        }
    }

    fun release() = synchronized(lock) {
        releaseLocked()
    }

    private fun releaseLocked() {
        val id = _displayId.value
        val sh = activeShell
        if (id != null && sh != null) {
            val _ = runCatching { sh.releaseDisplay(id) }
        }
        activeShell = null
        _displayId.value = null
        val _ = runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        val _ = runCatching { imageReader?.close() }
        imageReader = null
        fullFrame?.recycle()
        fullFrame = null
        hasFrame = false
        lastPreviewHash = 0L
        _previewFrames.value = null
        _previewFrameProtected.value = false
        tearDownThreadLocked()
    }

    private fun currentGeometry(): DisplayFrameGeometry {
        val id = _displayId.value
        val rotation = if (id != null) {
            runCatching { displayManager?.getDisplay(id)?.rotation }.getOrNull() ?: Surface.ROTATION_0
        } else {
            Surface.ROTATION_0
        }
        val w = if (bufferWidth > 0) bufferWidth else context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val h = if (bufferHeight > 0) bufferHeight else context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        return DisplayFrameGeometry(w, h, rotation)
    }

    private fun ensureThreadLocked() {
        if (frameThread == null) {
            val thread = HandlerThread("persona-phone-control-vd").apply { start() }
            frameThread = thread
            frameHandler = Handler(thread.looper)
        }
    }

    private fun tearDownThreadLocked() {
        val _ = frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
    }

    private fun captureFromReader(reader: ImageReader) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameProcessMs < FRAME_MIN_INTERVAL_MS) {
            val _ = runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        lastFrameProcessMs = now
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val geom = currentGeometry()
            synchronized(lock) {
                if (_displayId.value == null) return
                var bmp = fullFrame
                if (bmp == null || bmp.isRecycled || bmp.width != strideWidth || bmp.height != bufferHeight) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                val _ = plane.buffer.rewind()
                bmp.copyPixelsFromBuffer(plane.buffer)
                hasFrame = true
                val (sampleHash, mostlyBlack) = sampleBitmapHashAndBlack(bmp, bufferWidth, bufferHeight)
                _previewFrameProtected.value = mostlyBlack
                if (sampleHash != lastPreviewHash || _previewFrames.value == null) {
                    lastPreviewHash = sampleHash
                    val preview = uprightDisplayFrame(bmp, geom, geom.previewWidth(PREVIEW_SHORT_SIDE_PX), paint)
                    _previewFrames.value = preview
                }
            }
        } catch (_: Throwable) {
            // Preserve prior frame on transient ImageReader errors.
        } finally {
            image.close()
        }
    }

    private fun sampleBitmapHashAndBlack(bmp: Bitmap, contentW: Int, contentH: Int): Pair<Long, Boolean> {
        if (contentW <= 0 || contentH <= 0) return 0L to false
        var h = -3750763034362895579L
        val stepX = (contentW / 8).coerceAtLeast(1)
        val stepY = (contentH / 8).coerceAtLeast(1)
        var nonBlack = 0
        var total = 0
        var y = stepY / 2
        while (y < contentH) {
            var x = stepX / 2
            while (x < contentW) {
                val px = bmp.getPixel(x.coerceAtMost(bmp.width - 1), y.coerceAtMost(bmp.height - 1))
                h = (h xor px.toLong()) * 1099511628211L
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (r > 8 || g > 8 || b > 8) nonBlack++
                total++
                x += stepX
            }
            y += stepY
        }
        return h to (total > 0 && nonBlack == 0)
    }

    private companion object {
        const val FRAME_MIN_INTERVAL_MS = 180L
        const val PREVIEW_SHORT_SIDE_PX = 320
    }
}
