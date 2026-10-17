package com.autodoomscroller.tracking

import android.graphics.Bitmap
import com.autodoomscroller.data.ReelEntity
import com.autodoomscroller.data.ReelRepository
import com.autodoomscroller.data.ThumbnailStore
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follows one reel at a time: when it started, what the user did with it, and the frames
 * captured from it. When the reel ends it is labeled and persisted.
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
    private class ActiveReel(val snapshot: ReelSnapshot, val startedAtMs: Long, val mode: SessionMode) {
        var liked = snapshot.liked == true
        var looped = false
        var autoSkipped = false
        var undone = false
        val frames = mutableListOf<Bitmap>()
    }

    private var current: ActiveReel? = null

    val currentReelKey: String?
        get() = current?.snapshot?.key

    /** Returns true if [snapshot] is a different reel from the one being tracked. */
    fun onReelObserved(snapshot: ReelSnapshot, mode: SessionMode): Boolean {
        val now = clock()
        val active = current
        if (active != null && active.snapshot.key == snapshot.key) {
            if (snapshot.liked == true) active.liked = true
            return false
        }
        if (active != null) finish(active, now, movedToNext = true)
        current = ActiveReel(snapshot, now, mode)
        return true
    }

    fun addFrame(frame: Bitmap) {
        current?.frames?.add(frame)
    }

    fun markLooped() {
        current?.looped = true
    }

    /** AutoPilot swiped past this reel; the user never really watched it, so it gets no label. */
    fun markAutoSkipped() {
        current?.autoSkipped = true
    }

    /** The user came back to a reel AutoPilot had skipped: a strong "should have watched". */
    fun markUndone() {
        current?.undone = true
    }

    /** The user left the feed or turned tracking off. */
    fun stop() {
        current?.let { finish(it, clock(), movedToNext = false) }
        current = null
    }

    private fun finish(reel: ActiveReel, endedAtMs: Long, movedToNext: Boolean) {
        val sessionId = SessionController.sessionId ?: return
        val label = when {
            reel.autoSkipped -> null
            reel.undone -> ReelLabel.WATCH
            else -> ReelLabeler.label(endedAtMs - reel.startedAtMs, reel.looped, reel.liked, movedToNext, config)
        }
        val entity = ReelEntity(
            sessionId = sessionId,
            packageName = reel.snapshot.packageName,
            reelKey = reel.snapshot.key,
            creator = reel.snapshot.creator,
            caption = reel.snapshot.caption,
            audio = reel.snapshot.audio,
            mode = reel.mode,
            startedAtMs = reel.startedAtMs,
            endedAtMs = endedAtMs,
            movedToNext = movedToNext,
            looped = reel.looped,
            liked = reel.liked,
            autoSkipped = reel.autoSkipped,
            undone = reel.undone,
            label = label,
            frameCount = reel.frames.size,
        )
        val frames = reel.frames.toList()
        val keepFrames = SessionController.researchMode.value
        scope.launch {
            val reelId = repository.saveReel(entity)
            if (keepFrames) frames.forEachIndexed { index, frame -> thumbnails.save(reelId, index, frame) }
            frames.forEach(Bitmap::recycle)
        }
    }
}
