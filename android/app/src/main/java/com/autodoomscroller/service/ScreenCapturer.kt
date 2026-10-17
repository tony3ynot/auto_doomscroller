package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display

/** Grabs the current screen through the accessibility service and downscales it. */
class ScreenCapturer(private val service: AccessibilityService) {

    fun capture(onResult: (Bitmap?) -> Unit) {
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
                    onResult(software?.let(::downscale))
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: $errorCode")
                    onResult(null)
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
    }
}
