package com.autodoomscroller.tracking

enum class MediaType { VIDEO, IMAGE, CAROUSEL }

/** How a reel's length was learned: read from the scrubber, or estimated by seeing it start over. */
enum class DurationSource { SCRUBBER, LOOP }

/** What an adapter can read about the reel currently on screen. Null fields were not found. */
data class ReelSnapshot(
    val packageName: String,
    val creator: String,
    val caption: String?,
    val audio: String?,
    val mediaType: MediaType,
    val isAd: Boolean,
    val liked: Boolean?,
    val saved: Boolean?,
    val likeCount: Long?,
    val commentCount: Long?,
    /** Playback position and length from the scrubber; only some reels show one. */
    val positionMs: Long?,
    val durationMs: Long?,
    /** The comment sheet is open on top of the reel. */
    val commentsOpen: Boolean,
    /** Position in the feed as reported by the app; restarts when the feed is refreshed. */
    val feedIndex: Int? = null,
    /** Identifies the carousel slide on screen; a change means the user swiped. */
    val slideKey: Int? = null,
) {
    val key: String
        get() = "$packageName|$creator|${caption.orEmpty().hashCode()}"

    /**
     * Same reel if the creator matches and the captions don't contradict each other.
     * Captions often appear a moment after the reel, so a missing one matches anything.
     */
    fun isSameReelAs(other: ReelSnapshot): Boolean =
        packageName == other.packageName &&
            creator == other.creator &&
            (caption == null || other.caption == null || caption == other.caption)

    /** Fills in what was missing earlier and takes the latest button and playback states. */
    fun mergedWith(newer: ReelSnapshot): ReelSnapshot = copy(
        caption = caption ?: newer.caption,
        audio = audio ?: newer.audio,
        // Image/carousel markers can render a moment late; VIDEO is only the fallback.
        mediaType = if (newer.mediaType != MediaType.VIDEO) newer.mediaType else mediaType,
        isAd = isAd || newer.isAd,
        liked = newer.liked ?: liked,
        saved = newer.saved ?: saved,
        likeCount = newer.likeCount ?: likeCount,
        commentCount = newer.commentCount ?: commentCount,
        positionMs = newer.positionMs ?: positionMs,
        durationMs = newer.durationMs ?: durationMs,
        commentsOpen = newer.commentsOpen,
        feedIndex = feedIndex ?: newer.feedIndex,
        slideKey = newer.slideKey ?: slideKey,
    )
}
