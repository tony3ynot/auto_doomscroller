package com.autodoomscroller.model

import com.autodoomscroller.tracking.ReelLabel

/** Per-user model that predicts whether this user would skip a reel. */
interface PreferenceModel {
    /** Probability of SKIP, or null while the model is not trained yet. */
    fun predictSkip(features: FloatArray): Float?

    /** Retrains on all labeled reels. Called when a session ends. */
    suspend fun retrain(examples: List<TrainingExample>)
}

class TrainingExample(
    val features: FloatArray,
    val label: ReelLabel,
    /** Higher for corrections (the user undid an auto skip). */
    val weight: Float = 1f,
)
