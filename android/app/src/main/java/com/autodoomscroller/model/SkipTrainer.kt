package com.autodoomscroller.model

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One labeled reel as the trainer sees it. */
class TrainingExample(
    /** Decision-time features, NaN where one is missing. */
    val features: FloatArray,
    /** The L2-normalised frame embedding. */
    val frame: FloatArray,
    val skipped: Boolean,
    /** The local calendar day it was watched on, as an epoch day. */
    val day: Long,
)

/**
 * Fits the skip model on the phone: the frame embedding's leading principal components and
 * the decision-time features (median for a missing one), standardised, into an L2 logistic
 * regression. It is the model `skip_model` builds offline (ml/src/autodoom/model.py), folded
 * into weights the way ml/src/autodoom/deploy.py folds it, and both are tested against
 * ml/tests/fixtures/training.json.
 */
object SkipTrainer {
    const val COMPONENTS = 8
    const val C = 1.0
    const val AUTO_DAYS = 14
    const val AUTO_PRECISION = 0.9
    const val AUTO_MIN_SKIPS = 30

    /** Fewer examples than this, or only one outcome among them, and there is nothing to fit. */
    const val MIN_EXAMPLES = 50

    class Scored(val probability: Float, val skipped: Boolean)

    class AutoThreshold(val threshold: Float, val precision: Float, val skips: Int, val coverage: Float)

    /**
     * The model fitted on all of [examples], with the Auto mode threshold its recent days
     * earned, or null when there is too little to learn from.
     */
    fun train(examples: List<TrainingExample>, names: List<String>): SkipModel? {
        if (!fittable(examples)) return null
        val ordered = examples.sortedBy { it.day }
        val moments = Moments(ordered[0].frame.size).also { m -> ordered.forEach { m.add(it.frame) } }
        val auto = rollingScores(ordered, names, AUTO_DAYS)?.let { autoThreshold(it) }
        return fit(ordered, moments, names, COMPONENTS, C).copy(
            trainedOn = SkipModel.TrainingSummary(ordered.size, LocalDate.ofEpochDay(ordered.last().day).toString()),
            autoThreshold = auto?.threshold,
        )
    }

    fun fit(examples: List<TrainingExample>, names: List<String>, components: Int = COMPONENTS, c: Double = C): SkipModel {
        val moments = Moments(examples[0].frame.size).also { m -> examples.forEach { m.add(it.frame) } }
        return fit(examples, moments, names, components, c)
    }

    /**
     * Every reel of the last [days] days, scored by a model fitted on the days before its own.
     * Null when there aren't that many days after the first two, which only ever train.
     */
    fun rollingScores(
        examples: List<TrainingExample>, names: List<String>, days: Int,
        components: Int = COMPONENTS, c: Double = C,
    ): List<Scored>? {
        val ordered = examples.sortedBy { it.day }
        val allDays = ordered.map { it.day }.distinct()
        if (allDays.size - 2 < days) return null
        val testDays = allDays.takeLast(days)
        val moments = Moments(ordered[0].frame.size)
        var seen = 0
        val scores = mutableListOf<Scored>()
        for (day in testDays) {
            while (seen < ordered.size && ordered[seen].day < day) moments.add(ordered[seen++].frame)
            val earlier = ordered.subList(0, seen)
            if (!fittable(earlier, minimum = 2)) return null
            val model = fit(earlier, moments, names, components, c)
            ordered.filter { it.day == day }.forEach { scores += Scored(model.predictSkip(it.features, it.frame), it.skipped) }
        }
        return scores
    }

    /**
     * The lowest P(skip) from which Auto mode would have been right often enough on [scores]:
     * at least [minSkips] reels at or above it, [precision] of them really skipped.
     */
    fun autoThreshold(
        scores: List<Scored>, precision: Double = AUTO_PRECISION, minSkips: Int = AUTO_MIN_SKIPS,
    ): AutoThreshold? {
        val allSkips = scores.count { it.skipped }
        if (allSkips == 0) return null
        for (step in 0 until 10) {
            val threshold = (50 + 5 * step) / 100.0
            val above = scores.filter { it.probability >= threshold }
            val right = above.count { it.skipped }
            if (above.size >= minSkips && right >= precision * above.size) {
                return AutoThreshold(threshold.toFloat(), right.toFloat() / above.size, above.size, right.toFloat() / allSkips)
            }
        }
        return null
    }

    private fun fittable(examples: List<TrainingExample>, minimum: Int = MIN_EXAMPLES) =
        examples.size >= minimum && examples.any { it.skipped } && examples.any { !it.skipped }

