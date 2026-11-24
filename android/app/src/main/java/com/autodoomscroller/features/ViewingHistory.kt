package com.autodoomscroller.features

import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelLabel
import com.autodoomscroller.tracking.ReelSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * What the skip model knows about a reel besides its frame: what the app reads at once, and
 * what happened on the reels before it. Must stay in sync with `decision_features` in
 * `ml/src/autodoom/features.py`; both are tested against ml/tests/fixtures/decision_features.json.
 *
 * Reels are added in the order they were watched. Not thread-safe.
 */
class ViewingHistory(private val zone: ZoneId = ZoneId.systemDefault()) {
    private class CreatorStats(var skips: Int = 0, var labeled: Int = 0, var seen: Int = 0)

    private val creators = HashMap<Pair<String, String?>, CreatorStats>()
    private var skipsTotal = 0
    private var labeledTotal = 0

    // The viewing session: a stretch of reels with no gap longer than SESSION_GAP_MS.
    private var sessionStartMs: Long? = null
    private var position = 0
    private val recentSkips = ArrayDeque<Boolean>()
    private var previousDwell = Double.NaN
    private var lastEndedAtMs = 0L

    /** Start of the latest reel added; a reel that started earlier is already counted. */
    var lastStartedAtMs: Long = Long.MIN_VALUE
        private set

    /** One value per name in [NAMES], NaN where the app couldn't read it, for a reel that began at [startedAtMs]. */
    fun features(reel: ReelSnapshot, startedAtMs: Long): FloatArray = features(
        reel.packageName, reel.creator, reel.caption, reel.audio, reel.mediaType, reel.paidPromotion,
        reel.likeCount, reel.commentCount, startedAtMs,
    )

    fun features(
        packageName: String, creator: String?, caption: String?, audio: String?, mediaType: MediaType?,
        paidPromotion: Boolean, likeCount: Long?, commentCount: Long?, startedAtMs: Long,
    ): FloatArray {
        enterSession(startedAtMs)
        val local = Instant.ofEpochMilli(startedAtMs).atZone(zone)
        val hour = local.hour + local.minute / 60.0
        val globalRate = (skipsTotal + 1.0) / (labeledTotal + 2.0)
        val stats = creators[packageName to creator]
        val text = caption.orEmpty()
        return doubleArrayOf(
            flag(packageName == YOUTUBE),
            flag(mediaType == MediaType.IMAGE),
            flag(mediaType == MediaType.CAROUSEL),
            flag(paidPromotion),
            likeCount?.let { log1p(it.toDouble()) } ?: Double.NaN,
            commentCount?.let { log1p(it.toDouble()) } ?: Double.NaN,
            flag(text.isNotEmpty()),
            log1p(text.codePointCount(0, text.length).toDouble()),
            flag(!audio.isNullOrEmpty()),
            sin(2 * PI * hour / 24),
            cos(2 * PI * hour / 24),
            log1p(position.toDouble()),
            log1p((startedAtMs - (sessionStartMs ?: startedAtMs)) / 60_000.0),
            previousDwell,
            (recentSkips.count { it } + PRIOR_STRENGTH * globalRate) / (recentSkips.size + PRIOR_STRENGTH),
            log1p((stats?.seen ?: 0).toDouble()),
            ((stats?.skips ?: 0) + PRIOR_STRENGTH * globalRate) / ((stats?.labeled ?: 0) + PRIOR_STRENGTH),
        ).let { values -> FloatArray(values.size) { values[it].toFloat() } }
    }

    /** A reel ended. [dwellMs] is the time it was on screen, without time away from the feed. */
    fun add(
        packageName: String, creator: String?, startedAtMs: Long, endedAtMs: Long, dwellMs: Long,
        isAd: Boolean, label: ReelLabel?,
    ) {
        enterSession(startedAtMs)
        position++
        lastStartedAtMs = startedAtMs
        lastEndedAtMs = endedAtMs
        // Ads say nothing about taste, and auto-skipped ones would look like fast skips.
        if (isAd) return
        previousDwell = log1p(dwellMs / 1000.0)
        val stats = creators.getOrPut(packageName to creator) { CreatorStats() }
        stats.seen++
        if (label == null) return
        val skipped = label == ReelLabel.SKIP
        recentSkips.addLast(skipped)
        if (recentSkips.size > RECENT_REELS) recentSkips.removeFirst()
        labeledTotal++
        stats.labeled++
        if (skipped) {
            skipsTotal++
            stats.skips++
        }
    }

    /** Asking twice for the same reel is harmless: nothing is counted until the reel is added. */
    private fun enterSession(startedAtMs: Long) {
        if (sessionStartMs != null && startedAtMs - lastEndedAtMs <= SESSION_GAP_MS) return
        sessionStartMs = startedAtMs
        position = 0
        recentSkips.clear()
        previousDwell = Double.NaN
    }

    private fun flag(value: Boolean) = if (value) 1.0 else 0.0

    private fun log1p(x: Double) = ln(1 + x)

    companion object {
        /** The order the skip model's weights are in. */
        val NAMES = listOf(
            "youtube", "image", "carousel", "promotion", "log_likes", "log_comments", "has_caption",
            "log_caption_len", "has_audio", "hour_sin", "hour_cos", "log_session_position",
            "log_session_minutes", "log_prev_dwell", "recent_skip_rate", "log_creator_seen", "creator_skip_rate",
        )
        private const val YOUTUBE = "com.google.android.youtube"
        private const val SESSION_GAP_MS = 5 * 60_000L
        private const val RECENT_REELS = 10
        private const val PRIOR_STRENGTH = 5.0
    }
}
