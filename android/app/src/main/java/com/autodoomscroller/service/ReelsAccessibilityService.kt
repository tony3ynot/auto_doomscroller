package com.autodoomscroller.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autodoomscroller.AppGraph
import com.autodoomscroller.BuildConfig
import com.autodoomscroller.adapter.InstagramReelsAdapter
import com.autodoomscroller.adapter.NodeTreeDumper
import com.autodoomscroller.adapter.TargetAppAdapter
import com.autodoomscroller.autopilot.AutoPilot
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
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
 * Only events from packages with a [TargetAppAdapter] are inspected. For any other app the
 * service only notes that the user left, without reading its screen.
 */
class ReelsAccessibilityService : AccessibilityService() {

    private val adapters: Map<String, TargetAppAdapter> =
        listOf<TargetAppAdapter>(InstagramReelsAdapter()).associateBy { it.packageName }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tracker: ReelTracker
    private lateinit var capturer: ScreenCapturer
    private lateinit var autoPilot: AutoPilot
    private var lastTreeDumpAtMs = 0L

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
        val mode = SessionController.mode.value
        if (mode == SessionMode.OFF) return

        val adapter = adapters[event.packageName?.toString()]
        if (adapter == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) stopTracking()
            return
        }

        val root = rootInActiveWindow ?: return
        if (BuildConfig.DEBUG) maybeDumpTree(root)

        val reel = adapter.readCurrentReel(root)
        if (reel == null) {
            // Nodes briefly disappear during scroll animations, so only a window change
            // (e.g. opening DMs) counts as leaving the Reels feed.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) stopTracking()
            return
        }
        if (tracker.onReelObserved(reel, mode)) onNewReel(reel)
    }

    private fun onNewReel(reel: ReelSnapshot) {
        handler.removeCallbacksAndMessages(null)
        if (autoPilot.isUndo(reel.key)) tracker.markUndone()
        for (offsetMs in FRAME_OFFSETS_MS) {
            handler.postDelayed({ captureFrame(reel.key) }, offsetMs)
        }
        // TODO: after the last frame, run FeatureExtractor + PreferenceModel and pass
        //  P(skip) to autoPilot.onPrediction(); call tracker.markAutoSkipped() if it skips.
        // TODO: loop detection (frame similarity to the first frame) -> tracker.markLooped().
    }

    private fun captureFrame(reelKey: String) {
        capturer.capture { frame ->
            if (frame != null && tracker.currentReelKey == reelKey) tracker.addFrame(frame)
        }
    }

    private fun stopTracking() {
        handler.removeCallbacksAndMessages(null)
        tracker.stop()
    }

    /** Debug builds log the node tree so the adapters can be written against the real UI. */
    private fun maybeDumpTree(root: AccessibilityNodeInfo) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTreeDumpAtMs < TREE_DUMP_INTERVAL_MS) return
        lastTreeDumpAtMs = now
        NodeTreeDumper.dump(root)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** Frames taken after a reel starts. Spaced out because takeScreenshot() is rate limited. */
        val FRAME_OFFSETS_MS = longArrayOf(300, 800, 1_300)
        const val TREE_DUMP_INTERVAL_MS = 5_000L
    }
}