    /** [moments] are those of the frames of [examples]. */
    private fun fit(
        examples: List<TrainingExample>, moments: Moments, names: List<String>, components: Int, c: Double,
    ): SkipModel {
        val n = examples.size
        val featureCount = names.size
        val frameSize = moments.size
        val k = minOf(components, frameSize)

        val frameMean = moments.mean()
        val directions = leadingEigenvectors(moments.scatter(), frameSize, k)
        val medians = DoubleArray(featureCount) { j -> median(examples.mapNotNull { e -> e.features[j].takeUnless { it.isNaN() }?.toDouble() }) }

        // One row per example: the principal components, then the features.
        val width = k + featureCount
        val rows = Array(n) { i ->
            val example = examples[i]
            DoubleArray(width) { j ->
                if (j < k) {
                    var projection = 0.0
                    val direction = directions[j]
                    for (m in 0 until frameSize) projection += (example.frame[m] - frameMean[m]) * direction[m]
                    projection
                } else {
                    example.features[j - k].let { if (it.isNaN()) medians[j - k] else it.toDouble() }
                }
            }
        }
        val means = DoubleArray(width) { j -> rows.sumOf { it[j] } / n }
        val scales = DoubleArray(width) { j ->
            sqrt(rows.sumOf { (it[j] - means[j]) * (it[j] - means[j]) } / n).let { if (it > 0) it else 1.0 }
        }
        for (row in rows) for (j in 0 until width) row[j] = (row[j] - means[j]) / scales[j]

        val (coefficients, intercept) = logisticRegression(rows, BooleanArray(n) { examples[it].skipped }, c)

        // Fold projection and scaling into one weight per input, as deploy.py does.
        val perInput = DoubleArray(width) { coefficients[it] / scales[it] }
        val frameWeights = DoubleArray(frameSize) { m -> (0 until k).sumOf { perInput[it] * directions[it][m] } }
        val bias = intercept - (0 until width).sumOf { perInput[it] * means[it] } -
            (0 until frameSize).sumOf { frameMean[it] * frameWeights[it] }
        return SkipModel(
            features = names,
            impute = FloatArray(featureCount) { medians[it].toFloat() },
            weights = FloatArray(featureCount) { perInput[k + it].toFloat() },
            frameWeights = FloatArray(frameSize) { frameWeights[it].toFloat() },
            bias = bias.toFloat(),
        )
    }

    /**
     * Minimises the log loss plus |w|² / 2C by Newton's method; the intercept isn't penalised.
     * Returns the coefficients and the intercept.
     */
    private fun logisticRegression(rows: Array<DoubleArray>, skipped: BooleanArray, c: Double): Pair<DoubleArray, Double> {
        val width = rows[0].size
        val size = width + 1 // the intercept comes last
        var theta = DoubleArray(size)
        var loss = objective(rows, skipped, theta, c)
        repeat(50) {
            val gradient = DoubleArray(size)
            val hessian = DoubleArray(size * size)
            for (i in rows.indices) {
                val row = rows[i]
                val p = sigmoid(score(row, theta))
                val error = p - if (skipped[i]) 1.0 else 0.0
                val curve = p * (1 - p)
                for (a in 0 until size) {
                    val xa = if (a < width) row[a] else 1.0
                    gradient[a] += error * xa
                    val scaled = curve * xa
                    for (b in a until size) hessian[a * size + b] += scaled * (if (b < width) row[b] else 1.0)
                }
            }
            for (a in 0 until width) {
                gradient[a] += theta[a] / c
                hessian[a * size + a] += 1 / c
            }
            for (a in 0 until size) for (b in 0 until a) hessian[a * size + b] = hessian[b * size + a]
            val step = solvePositiveDefinite(hessian, size, gradient)

            var scale = 1.0
            var next = DoubleArray(size) { theta[it] - step[it] }
            var nextLoss = objective(rows, skipped, next, c)
            while (nextLoss > loss && scale > 1e-4) {
                scale /= 2
                next = DoubleArray(size) { theta[it] - scale * step[it] }
                nextLoss = objective(rows, skipped, next, c)
            }
            val moved = (0 until size).maxOf { abs(next[it] - theta[it]) }
            theta = next
            loss = nextLoss
            if (moved < 1e-10) return theta.copyOf(width) to theta[width]
        }
        return theta.copyOf(width) to theta[width]
    }

    private fun objective(rows: Array<DoubleArray>, skipped: BooleanArray, theta: DoubleArray, c: Double): Double {
        var loss = 0.0
        for (i in rows.indices) {
            val z = score(rows[i], theta)
            // log(1 + e^z) - y z, written so that a large |z| doesn't overflow
            loss += (if (z > 0) z + ln(1 + exp(-z)) else ln(1 + exp(z))) - if (skipped[i]) z else 0.0
        }
        var penalty = 0.0
        for (a in 0 until theta.size - 1) penalty += theta[a] * theta[a]
        return loss + penalty / (2 * c)
    }

    private fun score(row: DoubleArray, theta: DoubleArray): Double {
        var z = theta[row.size]
        for (j in row.indices) z += row[j] * theta[j]
        return z
    }

    private fun sigmoid(z: Double) = 1 / (1 + exp(-z))

    /** The middle value, or the mean of the two middle ones; 0 when the feature was never read. */
    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
