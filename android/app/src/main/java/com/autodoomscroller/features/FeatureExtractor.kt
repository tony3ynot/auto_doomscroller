package com.autodoomscroller.features

import android.graphics.Bitmap
import com.autodoomscroller.tracking.ReelSnapshot

/**
 * Turns one reel into a fixed-length feature vector for the preference model.
 *
 * Planned parts (see ml/ for the offline side):
 *  - image embedding of the captured frames (MobileNetV3 encoder, FP32/FP16/INT8 LiteRT variants)
 *  - text embedding of caption, hashtags, audio name and on-screen text (ML Kit OCR)
 *  - creator history: smoothed past watch rate and view count for this creator
 */
interface FeatureExtractor {
    suspend fun extract(snapshot: ReelSnapshot, frames: List<Bitmap>): FloatArray
}
