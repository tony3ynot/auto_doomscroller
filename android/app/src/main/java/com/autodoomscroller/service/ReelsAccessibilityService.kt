package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.AppGraph
import com.autodoomscroller.BuildConfig
import com.autodoomscroller.adapter.FeedState
import com.autodoomscroller.adapter.InstagramReelsAdapter
import com.autodoomscroller.adapter.NodeTreeDumper
import com.autodoomscroller.adapter.TargetAppAdapter
import com.autodoomscroller.autopilot.AutoPilot
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import com.autodoomscroller.tracking.Fingerprint
import com.autodoomscroller.tracking.LoopDetector
import com.autodoomscroller.tracking.MediaType
import com.autodoomscroller.tracking.ReelSnapshot
import com.autodoomscroller.tracking.ReelTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Entry point for everything that happens inside the target apps.
 *
 * Only packages with a [TargetAppAdapter] are inspected. For any other app the service only
 * notes that the user left, without reading its screen.
 */
class ReelsAccessibilityService : AccessibilityService() {

    private val adapters: Map<String, TargetAppAdapter> =
        listOf<TargetAppAdapter>(InstagramReelsAdapter()).associateBy { it.packageName }

    // Separate handlers: frame timers are cancelled on every new reel, reads are not.
    private val readHandler = Handler(Looper.getMainLooper())
    private val frameHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tracker: ReelTracker
    private lateinit var capturer: ScreenCapturer
    private lateinit var autoPilot: AutoPilot
    private var loopDetector = LoopDetector()
    private var showingOtherItem = false
    private var lastOtherAdSkipAtMs = 0L
    private var readScheduled = false
    private var lastReadAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturer = ScreenCapturer(this)
        autoPilot = AutoPilot(GestureDispatcher(this))
        tracker = ReelTracker(AppGraph.reelRepository, AppGraph.thumbnailStore, AppGraph.appScope)
        scope.launch {
            SessionController.mode.collect { mode ->
                if (mode == SessionMode.OFF) stopTracking()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (SessionController.mode.value == SessionMode.OFF) return
        if (event.packageName?.toString() in adapters) {
            scheduleRead()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            stopTracking()
        }
    }

    /**
     * Target apps fire content-change events many times a second while a video plays.
     * Reading the node tree is a chain of IPC calls, so coalesce events into at most one
     * read per [READ_INTERVAL_MS].
     */
    private fun scheduleRead() {
        if (readScheduled) return
        readScheduled = true
        val waitMs = (lastReadAtMs + READ_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        readHandler.postDelayed(::readFeed, waitMs)
    }

    private fun readFeed() {
        readScheduled = false
        lastReadAtMs = SystemClock.uptimeMillis()
        val mode = SessionController.mode.value
        if (mode == SessionMode.OFF) return
        val root = rootInActiveWindow ?: return
        val adapter = adapters[root.packageName?.toString()] ?: return

        val state = adapter.read(root)
        if (BuildConfig.DEBUG) {
            val tookMs = SystemClock.uptimeMillis() - lastReadAtMs
            if (tookMs > SLOW_READ_MS) Log.d(TAG, "slow read: ${tookMs}ms")
        }
        when (state) {
            is FeedState.OnReel -> {
                showingOtherItem = false
                if (tracker.onReelObserved(state.snapshot, mode)) onNewReel(state.snapshot)
                maybeSkipAd()
            }
            is FeedState.OtherItem -> onOtherItem(state, root)
            FeedState.NotOnFeed -> stopTracking()
            FeedState.Unknown -> Unit
        }
    }

    private fun onOtherItem(state: FeedState.OtherItem, root: AccessibilityNodeInfo) {
        if (!showingOtherItem) {
            showingOtherItem = true
            cancelFrames()
            tracker.endCurrent(movedToNext = true)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "other feed item (ad=${state.isAd})")
                NodeTreeDumper.dump(root)
                getExternalFilesDir(DEBUG_DUMP_DIR)?.let { NodeTreeDumper.save(root, it, "other-item") }
            }
        }
        // Don't fight a user who scrolled back to the item on purpose.
        val now = SystemClock.uptimeMillis()
        if (state.isAd && now - lastOtherAdSkipAtMs > OTHER_AD_SKIP_PAUSE_MS && autoPilot.onAdItem()) {
            lastOtherAdSkipAtMs = now
            if (BuildConfig.DEBUG) Log.d(TAG, "skipped ad item")
        }
    }

