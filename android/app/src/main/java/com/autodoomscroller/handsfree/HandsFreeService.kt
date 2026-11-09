package com.autodoomscroller.handsfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.autodoomscroller.BuildConfig
import com.autodoomscroller.MainActivity
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Hands-free mode: watches the front camera for a double wink or a long blink ("next") and a
 * held thumbs-up ("like") and hands them to the accessibility service through [HandsFree].
 *
 * A foreground service, because Android only lets an app use the camera while the user can
 * see that it does. It is started from the app's screen and keeps the camera while the user is
 * in Instagram or YouTube. Frames are analysed only while a supported feed is in front, about
 * ten a second, and never stored.
 */
class HandsFreeService : LifecycleService() {
    // One thread owns the models: they are created, used and closed on it.
    private lateinit var executor: ExecutorService
    private var faces: FaceLandmarker? = null
    private var hands: GestureRecognizer? = null
    private val interpreter = GestureInterpreter()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastFrameAtMs = 0L
    private var stats = FrameStats()

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: SecurityException) {
            // Camera permission was revoked after the switch was turned on.
            Log.w(TAG, "can't use the camera", e)
            stopSelf()
            return
        }
        HandsFree.setRunning(true)
        executor.execute(::loadModels)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            lifecycleScope.launch {
                HandsFree.feedVisible.collectLatest { visible ->
                    if (visible) {
                        bindCamera()
                    } else {
                        // Brief trips away (notification shade, a dialog) keep the camera warm.
                        delay(RELEASE_AFTER_MS)
                        unbindCamera()
                    }
                }
            }
        }, mainExecutor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unbindCamera()
        HandsFree.setRunning(false)
        executor.execute {
            faces?.close()
            hands?.close()
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun loadModels() {
        faces = FaceLandmarker.createFromOptions(
            this,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .build(),
        )
        hands = GestureRecognizer.createFromOptions(
            this,
            GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(GESTURE_MODEL).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .build(),
        )
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (analysis != null) return
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        useCase.setAnalyzer(executor, ::analyze)
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
        analysis = useCase
        if (BuildConfig.DEBUG) Log.d(TAG, "camera on")
    }

    private fun unbindCamera() {
        val useCase = analysis ?: return
        cameraProvider?.unbind(useCase)
        analysis = null
        if (BuildConfig.DEBUG) Log.d(TAG, "camera off")
    }

    /** Runs on [executor]. */
    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            val faces = faces
            val hands = hands
            if (faces == null || hands == null || now - lastFrameAtMs < FRAME_INTERVAL_MS) return
            lastFrameAtMs = now

            val rotation = image.imageInfo.rotationDegrees
            val frame = BitmapImageBuilder(image.toBitmap()).build()
            val options = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            val faceStart = SystemClock.elapsedRealtime()
            val face = faces.detectForVideo(frame, options, now)
            val handStart = SystemClock.elapsedRealtime()
            val gesture = hands.recognizeForVideo(frame, options, now).gestures().firstOrNull()?.firstOrNull()
            val handEnd = SystemClock.elapsedRealtime()
            val shapes = face.faceBlendshapes().orElse(null)?.firstOrNull()
            val signal = GestureFrame(
                atMs = now,
                blinkLeft = shapes?.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score(),
                blinkRight = shapes?.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score(),
                thumbUp = gesture?.takeIf { it.categoryName() == "Thumb_Up" }?.score(),
            )
            interpreter.onFrame(signal)?.let { command ->
                if (BuildConfig.DEBUG) Log.d(TAG, "command: $command")
                HandsFree.send(command)
            }
            if (BuildConfig.DEBUG) {
                val upright = if (rotation % 180 == 0) Size(image.width, image.height) else Size(image.height, image.width)
                val openness = face.faceLandmarks().firstOrNull()?.let { eyeOpenness(it, upright) }
                logEyes(signal, openness)
                logStats(signal, openness, faceMs = handStart - faceStart, handMs = handEnd - handStart)
            }
        }
    }

    // Debug builds log the scores, to tune the thresholds.

    /** Every frame in which an eye is closing; a wink takes only two or three. */
    private fun logEyes(signal: GestureFrame, openness: Pair<Float, Float>?) {
        if (maxOf(signal.blinkLeft ?: 0f, signal.blinkRight ?: 0f) < EYES_LOG_ABOVE) return
        val wink = if (interpreter.winking) " wink" else ""
        Log.d(TAG, "eyes ${eyes(signal, openness)}$wink")
    }

    /** Every couple of seconds: how long the models take, and the latest scores. */
    private fun logStats(signal: GestureFrame, openness: Pair<Float, Float>?, faceMs: Long, handMs: Long) {
        stats = stats.add(faceMs, handMs)
        if (signal.atMs - stats.sinceMs < STATS_INTERVAL_MS) return
        val n = stats.frames
        Log.d(
            TAG,
            "$n frames, face ${stats.faceMs / n} + hand ${stats.handMs / n} ms; ${eyes(signal, openness)} thumb=${signal.thumbUp.f()}",
        )
        stats = FrameStats(sinceMs = signal.atMs)
    }

    private fun eyes(signal: GestureFrame, openness: Pair<Float, Float>?) =
        "blink L=${signal.blinkLeft.f()} R=${signal.blinkRight.f()} open L=${openness?.first.f()} R=${openness?.second.f()}"

    private fun Float?.f() = this?.let { "%.2f".format(it) } ?: "-"

    /**
     * Eye aspect ratio from the face mesh: lid gap over eye width, about 0.3 open and under 0.15
     * shut. Logged next to the blink scores in case those move both eyes together.
     */
    private fun eyeOpenness(mesh: List<NormalizedLandmark>, image: Size): Pair<Float, Float> {
        fun gap(a: Int, b: Int) =
            hypot((mesh[a].x() - mesh[b].x()) * image.width, (mesh[a].y() - mesh[b].y()) * image.height)
        fun ratio(eye: IntArray) = (gap(eye[1], eye[5]) + gap(eye[2], eye[4])) / (2 * gap(eye[0], eye[3]))
        return ratio(LEFT_EYE) to ratio(RIGHT_EYE)
    }

    private data class FrameStats(
        val sinceMs: Long = SystemClock.elapsedRealtime(),
        val frames: Int = 0,
        val faceMs: Long = 0,
        val handMs: Long = 0,
    ) {
        fun add(face: Long, hand: Long) = copy(frames = frames + 1, faceMs = faceMs + face, handMs = handMs + hand)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hands-free mode", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Hands-free mode is on")
            .setContentText("Shut your eyes for a second or double wink: next · Thumbs-up: like")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HandsFree"
        private const val CHANNEL_ID = "hands_free"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.autodoomscroller.handsfree.STOP"
        private const val FACE_MODEL = "face_landmarker.task"
        private const val GESTURE_MODEL = "gesture_recognizer.task"
        private const val FRAME_INTERVAL_MS = 100L
        private const val RELEASE_AFTER_MS = 5_000L
        private const val STATS_INTERVAL_MS = 2_000L
        private const val EYES_LOG_ABOVE = 0.35f

        // Face mesh points per eye: outer corner, two upper lid, inner corner, two lower lid.
        private val LEFT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)
        private val RIGHT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    }
}
