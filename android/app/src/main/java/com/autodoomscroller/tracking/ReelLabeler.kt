package com.autodoomscroller.tracking

enum class ReelLabel { SKIP, WATCH }

/** Initial thresholds. Tune them from the dwell-time distribution of real data. */
data class LabelConfig(
    val skipMaxDwellMs: Long = 2_000,
    val watchMinDwellMs: Long = 5_000,
    /** Share of the length that counts as watched to the end. */
    val completionRatio: Double = 0.9,
)

/**
 * Turns what the user did with a reel into a training label. Must stay in sync with
 * `ml/src/autodoom/labels.py`, which re-applies the same rule offline.
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

    /**
     * @param liked / saved button state when the reel ended.
     * @param movedToNext the reel ended because the user swiped to the next one
     *   (as opposed to leaving the feed or turning the screen off).
     * @return null when the behaviour is too ambiguous to learn from.
     */
    fun label(
        dwellMs: Long,
        completed: Boolean,
        liked: Boolean,
        saved: Boolean,
        movedToNext: Boolean,
        config: LabelConfig = LabelConfig(),
    ): ReelLabel? = when {
        liked || saved || completed || dwellMs >= config.watchMinDwellMs -> ReelLabel.WATCH
        movedToNext && dwellMs < config.skipMaxDwellMs -> ReelLabel.SKIP
        else -> null
    }
}
