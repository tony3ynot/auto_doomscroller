package com.autodoomscroller.tracking

import android.graphics.Bitmap
import android.util.Log
import com.autodoomscroller.BuildConfig
import com.autodoomscroller.data.ReelEntity
import com.autodoomscroller.data.ReelRepository
import com.autodoomscroller.data.ThumbnailStore
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follows one reel at a time: when it started, what the user did with it, how far it played,
 * and the frames captured from it. When the reel ends it is labeled and persisted.
 *
 * Leaving the feed only pauses the reel: notification shades, heads-up notifications and quick
 * trips to DMs would otherwise cut one viewing into two records. Coming back to the same reel
 * within [RESUME_WINDOW_MS] resumes it, and the time away doesn't count as dwell.
 *
 * Called from the main thread only.
 */
class ReelTracker(
    private val repository: ReelRepository,
    private val thumbnails: ThumbnailStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: LabelConfig = LabelConfig(),
) {
    private class ActiveReel(
        var snapshot: ReelSnapshot,
        val sequence: Int,
        val startedAtMs: Long,
        val mode: SessionMode,
        /** Seen earlier (the user scrolled back), so passing through it says nothing. */
        val revisit: Boolean,
    ) {
        var openedComments = snapshot.commentsOpen
        var looped = false
        var durationMs: Long? = null
        var durationSource: DurationSource? = null
        var maxPositionMs: Long? = null
        var loopEstimateMs: Long? = null
        var carouselSwipes = 0
        var lastSlideKey: Int? = null
        var lastPositionMs: Long? = null
        var autoSkipped = false
        var undone = false
        val frames = mutableListOf<Bitmap>()
        /** When the first frame was taken, in ms on screen: the earliest moment Auto mode could decide. */
        var firstFrameMs: Long? = null
        var hiResFrame: Bitmap? = null
        var pausedAtMs: Long? = null
        var pausedTotalMs = 0L
    }

    private var current: ActiveReel? = null
    private var nextSequence = 0

    /** Creator last seen at each feed position, to recognise scrolling back. */
    private val creatorAtFeedIndex = HashMap<Pair<String, Int>, String>()

    /** Fallback for apps that don't report a feed position. */
    private val recentReels = ArrayDeque<ReelSnapshot>()

    /** Changes with every new reel; used to drop frames that arrive after the reel changed. */
    val currentSequence: Int?
        get() = current?.sequence

    val currentSnapshot: ReelSnapshot?
        get() = current?.snapshot

    /** Time the current reel has been on screen, excluding pauses; null while paused. */
    val currentElapsedMs: Long?
        get() = current?.takeIf { it.pausedAtMs == null }?.let { clock() - it.startedAtMs - it.pausedTotalMs }

    val currentPaused: Boolean
        get() = current?.pausedAtMs != null

    /** Frame-based loop detection already produced an estimate, so it can stop sampling. */
    val currentLoopEstimated: Boolean
        get() = current?.loopEstimateMs != null

    /** The current reel has no full-size frame yet; the first kept frame provides it. */
    val currentNeedsHiResFrame: Boolean
        get() = current?.let { it.hiResFrame == null } == true

    val currentAutoSkipped: Boolean
        get() = current?.autoSkipped == true

    val currentUndone: Boolean
        get() = current?.undone == true

    /** Returns true if [snapshot] is a different reel from the one being tracked. */
    fun onReelObserved(snapshot: ReelSnapshot, mode: SessionMode): Boolean {
        val now = clock()
        val active = current
        val pausedAt = active?.pausedAtMs
        val resumable = pausedAt == null || now - pausedAt <= RESUME_WINDOW_MS
        if (active != null && resumable && active.snapshot.isSameReelAs(snapshot)) {
            if (pausedAt != null) {
                active.pausedTotalMs += now - pausedAt
                active.pausedAtMs = null
            }
            observe(active, snapshot)
            active.snapshot = active.snapshot.mergedWith(snapshot)
            if (snapshot.commentsOpen) active.openedComments = true
            return false
        }
        if (active != null) {
            // A paused reel that didn't come back ended when the user left, without a swipe.
            if (pausedAt != null) finish(active, pausedAt, movedToNext = false) else finish(active, now, movedToNext = true)
        }
        val revisit = isRevisit(snapshot)
        remember(snapshot)
        current = ActiveReel(snapshot, nextSequence++, now, mode, revisit).also { observe(it, snapshot) }
        return true
    }

    /** Same feed position and creator as before. Without positions, require a matching caption. */
    private fun isRevisit(snapshot: ReelSnapshot): Boolean {
        val index = snapshot.feedIndex
        if (index != null) return creatorAtFeedIndex[snapshot.packageName to index] == snapshot.creator
        return recentReels.any {
            it.packageName == snapshot.packageName && it.creator == snapshot.creator &&
                it.caption != null && it.caption == snapshot.caption
        }
    }

    private fun remember(snapshot: ReelSnapshot) {
        snapshot.feedIndex?.let { creatorAtFeedIndex[snapshot.packageName to it] = snapshot.creator }
        recentReels.addLast(snapshot)
        if (recentReels.size > RECENT_REELS) recentReels.removeFirst()
    }

    private fun observe(reel: ActiveReel, snapshot: ReelSnapshot) {
        snapshot.slideKey?.let { key ->
            if (reel.lastSlideKey != null && key != reel.lastSlideKey) reel.carouselSwipes++
            reel.lastSlideKey = key
        }
        observePlayback(reel, snapshot)
    }

    /** Scrubber readings: exact length, furthest point reached, and wrap-around = loop. */
    private fun observePlayback(reel: ActiveReel, snapshot: ReelSnapshot) {
        val position = snapshot.positionMs ?: return
        val duration = snapshot.durationMs
        if (duration != null) {
            reel.durationMs = duration
            reel.durationSource = DurationSource.SCRUBBER
        }
        val last = reel.lastPositionMs
        if (last != null && duration != null && last - position > duration / 2) reel.looped = true
        reel.lastPositionMs = position
        reel.maxPositionMs = maxOf(reel.maxPositionMs ?: 0L, position)
    }

    fun addFrame(sequence: Int, frame: Bitmap, elapsedMs: Long) {
        val active = current
        if (active == null || active.sequence != sequence) {
            frame.recycle()
            return
        }
        if (active.frames.isEmpty()) active.firstFrameMs = elapsedMs
        active.frames.add(frame)
    }

    fun setHiResFrame(sequence: Int, frame: Bitmap) {
        val active = current
        if (active != null && active.sequence == sequence && active.hiResFrame == null) {
            active.hiResFrame = frame
        } else {
            frame.recycle()
        }
    }

    /**
     * Frame-based loop detection saw the video start over after [estimatedDurationMs].
     * The estimate is always recorded; it only becomes the reel's length (and counts as a loop)
     * when there is no scrubber, which is more reliable.
     */
    fun onLoopEstimated(estimatedDurationMs: Long) {
        val active = current ?: return
        if (active.loopEstimateMs != null) return
        active.loopEstimateMs = estimatedDurationMs
        if (active.durationSource != DurationSource.SCRUBBER) {
            active.looped = true
            active.durationMs = estimatedDurationMs
            active.durationSource = DurationSource.LOOP
        }
    }

    /** Swiped past automatically (model or ad skip); the user never really watched it. */
    fun markAutoSkipped() {
        current?.autoSkipped = true
    }

    /** The user came back to a reel that was skipped automatically: a strong "should have watched". */
    fun markUndone() {
        current?.undone = true
    }

    /** Comments of the current reel are open: engagement, and time that counts as dwell. */
    fun onCommentsOpen() {
        val active = current ?: return
        active.pausedAtMs?.let { pausedAt ->
            if (clock() - pausedAt <= RESUME_WINDOW_MS) {
                active.pausedTotalMs += clock() - pausedAt
                active.pausedAtMs = null
            } else {
                return
            }
        }
        active.openedComments = true
    }

    /** The user left the feed; see the class comment. */
    fun pause() {
        val active = current ?: return
        if (active.pausedAtMs == null) active.pausedAtMs = clock()
    }

    /** Ends a paused reel the user didn't come back to within [RESUME_WINDOW_MS]. */
    fun expirePause() {
        val active = current ?: return
        val pausedAt = active.pausedAtMs ?: return
        if (clock() - pausedAt < RESUME_WINDOW_MS) return
        finish(active, pausedAt, movedToNext = false)
        current = null
    }

    /** Tracking turned off. */
    fun stop() = endCurrent(movedToNext = false)

    /** The current reel ended without a new reel taking its place (e.g. an ad collage came next). */
    fun endCurrent(movedToNext: Boolean) {
        current?.let { reel ->
            val pausedAt = reel.pausedAtMs
            if (pausedAt != null) finish(reel, pausedAt, movedToNext = false) else finish(reel, clock(), movedToNext)
        }
        current = null
    }

    private fun finish(reel: ActiveReel, endedAtMs: Long, movedToNext: Boolean) {
        val snapshot = reel.snapshot
        val liked = snapshot.liked == true
        val saved = snapshot.saved == true
        val dwellMs = endedAtMs - reel.startedAtMs - reel.pausedTotalMs
        val completed = ReelLabeler.isCompleted(reel.looped, reel.maxPositionMs, reel.durationMs, config)
        val engaged = liked || saved || reel.openedComments || reel.carouselSwipes > 0
        val label = when {
            // Ads aren't content the user chose; their behaviour says little about taste.
            snapshot.isAd -> null
            reel.autoSkipped -> null
            reel.undone -> ReelLabel.WATCH
            else -> ReelLabeler.label(dwellMs, reel.durationMs, completed, engaged, movedToNext, config)
                // Scrolling back through reels already seen isn't a judgement on them.
                ?.takeUnless { it == ReelLabel.SKIP && reel.revisit }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "${label ?: "-"} ${dwellMs}ms @${snapshot.creator} ${snapshot.mediaType}" +
                    (if (snapshot.isAd) " AD" else "") +
                    (if (snapshot.paidPromotion) " PROMO" else "") +
                    (if (reel.revisit) " revisit" else "") +
                    (if (reel.pausedTotalMs > 0) " paused=${reel.pausedTotalMs}ms" else "") +
                    " dur=${reel.durationMs ?: "?"}(${reel.durationSource ?: "-"}) max=${reel.maxPositionMs ?: "?"}" +
                    " est=${reel.loopEstimateMs ?: "?"}" +
                    " looped=${reel.looped} liked=$liked" +
                    (if (reel.carouselSwipes > 0) " swipes=${reel.carouselSwipes}" else "") +
                    (if (reel.openedComments) " comments" else "") +
                    " frames=${reel.frames.size}" +
                    (reel.firstFrameMs?.let { " first=${it}ms" } ?: "") +
                    (if (reel.autoSkipped) " auto-skipped" else ""),
            )
        }

        val sessionId = SessionController.sessionId
        val frames = reel.frames.toList()
        val hiResFrame = reel.hiResFrame
        if (sessionId == null) {
            frames.forEach(Bitmap::recycle)
            hiResFrame?.recycle()
            return
        }
        val entity = ReelEntity(
            sessionId = sessionId,
            packageName = snapshot.packageName,
            reelKey = snapshot.key,
            creator = snapshot.creator,
            caption = snapshot.caption,
            audio = snapshot.audio,
            mediaType = snapshot.mediaType,
            isAd = snapshot.isAd,
            paidPromotion = snapshot.paidPromotion,
            mode = reel.mode,
            startedAtMs = reel.startedAtMs,
            endedAtMs = endedAtMs,
            pausedMs = reel.pausedTotalMs,
            movedToNext = movedToNext,
            looped = reel.looped,
            durationMs = reel.durationMs,
            durationSource = reel.durationSource,
            maxPositionMs = reel.maxPositionMs,
            loopEstimateMs = reel.loopEstimateMs,
            liked = liked,
            saved = saved,
            openedComments = reel.openedComments,
            carouselSwipes = reel.carouselSwipes,
            likeCount = snapshot.likeCount,
            commentCount = snapshot.commentCount,
            autoSkipped = reel.autoSkipped,
            undone = reel.undone,
            revisit = reel.revisit,
            feedIndex = snapshot.feedIndex,
            label = label,
            frameCount = frames.size,
            firstFrameMs = reel.firstFrameMs,
        )
        val keepFrames = SessionController.researchMode.value
        scope.launch {
            val reelId = repository.saveReel(entity)
            if (keepFrames) {
                frames.forEachIndexed { index, frame -> thumbnails.save(reelId, index, frame) }
                hiResFrame?.let { thumbnails.saveHiRes(reelId, it) }
            }
            frames.forEach(Bitmap::recycle)
            hiResFrame?.recycle()
        }
    }

    companion object {
        private const val TAG = "ReelTracker"
        private const val RECENT_REELS = 200
        const val RESUME_WINDOW_MS = 30_000L
    }
}
