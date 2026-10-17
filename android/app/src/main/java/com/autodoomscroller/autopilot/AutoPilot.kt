package com.autodoomscroller.autopilot

import com.autodoomscroller.service.GestureDispatcher
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode

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
    private var lastSkippedKey: String? = null

    /** Returns true if it swiped to the next reel. */
    fun onPrediction(reelKey: String, skipProbability: Float?): Boolean {
        if (SessionController.mode.value != SessionMode.AUTO) return false
        if (skipProbability == null || skipProbability < config.skipThreshold) return false
        val now = clock()
        val last = lastSkipAtMs
        if (last != null && now - last < config.cooldownMs) return false
        lastSkipAtMs = now
        lastSkippedKey = reelKey
        gestures.swipeUp()
        return true
    }

    fun isUndo(reelKey: String): Boolean {
        val last = lastSkipAtMs ?: return false
        return reelKey == lastSkippedKey && clock() - last <= config.undoWindowMs
    }
}
