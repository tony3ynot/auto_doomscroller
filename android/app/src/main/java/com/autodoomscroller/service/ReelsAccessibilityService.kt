package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.autodoomscroller.adapter.YouTubeShortsAdapter
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
        listOf(InstagramReelsAdapter(), YouTubeShortsAdapter()).associateBy { it.packageName }

    // Separate handlers: frame timers are cancelled on every new reel, reads are not.
    private val readHandler = Handler(Looper.getMainLooper())
    private val frameHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tracker: ReelTracker
    private lateinit var capturer: ScreenCapturer
    private lateinit var autoPilot: AutoPilot
    private var loopDetector = LoopDetector()

    /** Where the current reel settled on screen; its frames are cropped to this. */
    private var mediaBounds: Rect? = null
    private var showingOtherItem = false
    private var lastDiscoveryDumpAtMs = 0L
    private var lastUnskippedAdSequence: Int? = null
    private var windowDumpsLeft = MAX_WINDOW_DUMPS
    private var lastCommentWindowCheckAtMs = 0L
    private var lastCommentWindowOpen = false
    private var lastAppWindowCount = 0
    private var lastStateName: String? = null
    private var lastSnapshotComments: Boolean? = null
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
        val packageName = event.packageName?.toString()
        if (packageName in adapters) {
            scheduleRead()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scheduleLeaveCheck()
        if (BuildConfig.DEBUG && packageName in DISCOVERY_PACKAGES) maybeDumpForDiscovery()
    }

    /**
     * Another app's window changed. Heads-up notifications, the volume panel and the like do
     * this without taking the user away, so only pause if a target app is no longer in front.
     */
    private fun scheduleLeaveCheck() {
        readHandler.removeCallbacks(leaveCheck)
        readHandler.postDelayed(leaveCheck, LEAVE_CHECK_DELAY_MS)
    }

    private val leaveCheck = Runnable {
        if (rootInActiveWindow?.packageName?.toString() in adapters) scheduleRead() else pauseTracking()
    }

    private val pauseExpiry = Runnable { tracker.expirePause() }

    private fun pauseTracking() {
        if (tracker.currentSnapshot == null || tracker.currentPaused) return
        cancelFrames()
        tracker.pause()
        readHandler.removeCallbacks(pauseExpiry)
        readHandler.postDelayed(pauseExpiry, ReelTracker.RESUME_WINDOW_MS + PAUSE_EXPIRY_SLACK_MS)
    }

    /** Debug builds periodically dump apps that don't have an adapter yet, to write one. */
    private fun maybeDumpForDiscovery() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDiscoveryDumpAtMs < DISCOVERY_DUMP_INTERVAL_MS) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in DISCOVERY_PACKAGES) return
        lastDiscoveryDumpAtMs = now
        NodeTreeDumper.dump(root)
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

        var state = adapter.read(root)
        if (state == FeedState.NotOnFeed) state = readOtherWindows(adapter, root) ?: state
        if (BuildConfig.DEBUG) {
            val tookMs = SystemClock.uptimeMillis() - lastReadAtMs
            if (tookMs > SLOW_READ_MS) Log.d(TAG, "slow read: ${tookMs}ms")
        }
        if (BuildConfig.DEBUG) logTransitions(state)
        when (state) {
            is FeedState.OnReel -> {
                showingOtherItem = false
                if (tracker.onReelObserved(state.snapshot, mode)) onNewReel(state.snapshot, adapter)
                maybeSkipAd()
                checkCommentWindows(adapter, root)
            }
            is FeedState.OtherItem -> onOtherItem(state, root)
            FeedState.CommentsOpen -> tracker.onCommentsOpen()
            FeedState.NotOnFeed -> pauseTracking()
            FeedState.Unknown -> checkCommentWindows(adapter, root)
        }
    }

    /**
     * Opening comments hides or shrinks the feed (reads come back Unknown), and Instagram shows
     * them in a separate window that rarely becomes the active one. So while on or around the
     * feed, ask the adapter once a second whether any of the app's windows shows comments.
     */
    private fun checkCommentWindows(adapter: TargetAppAdapter, active: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastCommentWindowCheckAtMs < COMMENT_WINDOW_CHECK_MS) return
        lastCommentWindowCheckAtMs = now
        val appWindows = (listOf(active) + windows.mapNotNull { it.root }.filter { it != active })
            .filter { it.packageName?.toString() == adapter.packageName }
        val open = appWindows.any { adapter.isCommentsWindow(it) }
        if (BuildConfig.DEBUG && (open != lastCommentWindowOpen || appWindows.size != lastAppWindowCount)) {
            Log.d(TAG, "app windows: ${appWindows.size}, comment window open: $open")
        }
        lastCommentWindowOpen = open
        lastAppWindowCount = appWindows.size
        if (open) tracker.onCommentsOpen()
    }

    private fun logTransitions(state: FeedState) {
        val name = state::class.simpleName
        if (name != lastStateName) Log.d(TAG, "feed state: $lastStateName -> $name")
        lastStateName = name
        val comments = (state as? FeedState.OnReel)?.snapshot?.commentsOpen
        if (comments != null && comments != lastSnapshotComments) Log.d(TAG, "reel comments open: $comments")
        if (comments != null) lastSnapshotComments = comments
    }

    /**
     * The active window can be a sheet or dialog on top of the feed (e.g. comments), so before
     * treating the user as gone, look at the app's other windows: comments open anywhere win,
     * then a feed that is still visible.
     */
    private fun readOtherWindows(adapter: TargetAppAdapter, active: AccessibilityNodeInfo): FeedState? {
        val others = windows.mapNotNull { window -> window.root?.let { window to it } }
            .filter { (_, root) -> root.packageName?.toString() == adapter.packageName && root != active }
        val states = others.map { (_, root) -> adapter.read(root) }
        if (BuildConfig.DEBUG && windowDumpsLeft > 0) {
            windowDumpsLeft--
            Log.d(TAG, "not on feed in the active window; app windows: " + others.zip(states).joinToString { (pair, state) ->
                val (window, _) = pair
                "[type=${window.type} active=${window.isActive} focused=${window.isFocused} title=${window.title} -> ${state::class.simpleName}]"
            })
            val dir = getExternalFilesDir(DEBUG_DUMP_DIR)
            NodeTreeDumper.dump(active)
            dir?.let { NodeTreeDumper.save(active, it, "not-on-feed-active") }
            others.forEachIndexed { i, (_, root) ->
                NodeTreeDumper.dump(root)
                dir?.let { NodeTreeDumper.save(root, it, "not-on-feed-window$i") }
            }
        }
        return states.firstOrNull { it == FeedState.CommentsOpen } ?: states.firstOrNull { it is FeedState.OnReel }
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
        if (!reel.isAd || tracker.currentAutoSkipped || tracker.currentUndone) return
        if (autoPilot.onAd(reel)) {
            tracker.markAutoSkipped()
        } else if (BuildConfig.DEBUG && tracker.currentSequence != lastUnskippedAdSequence) {
            lastUnskippedAdSequence = tracker.currentSequence
            Log.d(TAG, "ad not skipped (skip ads ${if (SessionController.adSkip.value) "on, cooling down" else "off"})")
        }
    }

    private fun onNewReel(reel: ReelSnapshot, adapter: TargetAppAdapter) {
        cancelFrames()
        mediaBounds = null
        if (autoPilot.isUndo(reel)) tracker.markUndone()
        val sequence = tracker.currentSequence ?: return
        loopDetector = LoopDetector()
        waitUntilSettled(sequence, adapter)
        if (BuildConfig.DEBUG) frameHandler.postDelayed({ dumpTree(sequence) }, TREE_DUMP_DELAY_MS)
        // TODO: after the first frame, run FeatureExtractor + PreferenceModel and pass
        //  P(skip) to autoPilot.onPrediction(); call tracker.markAutoSkipped() if it skips.
    }

    /**
     * A reel becomes current while the swipe that brings it in is still finishing, and a
     * screenshot then shows part of the previous reel. So frames wait until the page has
     * stopped moving and are all cropped to where it settled. The first one is taken at once:
     * it is what Auto mode has to decide on.
     */
    private fun waitUntilSettled(sequence: Int, adapter: TargetAppAdapter) {
        if (tracker.currentSequence != sequence) return
        val elapsedMs = tracker.currentElapsedMs ?: return
        val bounds = settledBounds(adapter)
        when {
            bounds != null -> onSettled(sequence, adapter, bounds, elapsedMs)
            elapsedMs < SETTLE_TIMEOUT_MS ->
                frameHandler.postDelayed({ waitUntilSettled(sequence, adapter) }, SETTLE_POLL_MS)
            BuildConfig.DEBUG -> Log.d(TAG, "reel didn't settle within ${SETTLE_TIMEOUT_MS}ms; no frames")
        }
    }

    private fun settledBounds(adapter: TargetAppAdapter): Rect? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == adapter.packageName }
            ?.let(adapter::settledMediaBounds)

    private fun onSettled(sequence: Int, adapter: TargetAppAdapter, bounds: Rect, elapsedMs: Long) {
        mediaBounds = bounds
        if (BuildConfig.DEBUG) Log.d(TAG, "settled after ${elapsedMs}ms; frames cropped to $bounds")
        captureFrame(sequence, adapter, keep = true)
        FRAME_OFFSETS_MS.forEach { offsetMs ->
            frameHandler.postDelayed({ captureFrame(sequence, adapter, keep = true) }, (offsetMs - elapsedMs).coerceAtLeast(0))
        }
        frameHandler.postDelayed({ sampleForLoop(sequence, adapter) }, (LOOP_SAMPLING_START_MS - elapsedMs).coerceAtLeast(0))
    }

    /**
     * [keep]: store a thumbnail with the reel; otherwise the frame only feeds loop detection.
     * The first kept frame is also kept at capture size, readable enough for OCR. A frame is
     * dropped if the reel was moving when it was requested or is by the time it arrives.
     */
    private fun captureFrame(sequence: Int, adapter: TargetAppAdapter, keep: Boolean) {
        val bounds = mediaBounds ?: return
        val stillCurrent = { tracker.currentSequence == sequence && settledBounds(adapter) != null }
        capturer.capture(bounds, stillWanted = stillCurrent) { capture ->
            val elapsedMs = tracker.currentElapsedMs
            if (elapsedMs == null || !stillCurrent()) {
                capture.recycle()
                return@capture
            }
            val hiRes = if (keep && tracker.currentNeedsHiResFrame) {
                capture.copy(capture.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                null
            }
            val thumbnail = capture.scaledToWidth(ScreenCapturer.THUMBNAIL_WIDTH)
            loopDetector.add(Fingerprint.of(thumbnail), elapsedMs)?.let(tracker::onLoopEstimated)
            if (keep) tracker.addFrame(sequence, thumbnail, elapsedMs) else thumbnail.recycle()
            hiRes?.let { tracker.setHiResFrame(sequence, it) }
        }
    }

    /**
     * Videos: keep sampling about once a second until the video is seen starting over.
     * Reels with a scrubber already know their length. In research mode one in
     * [VALIDATION_SAMPLE_EVERY] of them is sampled anyway, with the scrubber as ground truth
     * for the estimate; a sample is enough for that, and screenshots are the main battery cost.
     */
    private fun sampleForLoop(sequence: Int, adapter: TargetAppAdapter) {
        val reel = tracker.currentSnapshot ?: return
        val elapsedMs = tracker.currentElapsedMs ?: return
        if (tracker.currentSequence != sequence || tracker.currentLoopEstimated || elapsedMs > LOOP_SAMPLING_MAX_MS) return
        if (reel.mediaType != MediaType.VIDEO || reel.isAd) return
        val validate = SessionController.researchMode.value && sequence % VALIDATION_SAMPLE_EVERY == 0
        if (reel.durationMs != null && !validate) return
        captureFrame(sequence, adapter, keep = false)
        frameHandler.postDelayed({ sampleForLoop(sequence, adapter) }, LOOP_SAMPLING_INTERVAL_MS)
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
        /** Frames after the first, measured from when the reel appeared; ScreenCapturer spaces them. */
        val FRAME_OFFSETS_MS = longArrayOf(800, 1_300)
        const val SETTLE_POLL_MS = 50L
        const val SETTLE_TIMEOUT_MS = 1_500L
        const val LOOP_SAMPLING_START_MS = 2_300L
        const val LOOP_SAMPLING_INTERVAL_MS = 1_000L
        const val LOOP_SAMPLING_MAX_MS = 120_000L
        const val VALIDATION_SAMPLE_EVERY = 4
        const val TREE_DUMP_DELAY_MS = 2_000L
        const val LEAVE_CHECK_DELAY_MS = 500L
        const val MAX_WINDOW_DUMPS = 3
        const val COMMENT_WINDOW_CHECK_MS = 1_000L
        const val PAUSE_EXPIRY_SLACK_MS = 500L
        const val OTHER_AD_SKIP_PAUSE_MS = 10_000L
        const val DEBUG_DUMP_DIR = "debug-dumps"
        /** Apps to dump while writing a new adapter; empty when there is none in progress. */
        val DISCOVERY_PACKAGES = emptySet<String>()
        const val DISCOVERY_DUMP_INTERVAL_MS = 3_000L
    }
}
