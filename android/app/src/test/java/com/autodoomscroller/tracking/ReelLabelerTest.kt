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
        durationMs: Long? = null,
        completed: Boolean = false,
        engaged: Boolean = false,
        movedToNext: Boolean = true,
    ) = ReelLabeler.label(dwellMs, durationMs, completed, engaged, movedToNext)

    @Test
    fun quickSwipeIsSkip() = assertEquals(ReelLabel.SKIP, label(1_500))

    @Test
    fun leavingTheFeedQuicklyIsIgnored() = assertNull(label(1_500, movedToNext = false))

    @Test
    fun completedIsWatchEvenWhenShort() = assertEquals(ReelLabel.WATCH, label(3_000, durationMs = 2_800, completed = true))

    @Test
    fun engagementIsWatchEvenWhenQuick() = assertEquals(ReelLabel.WATCH, label(1_000, engaged = true))

    @Test
    fun fiveSecondsIsWatchWhateverTheLength() = assertEquals(ReelLabel.WATCH, label(5_000, durationMs = 120_000))

    @Test
    fun middleDwellIsIgnored() = assertNull(label(3_500))

    @Test
    fun shortReelLeftLoopingIsIdle() = assertNull(label(40_000, durationMs = 6_000, completed = true))

    @Test
    fun longReelWatchedForAWhileIsNotIdle() = assertEquals(ReelLabel.WATCH, label(100_000, durationMs = 145_000))

    @Test
    fun imageStaredAtForMinutesIsIdle() = assertNull(label(90_000))

    @Test
    fun engagementOverridesIdle() = assertEquals(ReelLabel.WATCH, label(90_000, engaged = true))

    @Test
    fun loopCountsAsCompleted() = assertTrue(ReelLabeler.isCompleted(looped = true, maxPositionMs = null, durationMs = null))

    @Test
    fun scrubberNearTheEndCountsAsCompleted() =
        assertTrue(ReelLabeler.isCompleted(looped = false, maxPositionMs = 32_000, durationMs = 35_000))

    @Test
    fun scrubberHalfwayIsNotCompleted() =
        assertFalse(ReelLabeler.isCompleted(looped = false, maxPositionMs = 17_000, durationMs = 35_000))
}
