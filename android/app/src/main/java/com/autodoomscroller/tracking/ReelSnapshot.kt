package com.autodoomscroller.tracking

/** What an adapter can read about the reel currently on screen. */
data class ReelSnapshot(
    val packageName: String,
    val creator: String?,
    val caption: String?,
    val audio: String?,
    /** Null when the like button could not be found. */
    val liked: Boolean?,
) {
    // TODO: add a first-frame embedding hash once frames are embedded, so reels with
    //  the same creator and an empty caption are not merged.
    val key: String
        get() = "$packageName|${creator.orEmpty()}|${caption.orEmpty().hashCode()}"
}
