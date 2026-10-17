package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class GestureDispatcher(private val service: AccessibilityService) {

    /** One swipe up through the middle of the screen: "next reel" in Reels and Shorts. */
    fun swipeUp(onDone: (Boolean) -> Unit = {}) {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.75f)
            lineTo(x, metrics.heightPixels * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            },
            null,
        )
        if (!dispatched) onDone(false)
    }

    private companion object {
        const val SWIPE_DURATION_MS = 250L
    }
}
