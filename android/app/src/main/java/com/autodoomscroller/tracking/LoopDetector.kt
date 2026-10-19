package com.autodoomscroller.tracking

/**
 * Estimates a video's length for reels without a scrubber. The first frames of a reel are
 * kept as references; once later frames have clearly moved away from them, a frame that
 * matches a reference again means the video started over.
 *
 * Talking-head or near-static videos may never diverge (no estimate) or match by accident;
 * the estimate is checked against reels with a scrubber in the evaluation.
 */
class LoopDetector(
    private val maxReferences: Int = 3,
    private val divergeBelow: Float = 0.80f,
    private val matchAbove: Float = 0.95f,
    private val minDurationMs: Long = 2_000,
) {
    private val references = mutableListOf<Pair<FloatArray, Long>>()
    private var diverged = false

    /** Feed one frame taken [atMs] after the reel started. Returns the length once it loops. */
    fun add(fingerprint: FloatArray, atMs: Long): Long? {
        if (Fingerprint.isFlat(fingerprint)) return null
        if (references.size < maxReferences) {
            references += fingerprint to atMs
            return null
        }
        val (similarity, referenceAtMs) = references
            .map { (reference, at) -> Fingerprint.similarity(reference, fingerprint) to at }
            .maxBy { it.first }
        if (similarity < divergeBelow) {
            diverged = true
            return null
        }
        val durationMs = atMs - referenceAtMs
        return if (diverged && similarity >= matchAbove && durationMs >= minDurationMs) durationMs else null
    }
}
