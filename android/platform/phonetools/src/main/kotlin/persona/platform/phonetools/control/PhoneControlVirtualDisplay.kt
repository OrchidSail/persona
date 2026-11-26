package persona.platform.phonetools.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Strictly lazy, zero-MediaProjection background [VirtualDisplay] controller.
 *
 * Allocates zero resources (no HandlerThread, ImageReader, or VirtualDisplay) while remote control is off
 * or idle, and only creates a VirtualDisplay if an active task explicitly requests background off-screen launch.
 * Always released on terminal task completion, disconnect timeout, or master switch disable.
 */
internal class PhoneControlVirtualDisplay(private val context: Context) {
    private val lock = Any()
    private val displayManager: DisplayManager?
        get() = context.getSystemService(DisplayManager::class.java)

    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null
    private var lastFrameProcessMs = 0L

    private val _displayId = MutableStateFlow<Int?>(null)
    val displayId: StateFlow<Int?> = _displayId.asStateFlow()

    val isActive: Boolean
        get() = _displayId.value != null

    fun ensureCreated(): Int? = synchronized(lock) {
        if (!PhoneControlGateState.isMasterEnabled()) return null
        virtualDisplay?.display?.displayId?.let { return it }
        val dm = displayManager ?: return null

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceIn(720, 1440)
        val height = metrics.heightPixels.coerceIn(1280, 3200)
        val dpi = metrics.densityDpi.coerceIn(160, 640)

        ensureThreadLocked()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> captureFromReader(r) }, frameHandler)

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val vd = runCatching {
            dm.createVirtualDisplay(
                "persona-phone-bg",
                width,
                height,
                dpi,
                reader.surface,
                flags,
            )
        }.getOrNull()

        if (vd == null) {
            reader.close()
            tearDownThreadLocked()
            _displayId.value = null
            return null
        }

        imageReader = reader
        virtualDisplay = vd
        val id = vd.display?.displayId
        _displayId.value = id
        return id
    }

    fun latestFrameJpeg(
        maxEdge: Int = ScreenshotEncoder.DEFAULT_MAX_EDGE,
        quality: Int = ScreenshotEncoder.DEFAULT_QUALITY,
    ): ScreenshotCaptureResult? {
        val copy = synchronized(lock) {
            latestBitmap?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null
        return try {
            ScreenshotEncoder.encodeJpeg(copy, maxEdge, quality)
        } finally {
            copy.recycle()
        }
    }

    fun release() = synchronized(lock) {
        val _ = runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val _ = runCatching { imageReader?.close() }
        imageReader = null
        _displayId.value = null
        latestBitmap?.recycle()
        latestBitmap = null
        tearDownThreadLocked()
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
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            val _ = plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth != image.width) {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            } else {
                padded
            }
            synchronized(lock) {
                latestBitmap?.recycle()
                latestBitmap = cropped
            }
        } catch (_: Throwable) {
            // Keep prior frame on transient ImageReader errors.
        } finally {
            image.close()
        }
    }

    private companion object {
        const val FRAME_MIN_INTERVAL_MS = 100L
    }
}
