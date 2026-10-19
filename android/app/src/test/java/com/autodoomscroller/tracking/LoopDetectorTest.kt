package com.autodoomscroller.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LoopDetectorTest {

    /** A distinct random "scene"; [noise] blends in a little randomness to mimic compression. */
    private fun scene(seed: Int, noise: Float = 0f, noiseSeed: Int = 0): FloatArray {
        val base = Random(seed)
        val jitter = Random(noiseSeed)
        val size = Fingerprint.SIZE
        val pixels = IntArray(size * size) {
            val v = (base.nextInt(256) * (1 - noise) + jitter.nextInt(256) * noise).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Fingerprint.fromPixels(pixels, stride = size, left = 0, top = 0, right = size, bottom = size)
    }

    @Test
    fun identicalFramesAreFullySimilar() {
        assertEquals(1f, Fingerprint.similarity(scene(1), scene(1)), 1e-4f)
    }

    @Test
    fun flatFramesAreFlagged() {
        val gray = IntArray(16 * 16) { (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80 }
        assertTrue(Fingerprint.isFlat(Fingerprint.fromPixels(gray, 16, 0, 0, 16, 16)))
    }

    @Test
    fun detectsTheVideoStartingOver() {
        val detector = LoopDetector()
        // References from the first second, then new scenes, then the opening scene again.
        assertNull(detector.add(scene(1), 300))
        assertNull(detector.add(scene(2), 800))
        assertNull(detector.add(scene(3), 1_300))
        assertNull(detector.add(scene(4), 2_300))
        assertNull(detector.add(scene(5), 3_300))
        val duration = detector.add(scene(2, noise = 0.05f, noiseSeed = 9), 8_800)
        assertEquals(8_000L, duration)
    }

    @Test
    fun staticVideoNeverLoops() {
        val detector = LoopDetector()
        for (t in 0 until 10) assertNull(detector.add(scene(1, noise = 0.05f, noiseSeed = t), 300L + t * 1_000))
    }
}
