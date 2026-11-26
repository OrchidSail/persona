package persona.platform.phonetools.control.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal data class DisplayFrameGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotation: Int,
) {
    private val sideways: Boolean get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    fun previewWidth(shortSide: Int): Int =
        if (width > height) maxOf(1, (width.toLong() * shortSide / height).toInt()) else shortSide

    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width).coerceAtLeast(1)
        val h = maxOf(1, (height.toLong() * w / width.coerceAtLeast(1)).toInt())
        return w to h
    }
}

internal fun uprightDisplayFrame(
    buffer: Bitmap,
    geometry: DisplayFrameGeometry,
    maxWidth: Int = geometry.width,
    paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
): Bitmap {
    val (width, height) = geometry.previewSize(maxWidth)
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        scale(width.toFloat() / geometry.width.coerceAtLeast(1), height.toFloat() / geometry.height.coerceAtLeast(1))
        when (geometry.rotation) {
            1 -> { translate(0f, geometry.bufferWidth.toFloat()); rotate(-90f) }
            2 -> { translate(geometry.bufferWidth.toFloat(), geometry.bufferHeight.toFloat()); rotate(180f) }
            3 -> { translate(geometry.bufferHeight.toFloat(), 0f); rotate(90f) }
        }
        val content = Rect(0, 0, geometry.bufferWidth, geometry.bufferHeight)
        drawBitmap(buffer, content, content, paint)
    }
    return result
}
