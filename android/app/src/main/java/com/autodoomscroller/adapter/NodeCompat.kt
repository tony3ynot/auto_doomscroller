package com.autodoomscroller.adapter

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Checked state of a checkable node; `isChecked` was replaced by a tri-state in API 36. */
internal val AccessibilityNodeInfo.isCheckedCompat: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
    } else {
        @Suppress("DEPRECATION")
        isChecked
    }
