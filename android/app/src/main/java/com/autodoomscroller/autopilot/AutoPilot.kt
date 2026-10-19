package com.autodoomscroller.autopilot

import com.autodoomscroller.service.GestureDispatcher
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import com.autodoomscroller.tracking.ReelSnapshot

data class AutoPilotConfig(
    /** Chosen on validation data so that at least 90% of auto skips are correct. */
    val skipThreshold: Float = 0.9f,
    val cooldownMs: Long = 1_200,
    /** Coming back to a skipped reel within this window counts as an undo. */
    val undoWindowMs: Long = 10_000,
)

/** Skips a reel only when the model is confident the user would have skipped it too. */
class AutoPilot(
    private val gestures: GestureDispatcher,
    private val config: AutoPilotConfig = AutoPilotConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastSkipAtMs: Long? = null
    private var lastSkipped: ReelSnapshot? = null

    /** Returns true if it swiped to the next reel. */
    fun onPrediction(reel: ReelSnapshot, skipProbability: Float?): Boolean {
        if (SessionController.mode.value != SessionMode.AUTO) return false
        if (skipProbability == null || skipProbability < config.skipThreshold) return false
        return skip(reel)
    }

    /** Ad skip is rule-based, so it works in any mode once the user turns it on. */
    fun onAd(reel: ReelSnapshot): Boolean {
        if (!SessionController.adSkip.value || !reel.isAd) return false
        return skip(reel)
    }

    /** An ad that isn't a regular reel (e.g. a multi-ad collage). */
    fun onAdItem(): Boolean {
        if (!SessionController.adSkip.value) return false
        return skip(null)
    }

    private fun skip(reel: ReelSnapshot?): Boolean {
        val now = clock()
        val last = lastSkipAtMs
        if (last != null && now - last < config.cooldownMs) return false
        lastSkipAtMs = now
        lastSkipped = reel
        gestures.swipeUp()
        return true
    }

    fun isUndo(reel: ReelSnapshot): Boolean {
        val last = lastSkipAtMs ?: return false
        val skipped = lastSkipped ?: return false
        return skipped.isSameReelAs(reel) && clock() - last <= config.undoWindowMs
    }
}
