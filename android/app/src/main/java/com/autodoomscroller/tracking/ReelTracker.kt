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
        var lastPositionMs: Long? = null
        var autoSkipped = false
        var undone = false
        val frames = mutableListOf<Bitmap>()
    }

    private var current: ActiveReel? = null
    private var nextSequence = 0
    private val recentReels = ArrayDeque<ReelSnapshot>()

    /** Changes with every new reel; used to drop frames that arrive after the reel changed. */
    val currentSequence: Int?
        get() = current?.sequence

    val currentSnapshot: ReelSnapshot?
        get() = current?.snapshot

    val currentElapsedMs: Long?
        get() = current?.let { clock() - it.startedAtMs }

    /** Frame-based loop detection already produced an estimate, so it can stop sampling. */
    val currentLoopEstimated: Boolean
        get() = current?.loopEstimateMs != null

    val currentAutoSkipped: Boolean
        get() = current?.autoSkipped == true

    val currentUndone: Boolean
        get() = current?.undone == true

    /** Returns true if [snapshot] is a different reel from the one being tracked. */
    fun onReelObserved(snapshot: ReelSnapshot, mode: SessionMode): Boolean {
        val now = clock()
        val active = current
        if (active != null && active.snapshot.isSameReelAs(snapshot)) {
            observePlayback(active, snapshot)
            active.snapshot = active.snapshot.mergedWith(snapshot)
            if (snapshot.commentsOpen) active.openedComments = true
            return false
        }
        if (active != null) finish(active, now, movedToNext = true)
        val revisit = recentReels.any { it.isSameReelAs(snapshot) }
        current = ActiveReel(snapshot, nextSequence++, now, mode, revisit).also { observePlayback(it, snapshot) }
        return true
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

    fun addFrame(sequence: Int, frame: Bitmap) {
        val active = current
        if (active != null && active.sequence == sequence) active.frames.add(frame) else frame.recycle()
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

    /** The user left the feed or turned tracking off. */
    fun stop() = endCurrent(movedToNext = false)

    /** The current reel ended without a new reel taking its place (e.g. an ad collage came next). */
    fun endCurrent(movedToNext: Boolean) {
        current?.let { finish(it, clock(), movedToNext) }
        current = null
    }

    private fun finish(reel: ActiveReel, endedAtMs: Long, movedToNext: Boolean) {
        val snapshot = reel.snapshot
        val liked = snapshot.liked == true
        val saved = snapshot.saved == true
        val dwellMs = endedAtMs - reel.startedAtMs
        val completed = ReelLabeler.isCompleted(reel.looped, reel.maxPositionMs, reel.durationMs, config)
        val label = when {
            // Ads aren't content the user chose; their behaviour says little about taste.
            snapshot.isAd -> null
            reel.autoSkipped -> null
            reel.undone -> ReelLabel.WATCH
            else -> ReelLabeler.label(dwellMs, completed, liked, saved, movedToNext, config)
                // Scrolling back through reels already seen isn't a judgement on them.
                ?.takeUnless { it == ReelLabel.SKIP && reel.revisit }
        }
        recentReels.addLast(snapshot)
        if (recentReels.size > RECENT_REELS) recentReels.removeFirst()
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "${label ?: "-"} ${dwellMs}ms @${snapshot.creator} ${snapshot.mediaType}" +
                    (if (snapshot.isAd) " AD" else "") +
                    (if (reel.revisit) " revisit" else "") +
                    " dur=${reel.durationMs ?: "?"}(${reel.durationSource ?: "-"}) max=${reel.maxPositionMs ?: "?"}" +
                    " est=${reel.loopEstimateMs ?: "?"}" +
                    " looped=${reel.looped} liked=$liked frames=${reel.frames.size}" +
                    (if (reel.autoSkipped) " auto-skipped" else ""),
            )
        }

        val sessionId = SessionController.sessionId
        val frames = reel.frames.toList()
        if (sessionId == null) {
            frames.forEach(Bitmap::recycle)
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
            mode = reel.mode,
            startedAtMs = reel.startedAtMs,
            endedAtMs = endedAtMs,
            movedToNext = movedToNext,
            looped = reel.looped,
            durationMs = reel.durationMs,
            durationSource = reel.durationSource,
            maxPositionMs = reel.maxPositionMs,
            loopEstimateMs = reel.loopEstimateMs,
            liked = liked,
            saved = saved,
            openedComments = reel.openedComments,
            likeCount = snapshot.likeCount,
            commentCount = snapshot.commentCount,
            autoSkipped = reel.autoSkipped,
            undone = reel.undone,
            revisit = reel.revisit,
            feedIndex = snapshot.feedIndex,
            label = label,
            frameCount = frames.size,
        )
        val keepFrames = SessionController.researchMode.value
        scope.launch {
            val reelId = repository.saveReel(entity)
            if (keepFrames) frames.forEachIndexed { index, frame -> thumbnails.save(reelId, index, frame) }
            frames.forEach(Bitmap::recycle)
        }
    }

    private companion object {
        const val TAG = "ReelTracker"
        const val RECENT_REELS = 200
    }
}