    /** The ad label can render a moment after the reel, so this runs on every read. */
    private fun maybeSkipAd() {
        val reel = tracker.currentSnapshot ?: return
        if (tracker.currentAutoSkipped || tracker.currentUndone) return
        if (autoPilot.onAd(reel)) tracker.markAutoSkipped()
    }

    private fun onNewReel(reel: ReelSnapshot) {
        cancelFrames()
        if (autoPilot.isUndo(reel)) tracker.markUndone()
        val sequence = tracker.currentSequence ?: return
        loopDetector = LoopDetector()
        for (offsetMs in FRAME_OFFSETS_MS) {
            frameHandler.postDelayed({ captureFrame(sequence, keep = true) }, offsetMs)
        }
        frameHandler.postDelayed({ sampleForLoop(sequence) }, LOOP_SAMPLING_START_MS)
        if (BuildConfig.DEBUG) frameHandler.postDelayed({ dumpTree(sequence) }, TREE_DUMP_DELAY_MS)
        // TODO: after the last frame, run FeatureExtractor + PreferenceModel and pass
        //  P(skip) to autoPilot.onPrediction(); call tracker.markAutoSkipped() if it skips.
    }

    /** [keep]: store the frame with the reel (first frames); otherwise it only feeds loop detection. */
    private fun captureFrame(sequence: Int, keep: Boolean) {
        capturer.capture(stillWanted = { tracker.currentSequence == sequence }) { frame ->
            val elapsedMs = tracker.currentElapsedMs
            if (tracker.currentSequence != sequence || elapsedMs == null) {
                frame.recycle()
                return@capture
            }
            loopDetector.add(Fingerprint.of(frame), elapsedMs)?.let(tracker::onLoopEstimated)
            if (keep) tracker.addFrame(sequence, frame) else frame.recycle()
        }
    }

    /**
     * Videos: keep sampling about once a second until the video is seen starting over.
     * Reels with a scrubber already know their length. In research mode one in
     * [VALIDATION_SAMPLE_EVERY] of them is sampled anyway, with the scrubber as ground truth
     * for the estimate; a sample is enough for that, and screenshots are the main battery cost.
     */
    private fun sampleForLoop(sequence: Int) {
        val reel = tracker.currentSnapshot ?: return
        val elapsedMs = tracker.currentElapsedMs ?: return
        if (tracker.currentSequence != sequence || tracker.currentLoopEstimated || elapsedMs > LOOP_SAMPLING_MAX_MS) return
        if (reel.mediaType != MediaType.VIDEO || reel.isAd) return
        val validate = SessionController.researchMode.value && sequence % VALIDATION_SAMPLE_EVERY == 0
        if (reel.durationMs != null && !validate) return
        captureFrame(sequence, keep = false)
        frameHandler.postDelayed({ sampleForLoop(sequence) }, LOOP_SAMPLING_INTERVAL_MS)
    }

    private fun cancelFrames() {
        frameHandler.removeCallbacksAndMessages(null)
        capturer.cancelPending()
    }

    private fun stopTracking() {
        cancelFrames()
        tracker.stop()
    }

    /** Debug builds log each reel's node tree once, so adapters can be written against the real UI. */
    private fun dumpTree(sequence: Int) {
        if (tracker.currentSequence != sequence) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() in adapters) NodeTreeDumper.dump(root)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        readHandler.removeCallbacksAndMessages(null)
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ReelsService"
        const val READ_INTERVAL_MS = 250L
        const val SLOW_READ_MS = 50L
        /** Frames taken after a reel starts; ScreenCapturer spaces them to respect the rate limit. */
        val FRAME_OFFSETS_MS = longArrayOf(300, 800, 1_300)
        const val LOOP_SAMPLING_START_MS = 2_300L
        const val LOOP_SAMPLING_INTERVAL_MS = 1_000L
        const val LOOP_SAMPLING_MAX_MS = 120_000L
        const val VALIDATION_SAMPLE_EVERY = 4
        const val TREE_DUMP_DELAY_MS = 2_000L
        const val OTHER_AD_SKIP_PAUSE_MS = 10_000L
        const val DEBUG_DUMP_DIR = "debug-dumps"
    }
}
