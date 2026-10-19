package com.autodoomscroller.tracking

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * A tiny grayscale summary of a frame, used to notice when a video starts over.
 * Only the middle of the screen is used, so the app's own overlays (tabs, caption,
 * action buttons) don't make different reels look alike.
 */
object Fingerprint {
    const val SIZE = 16

    fun of(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return fromPixels(
            pixels,
            stride = width,
            left = (width * 0.10).toInt(),
            top = (height * 0.15).toInt(),
            right = (width * 0.80).toInt(),
            bottom = (height * 0.65).toInt(),
        )
    }

    /** Averages ARGB [pixels] inside the box into SIZE×SIZE cells, then normalizes. */
    fun fromPixels(pixels: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int): FloatArray {
        val cells = FloatArray(SIZE * SIZE)
        for (gy in 0 until SIZE) {
            val y0 = top + (bottom - top) * gy / SIZE
            val y1 = maxOf(y0 + 1, top + (bottom - top) * (gy + 1) / SIZE)
            for (gx in 0 until SIZE) {
                val x0 = left + (right - left) * gx / SIZE
                val x1 = maxOf(x0 + 1, left + (right - left) * (gx + 1) / SIZE)
                var sum = 0f
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val p = pixels[y * stride + x]
                        sum += 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
                    }
                }
                cells[gy * SIZE + gx] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        val mean = cells.average().toFloat()
        val std = sqrt(cells.map { (it - mean) * (it - mean) }.average()).toFloat()
        // Flat frames (black transitions, solid backgrounds) carry no information.
        return if (std < FLAT_STD) FloatArray(SIZE * SIZE) else FloatArray(cells.size) { (cells[it] - mean) / std }
    }

    fun isFlat(fingerprint: FloatArray): Boolean = fingerprint.all { it == 0f }

    /** Pearson correlation of two non-flat fingerprints, in [-1, 1]. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot / a.size
    }

    private const val FLAT_STD = 2f
}
