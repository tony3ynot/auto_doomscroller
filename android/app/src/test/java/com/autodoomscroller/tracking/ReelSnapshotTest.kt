package com.autodoomscroller.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelSnapshotTest {

    private fun reel(
        creator: String = "cat_daily",
        caption: String? = "zoomies",
        liked: Boolean? = false,
        mediaType: MediaType = MediaType.VIDEO,
        isAd: Boolean = false,
        positionMs: Long? = null,
    ) = ReelSnapshot(
        packageName = "com.instagram.android",
        creator = creator,
        caption = caption,
        audio = null,
        mediaType = mediaType,
        isAd = isAd,
        liked = liked,
        saved = false,
        likeCount = null,
        commentCount = null,
        positionMs = positionMs,
        durationMs = positionMs?.let { 30_000L },
        commentsOpen = false,
    )

    @Test
    fun captionLoadingLateIsStillTheSameReel() {
        assertTrue(reel(caption = null).isSameReelAs(reel(caption = "zoomies")))
    }

    @Test
    fun differentCreatorIsANewReel() {
        assertFalse(reel(creator = "cat_daily").isSameReelAs(reel(creator = "dog_daily")))
    }

    @Test
    fun sameCreatorWithADifferentCaptionIsANewReel() {
        assertFalse(reel(caption = "zoomies").isSameReelAs(reel(caption = "nap time")))
    }

    @Test
    fun mergeKeepsEarlierCaptionAndTakesLatestLikeState() {
        val merged = reel(caption = null, liked = false).mergedWith(reel(caption = "zoomies", liked = true))
        assertEquals("zoomies", merged.caption)
        assertEquals(true, merged.liked)
        val unliked = merged.mergedWith(reel(caption = "zoomies", liked = false))
        assertEquals(false, unliked.liked)
    }

    @Test
    fun lateMarkersStickOnceSeen() {
        val merged = reel().mergedWith(reel(mediaType = MediaType.CAROUSEL, isAd = true)).mergedWith(reel())
        assertEquals(MediaType.CAROUSEL, merged.mediaType)
        assertTrue(merged.isAd)
    }

    @Test
    fun mergeKeepsLatestPlaybackPosition() {
        val merged = reel(positionMs = 4_000).mergedWith(reel(positionMs = 9_000))
        assertEquals(9_000L, merged.positionMs)
        assertEquals(30_000L, merged.durationMs)
    }
}
