package com.autodoomscroller.tracking

enum class ReelLabel { SKIP, WATCH }

/** Initial thresholds. Tune them from the dwell-time distribution of real data. */
data class LabelConfig(
    val skipMaxDwellMs: Long = 2_000,
    val watchMinDwellMs: Long = 20_000,
)

/**
 * Turns what the user did with a reel into a training label. Must stay in sync with
 * `ml/src/autodoom/labels.py`, which re-applies the same rule offline.
 */
object ReelLabeler {

    /**
     * @param movedToNext the reel ended because the user swiped to the next one
     *   (as opposed to leaving the app or turning the screen off).
     * @return null when the behaviour is too ambiguous to learn from.
     */
    fun label(
        dwellMs: Long,
        looped: Boolean,
        liked: Boolean,
        movedToNext: Boolean,
        config: LabelConfig = LabelConfig(),
    ): ReelLabel? = when {
        liked || looped || dwellMs >= config.watchMinDwellMs -> ReelLabel.WATCH
        movedToNext && dwellMs < config.skipMaxDwellMs -> ReelLabel.SKIP
        else -> null
    }
}
