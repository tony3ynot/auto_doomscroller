package com.autodoomscroller.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.ReelSnapshot

/** Knows how to read one app's short-video feed from its accessibility node tree. */
interface TargetAppAdapter {
    val packageName: String

    /** The reel currently on screen, or null if the user is not on the short-video feed. */
    fun readCurrentReel(root: AccessibilityNodeInfo): ReelSnapshot?
}
