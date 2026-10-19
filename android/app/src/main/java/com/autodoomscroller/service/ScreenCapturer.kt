package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display

/**
 * Grabs the current screen through the accessibility service and downscales it.
 *
 * takeScreenshot() is rate limited, so requests are spaced by [MIN_INTERVAL_MS] measured from
 * the previous request, and a request rejected for being too early is retried.
 */
class ScreenCapturer(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    /** [stillWanted] is checked right before the request is sent; delayed captures may be stale. */
    fun capture(stillWanted: () -> Boolean, onResult: (Bitmap) -> Unit) = request(stillWanted, onResult, MAX_RETRIES)

    fun cancelPending() = handler.removeCallbacksAndMessages(null)

    private fun request(stillWanted: () -> Boolean, onResult: (Bitmap) -> Unit, retriesLeft: Int) {
        val waitMs = lastRequestAtMs + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
        if (waitMs > 0) {
            handler.postDelayed({ request(stillWanted, onResult, retriesLeft) }, waitMs)
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
                    software?.let { onResult(downscale(it)) }
                }

                override fun onFailure(errorCode: Int) {
                    val tooSoon = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                    if (tooSoon && retriesLeft > 0) {
                        request(stillWanted, onResult, retriesLeft - 1)
                    } else {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                    }
                }
            },
        )
    }

    /** Keeps the aspect ratio; cropping and resizing for the encoder happen downstream. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val height = bitmap.height * THUMBNAIL_WIDTH / bitmap.width
        val scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "ScreenCapturer"
        const val THUMBNAIL_WIDTH = 224
        // The framework limit is about a third of a second; keep some margin.
        const val MIN_INTERVAL_MS = 400L
        const val MAX_RETRIES = 2
    }
}
