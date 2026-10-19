package com.autodoomscroller.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as ml/tests/test_labels.py. */
class ReelLabelerTest {

    private fun label(
        dwellMs: Long,
        completed: Boolean = false,
        liked: Boolean = false,
        saved: Boolean = false,
        movedToNext: Boolean = true,
    ) = ReelLabeler.label(dwellMs, completed, liked, saved, movedToNext)

    @Test
    fun quickSwipeIsSkip() = assertEquals(ReelLabel.SKIP, label(1_500))

    @Test
    fun leavingTheFeedQuicklyIsIgnored() = assertNull(label(1_500, movedToNext = false))

    @Test
    fun completedIsWatchEvenWhenShort() = assertEquals(ReelLabel.WATCH, label(3_000, completed = true))

    @Test
    fun likeIsWatchEvenWhenQuick() = assertEquals(ReelLabel.WATCH, label(1_000, liked = true))

    @Test
    fun saveIsWatchEvenWhenQuick() = assertEquals(ReelLabel.WATCH, label(1_000, saved = true))

    @Test
    fun longDwellIsWatch() = assertEquals(ReelLabel.WATCH, label(5_000))

    @Test
    fun middleDwellIsIgnored() = assertNull(label(3_500))

    @Test
    fun loopCountsAsCompleted() = assertTrue(ReelLabeler.isCompleted(looped = true, maxPositionMs = null, durationMs = null))

    @Test
    fun scrubberNearTheEndCountsAsCompleted() =
        assertTrue(ReelLabeler.isCompleted(looped = false, maxPositionMs = 32_000, durationMs = 35_000))

    @Test
    fun scrubberHalfwayIsNotCompleted() =
        assertFalse(ReelLabeler.isCompleted(looped = false, maxPositionMs = 17_000, durationMs = 35_000))

    @Test
    fun unknownLengthIsNotCompleted() =
        assertFalse(ReelLabeler.isCompleted(looped = false, maxPositionMs = 9_000, durationMs = null))
}
