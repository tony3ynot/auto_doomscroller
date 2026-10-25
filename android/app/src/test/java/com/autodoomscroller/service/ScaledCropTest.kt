package com.autodoomscroller.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaledCropTest {
    @Test
    fun sameSizeKeepsTheCrop() {
        assertEquals(listOf(0, 103, 1080, 2052), scaledCrop(0, 103, 1080, 2052, 1080, 2340, 1080))
    }

    @Test
    fun scalesToTheBitmapWhenItIsSmallerThanTheScreen() {
        assertEquals(listOf(0, 51, 540, 1026), scaledCrop(0, 103, 1080, 2052, 540, 1170, 1080))
    }

    @Test
    fun clampsToTheBitmap() {
        assertEquals(listOf(0, 0, 1080, 2340), scaledCrop(-20, -5, 1200, 2500, 1080, 2340, 1080))
    }

    @Test
    fun nothingLeftIsNull() {
        assertNull(scaledCrop(0, 2400, 1080, 2500, 1080, 2340, 1080))
    }
}
