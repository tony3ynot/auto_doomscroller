package com.autodoomscroller.adapter

import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.ReelSnapshot

/** Knows how to read one app's short-video feed from its accessibility node tree. */
interface TargetAppAdapter {
    val packageName: String

    fun read(root: AccessibilityNodeInfo): FeedState
}

sealed interface FeedState {
    data class OnReel(val snapshot: ReelSnapshot) : FeedState

    /** The short-video feed is not on screen (e.g. the user swiped over to DMs). */
    data object NotOnFeed : FeedState

    /** The feed is on screen but the current reel can't be read yet (loading, mid-swipe). */
    data object Unknown : FeedState

    /** A feed item that isn't a regular reel, e.g. a collage of several ads. */
    data class OtherItem(val isAd: Boolean) : FeedState
}
