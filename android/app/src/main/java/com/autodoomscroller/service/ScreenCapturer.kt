package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager

/**
 * Grabs the current screen through the accessibility service, crops it and downscales it.
 *
 * takeScreenshot() is rate limited, so requests are spaced by [MIN_INTERVAL_MS] measured from
 * the previous request, and a request rejected for being too early is retried.
 */
class ScreenCapturer(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    /**
     * [crop] is in screen coordinates (as accessibility bounds are); null keeps the whole screen.
     * [stillWanted] is checked right before the request is sent; delayed captures may be stale.
     */
    fun capture(crop: Rect?, stillWanted: () -> Boolean, onResult: (Bitmap) -> Unit) =
        request(crop, stillWanted, onResult, MAX_RETRIES)

    fun cancelPending() = handler.removeCallbacksAndMessages(null)

    private fun request(crop: Rect?, stillWanted: () -> Boolean, onResult: (Bitmap) -> Unit, retriesLeft: Int) {
        val waitMs = lastRequestAtMs + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
        if (waitMs > 0) {
            handler.postDelayed({ request(crop, stillWanted, onResult, retriesLeft) }, waitMs)
            return
        }
        if (!stillWanted()) return
        lastRequestAtMs = SystemClock.uptimeMillis()
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()
                    software?.let { onResult(it.croppedTo(crop).scaledToWidth(CAPTURE_WIDTH)) }
                }

                override fun onFailure(errorCode: Int) {
                    val tooSoon = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                    if (tooSoon && retriesLeft > 0) {
                        request(crop, stillWanted, onResult, retriesLeft - 1)
                    } else {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                    }
                }
            },
        )
    }

    /** Screenshots come at the display's pixel size, which is the space accessibility bounds use. */
    private fun Bitmap.croppedTo(crop: Rect?): Bitmap {
        if (crop == null) return this
        val screenWidth = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        val (left, top, right, bottom) = scaledCrop(crop.left, crop.top, crop.right, crop.bottom, width, height, screenWidth)
            ?: return this
        val cropped = Bitmap.createBitmap(this, left, top, right - left, bottom - top)
        if (cropped !== this) recycle()
        return cropped
    }

    companion object {
        private const val TAG = "ScreenCapturer"

        /** Wide enough for on-screen text (subtitles, meme captions) to stay readable for OCR. */
        const val CAPTURE_WIDTH = 540
        const val THUMBNAIL_WIDTH = 224
        // The framework limit is about a third of a second; keep some margin.
        private const val MIN_INTERVAL_MS = 400L
        private const val MAX_RETRIES = 2
    }
}

/** Scales to [width] keeping the aspect ratio, recycling the original. */
fun Bitmap.scaledToWidth(width: Int): Bitmap {
    if (this.width <= width) return this
    val scaled = Bitmap.createScaledBitmap(this, width, height * width / this.width, true)
    if (scaled !== this) recycle()
    return scaled
}

/**
 * Maps a crop given in screen pixels onto a bitmap [bitmapWidth] wide, clamped to the bitmap.
 * Returns left, top, right, bottom, or null when nothing of the crop is left.
 */
internal fun scaledCrop(
    left: Int, top: Int, right: Int, bottom: Int,
    bitmapWidth: Int, bitmapHeight: Int, screenWidth: Int,
): List<Int>? {
    val scale = if (screenWidth > 0) bitmapWidth.toDouble() / screenWidth else 1.0
    val l = (left * scale).toInt().coerceIn(0, bitmapWidth)
    val t = (top * scale).toInt().coerceIn(0, bitmapHeight)
    val r = (right * scale).toInt().coerceIn(0, bitmapWidth)
    val b = (bottom * scale).toInt().coerceIn(0, bitmapHeight)
    return if (r > l && b > t) listOf(l, t, r, b) else null
}
