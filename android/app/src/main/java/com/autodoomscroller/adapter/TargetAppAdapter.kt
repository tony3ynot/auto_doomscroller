package com.autodoomscroller.adapter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.tracking.ReelSnapshot

/** Knows how to read one app's short-video feed from its accessibility node tree. */
interface TargetAppAdapter {
    val packageName: String

    fun read(root: AccessibilityNodeInfo): FeedState

    /**
     * Whether [root] (any window of the app, the active one included) shows the current reel's
     * comments. Opening comments usually hides or shrinks the feed, so reads come back Unknown
     * meanwhile; this is how the service still notices. Must be cheap: it runs once a second.
     */
    fun isCommentsWindow(root: AccessibilityNodeInfo): Boolean = false

    /**
     * Where the reel from the last [read] is on screen, once it has stopped moving; null while it
     * is still sliding in or out. Screenshots are cropped to it, so they hold the reel and not the
     * status bar, navigation or whatever else the app shows around the feed. Re-fetches nodes, so
     * a call costs a couple of IPC round trips; [root] is the app's active window.
     */
    fun settledMediaBounds(root: AccessibilityNodeInfo): Rect? = null
}

sealed interface FeedState {
    data class OnReel(val snapshot: ReelSnapshot) : FeedState

    /** The short-video feed is not on screen (e.g. the user swiped over to DMs). */
    data object NotOnFeed : FeedState

    /** The feed is on screen but the current reel can't be read yet (loading, mid-swipe). */
    data object Unknown : FeedState

    /** A feed item that isn't a regular reel, e.g. a collage of several ads. */
    data class OtherItem(val isAd: Boolean) : FeedState

    /**
     * The current reel's comments are open in a window of their own, which hides the feed.
     * The user is still on the reel, and more engaged than ever.
     */
    data object CommentsOpen : FeedState
}
