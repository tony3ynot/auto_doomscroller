package com.autodoomscroller.model

import kotlin.math.exp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The per-user skip model: a logistic regression over the decision-time features and the
 * frame embedding, in the weight-file format written by `ml/src/autodoom/deploy.py`.
 */
class SkipModel(
    /** Names of the features the weights are for, in order. */
    val features: List<String>,
    private val impute: FloatArray,
    private val weights: FloatArray,
    private val frameWeights: FloatArray,
    private val bias: Float,
    /** What the file says about its training data, for display. */
    val trainedOn: TrainingSummary? = null,
    /**
     * P(skip) from which Auto mode may act; null while the model hasn't shown on recent days
     * that it is right often enough at any threshold.
     */
    val autoThreshold: Float? = null,
) {
    class TrainingSummary(val reels: Int, val lastDay: String)

    val frameSize: Int
        get() = frameWeights.size

    /** P(skip) from [x] (NaN where a feature is missing) and the L2-normalised [frame] embedding. */
    fun predictSkip(x: FloatArray, frame: FloatArray): Float {
        require(x.size == weights.size && frame.size == frameWeights.size) { "inputs don't match the model" }
        var logit = bias.toDouble()
        for (i in x.indices) logit += weights[i] * (if (x[i].isNaN()) impute[i] else x[i]).toDouble()
        for (j in frame.indices) logit += frameWeights[j] * frame[j].toDouble()
        return (1 / (1 + exp(-logit))).toFloat()
    }

    companion object {
        private const val FORMAT = 1

        /** Throws if [json] isn't a weight file of the format this build reads. */
        fun parse(json: String): SkipModel {
            val file = JSONObject(json)
            require(file.getInt("format") == FORMAT) { "weight file format ${file.getInt("format")}, expected $FORMAT" }
            val names = file.getJSONArray("features")
            val model = SkipModel(
                features = List(names.length()) { names.getString(it) },
                impute = file.getJSONArray("impute").toFloats(),
                weights = file.getJSONArray("weights").toFloats(),
                frameWeights = file.getJSONArray("frame_weights").toFloats(),
                bias = file.getDouble("bias").toFloat(),
                trainedOn = file.optJSONObject("trained")?.let { TrainingSummary(it.getInt("reels"), it.getString("last_day")) },
                autoThreshold = file.optDouble("auto_threshold").takeUnless { it.isNaN() }?.toFloat(),
            )
            require(model.impute.size == model.features.size && model.weights.size == model.features.size) {
                "weight file lists ${model.features.size} features but not as many weights"
            }
            return model
        }

        private fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }
    }
}
