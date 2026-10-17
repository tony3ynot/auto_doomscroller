package com.autodoomscroller.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.ReelSnapshot

class InstagramReelsAdapter : TargetAppAdapter {
    override val packageName = "com.instagram.android"

    override fun readCurrentReel(root: AccessibilityNodeInfo): ReelSnapshot? {
        // TODO: Fill in from real node dumps (debug builds log them under the "NodeTree" tag):
        //  - how to tell the Reels viewer apart from the rest of the app
        //  - view IDs / content descriptions for creator, caption, audio and the like button
        //  IDs and labels change with app version and UI language, so keep lookups in one place.
        return null
    }
}
