package com.autodoomscroller.handsfree

/**
 * What the front camera saw in one frame. Scores are MediaPipe's, 0 to 1; null when there was
 * no face (blinks) or no hand (thumbs-up) in the frame.
 */
data class GestureFrame(
    val atMs: Long,
    val blinkLeft: Float?,
    val blinkRight: Float?,
    val thumbUp: Float?,
)

sealed interface HandsFreeCommand {
    /** When the user made up their mind: the start of the gesture, not its end. */
    val decidedAtMs: Long

    /** Go to the next reel. */
    data class Next(override val decidedAtMs: Long, val by: NextGesture) : HandsFreeCommand

    /** A thumbs-up held for a moment: like the reel. */
    data class Like(override val decidedAtMs: Long) : HandsFreeCommand
}

enum class NextGesture {
    /** Two winks in a row. */
    DOUBLE_WINK,

    /** Both eyes shut for about a second. */
    LONG_BLINK,
}

data class GestureConfig(
    /** Blink score above which the winking eye counts as closed. */
    val closed: Float = 0.45f,
    /**
     * How much more closed the winking eye must be than the other. A wink squints the other eye
     * as well, and a natural blink closes both about equally.
     */
    val margin: Float = 0.25f,
    /** Below this both eyes count as open, and their resting difference is learned. */
    val restingBelow: Float = 0.35f,
    /** How quickly the resting difference follows the face, per frame. */
    val restingRate: Float = 0.05f,
    val minWinkMs: Long = 60,
    val maxWinkMs: Long = 700,
    /** The second wink has to start within this long of the first. */
    val doubleWinkWithinMs: Long = 1_200,
    /** Both eyes count as shut once both score above this... */
    val shut: Float = 0.5f,
    /** ...and stay shut while both score above this. */
    val stillShut: Float = 0.4f,
    /** Natural blinks last 0.1 to 0.4 s. */
    val longBlinkMs: Long = 800,
    /** Longer than this is resting the eyes, not a command. */
    val maxLongBlinkMs: Long = 2_500,
    val thumbScore: Float = 0.5f,
    val thumbHoldMs: Long = 500,
    /** Frames where the hand briefly isn't recognised don't end a hold. */
    val thumbGapMs: Long = 250,
    /** No second command this soon after one, so a single gesture can't fire twice. */
    val cooldownMs: Long = 1_000,
)

/**
 * Turns a stream of camera frames into commands. A double wink or a long blink means "next", a
 * held thumbs-up means "like". Natural blinks are too short and too even to count, and a
 * thumbs-up likes once per raise, however long it is held.
 *
 * Winks are hard to tell from blinks: for many people a wink squints the other eye nearly shut,
 * so only a clear wink counts. A long blink is the dependable way to move on.
 */
class GestureInterpreter(private val config: GestureConfig = GestureConfig()) {
    private var winkStartMs: Long? = null
    private var firstWinkStartMs: Long? = null
    private var shutSinceMs: Long? = null
    private var thumbSinceMs: Long? = null
    private var thumbLastSeenMs: Long? = null
    private var likedThisRaise = false
    private var cooldownUntilMs = Long.MIN_VALUE

    /** Open eyes rarely score the same: one lid sits lower, or the head is turned. */
    private var restingDiff = 0f

    /** The last frame looked like a wink. For debug logs. */
    var winking = false
        private set

    /** Frames must come in time order. */
    fun onFrame(frame: GestureFrame): HandsFreeCommand? {
        // All run on every frame so their state stays current through a cooldown.
        val wink = wink(frame)
        val longBlink = longBlink(frame)
        val like = thumb(frame)
        val command = wink ?: longBlink ?: like ?: return null
        if (frame.atMs < cooldownUntilMs) return null
        cooldownUntilMs = frame.atMs + config.cooldownMs
        return command
    }

    private fun wink(frame: GestureFrame): HandsFreeCommand? {
        val left = frame.blinkLeft
        val right = frame.blinkRight
        winking = left != null && right != null && isWink(left, right)
        firstWinkStartMs?.let { if (frame.atMs - it > config.doubleWinkWithinMs) firstWinkStartMs = null }

        val start = winkStartMs
        if (winking) {
            if (start == null) winkStartMs = frame.atMs
            return null
        }
        if (start == null) return null
        winkStartMs = null
        if (frame.atMs - start !in config.minWinkMs..config.maxWinkMs) return null
        val first = firstWinkStartMs
        if (first == null) {
            firstWinkStartMs = start
            return null
        }
        firstWinkStartMs = null
        return HandsFreeCommand.Next(decidedAtMs = first, by = NextGesture.DOUBLE_WINK)
    }

    /** Counts when the eyes open again, so resting them for longer doesn't skip anything. */
    private fun longBlink(frame: GestureFrame): HandsFreeCommand? {
        val left = frame.blinkLeft
        val right = frame.blinkRight
        val since = shutSinceMs
        if (left == null || right == null) {
            // Lost the face: the eyes weren't seen to open.
            shutSinceMs = null
            return null
        }
        if (minOf(left, right) >= if (since == null) config.shut else config.stillShut) {
            if (since == null) shutSinceMs = frame.atMs
            return null
        }
        if (since == null) return null
        shutSinceMs = null
        if (frame.atMs - since !in config.longBlinkMs..config.maxLongBlinkMs) return null
        return HandsFreeCommand.Next(decidedAtMs = since, by = NextGesture.LONG_BLINK)
    }

    private fun isWink(left: Float, right: Float): Boolean {
        if (left < config.restingBelow && right < config.restingBelow) {
            restingDiff += config.restingRate * (left - right - restingDiff)
            return false
        }
        val diff = left - right - restingDiff
        return (left >= config.closed && diff >= config.margin) || (right >= config.closed && -diff >= config.margin)
    }

    private fun thumb(frame: GestureFrame): HandsFreeCommand? {
        val lastSeen = thumbLastSeenMs
        if ((frame.thumbUp ?: 0f) < config.thumbScore) {
            if (lastSeen != null && frame.atMs - lastSeen > config.thumbGapMs) {
                thumbSinceMs = null
                thumbLastSeenMs = null
                likedThisRaise = false
            }
            return null
        }
        val since = thumbSinceMs ?: frame.atMs.also { thumbSinceMs = it }
        thumbLastSeenMs = frame.atMs
        if (likedThisRaise || frame.atMs - since < config.thumbHoldMs) return null
        likedThisRaise = true
        return HandsFreeCommand.Like(decidedAtMs = since)
    }
}
