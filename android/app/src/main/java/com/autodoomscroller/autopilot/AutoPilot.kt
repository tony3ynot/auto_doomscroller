package com.autodoomscroller.autopilot

import com.autodoomscroller.service.GestureDispatcher
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import com.autodoomscroller.tracking.ReelSnapshot

data class AutoPilotConfig(
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

    /**
     * Whether Auto mode wants this reel skipped. [threshold] comes with the model: the
     * probability from which it was right at least nine times in ten on its most recent days,
     * or null if there is no such point yet.
     */
    fun wantsSkip(skipProbability: Float, threshold: Float?): Boolean =
        SessionController.mode.value == SessionMode.AUTO && threshold != null && skipProbability >= threshold

    /** How long until the next swipe is allowed; 0 when it is now. */
    fun cooldownLeftMs(): Long = lastSkipAtMs?.let { (it + config.cooldownMs - clock()).coerceAtLeast(0) } ?: 0

    /** Returns true if it swiped to the next reel; false if Auto mode doesn't want to or it is too soon. */
    fun onPrediction(reel: ReelSnapshot, skipProbability: Float, threshold: Float?): Boolean =
        wantsSkip(skipProbability, threshold) && skip(reel)

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
