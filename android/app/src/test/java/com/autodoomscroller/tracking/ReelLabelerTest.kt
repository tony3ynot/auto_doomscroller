package com.autodoomscroller.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as ml/tests/test_labels.py. */
class ReelLabelerTest {

    @Test
    fun quickSwipeIsSkip() {
        assertEquals(ReelLabel.SKIP, ReelLabeler.label(1_500, looped = false, liked = false, movedToNext = true))
    }

    @Test
    fun leavingTheAppQuicklyIsIgnored() {
        assertNull(ReelLabeler.label(1_500, looped = false, liked = false, movedToNext = false))
    }

    @Test
    fun loopIsWatch() {
        assertEquals(ReelLabel.WATCH, ReelLabeler.label(9_000, looped = true, liked = false, movedToNext = true))
    }

    @Test
    fun likeIsWatchEvenWhenQuick() {
        assertEquals(ReelLabel.WATCH, ReelLabeler.label(1_000, looped = false, liked = true, movedToNext = true))
    }

    @Test
    fun longDwellIsWatch() {
        assertEquals(ReelLabel.WATCH, ReelLabeler.label(20_000, looped = false, liked = false, movedToNext = true))
    }

    @Test
    fun middleDwellIsIgnored() {
        assertNull(ReelLabeler.label(8_000, looped = false, liked = false, movedToNext = true))
    }
}
