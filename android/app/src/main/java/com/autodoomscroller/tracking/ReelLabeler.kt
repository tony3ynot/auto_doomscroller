package com.autodoomscroller.tracking

enum class ReelLabel { SKIP, WATCH }

/** Initial thresholds. Tune them from the dwell-time distribution of real data. */
data class LabelConfig(
    val skipMaxDwellMs: Long = 2_000,
    val watchMinDwellMs: Long = 5_000,
    /** Share of the length that counts as watched to the end. */
    val completionRatio: Double = 0.9,
    /** Staying this many times the length without doing anything looks like the phone was left alone. */
    val idleLengthMultiple: Double = 3.0,
    val idleMinDwellMs: Long = 30_000,
    /** Idle cut-off when the length is unknown (images, carousels, videos without an estimate). */
    val idleUnknownLengthDwellMs: Long = 60_000,
)

/**
 * Turns what the user did with a reel into a training label. Must stay in sync with
 * `ml/src/autodoom/labels.py`, which re-applies the same rule offline.
 *
 * The label answers the question Auto mode faces in the first second and a half: would the
 * user swipe away on first impression? That doesn't depend on the reel's length or type, so
 * WATCH means "gave it a chance", not "watched it all". How strongly the user liked something
 * is analysed separately from the raw signals.
 */
object ReelLabeler {

    /** Watched to the end: it started over, or the scrubber got close enough to the end. */
    fun isCompleted(
        looped: Boolean,
        maxPositionMs: Long?,
        durationMs: Long?,
        config: LabelConfig = LabelConfig(),
    ): Boolean = looped ||
        (maxPositionMs != null && durationMs != null && durationMs > 0 && maxPositionMs >= config.completionRatio * durationMs)

    fun isIdle(dwellMs: Long, durationMs: Long?, config: LabelConfig = LabelConfig()): Boolean {
        val limit = durationMs?.let { maxOf(config.idleMinDwellMs.toDouble(), it * config.idleLengthMultiple) }
            ?: config.idleUnknownLengthDwellMs.toDouble()
        return dwellMs > limit
    }

    /**
     * @param engaged liked, saved, opened the comments or swiped a carousel.
     * @param movedToNext the reel ended because the user swiped to the next one
     *   (as opposed to leaving the feed or turning the screen off).
     * @return null when the behaviour is too ambiguous to learn from.
     */
    fun label(
        dwellMs: Long,
        durationMs: Long?,
        completed: Boolean,
        engaged: Boolean,
        movedToNext: Boolean,
        config: LabelConfig = LabelConfig(),
    ): ReelLabel? = when {
        engaged -> ReelLabel.WATCH
        isIdle(dwellMs, durationMs, config) -> null
        completed || dwellMs >= config.watchMinDwellMs -> ReelLabel.WATCH
        movedToNext && dwellMs < config.skipMaxDwellMs -> ReelLabel.SKIP
        else -> null
    }
}
