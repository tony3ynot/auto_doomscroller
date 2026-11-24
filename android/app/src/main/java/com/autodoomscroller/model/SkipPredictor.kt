package com.autodoomscroller.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.autodoomscroller.data.ReelEntity
import com.autodoomscroller.data.ReelRepository
import com.autodoomscroller.data.ThumbnailStore
import com.autodoomscroller.features.FrameEncoder
import com.autodoomscroller.features.ViewingHistory
import com.autodoomscroller.tracking.ReelLabel
import com.autodoomscroller.tracking.ReelSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Predicts whether the user would skip the reel that just appeared, from its first frame and
 * the viewing history, and learns to: the model is fitted on this phone, from the reels in
 * its database, when a session ends ([SkipTrainer]). Nothing is read from or sent anywhere
 * else. Until there is enough to fit a model, reels are only embedded and recorded.
 *
 * [inputs] and [onReelFinished] are called from the main thread.
 */
class SkipPredictor(
    private val context: Context,
    private val repository: ReelRepository,
    private val thumbnails: ThumbnailStore,
) {
    /** What one prediction needs, read on the main thread while the frame is still valid. */
    class Inputs internal constructor(internal val features: FloatArray, internal val frame: ByteBuffer)

    /** [skipProbability] and [autoThreshold] are null while there is no model, or none sure enough. */
    class Prediction(val embedding: FloatArray, val skipProbability: Float?, val encoderMs: Long, val autoThreshold: Float?)

    // The encoder and the model in use are only touched on this one worker.
    private val inference = Dispatchers.Default.limitedParallelism(1)
    private var encoder: FrameEncoder? = null
    private var model: SkipModel? = null

    /** Read from the main thread to skip the work when the build has no encoder. */
    @Volatile
    private var encoderReady = false

    private var history: ViewingHistory? = null
    private val finishedBeforeLoad = mutableListOf<ReelEntity>()
    private val training = Mutex()
    private val modelFile = File(context.filesDir, "model/skip_model.json")

    private val _trainedOn = MutableStateFlow<SkipModel.TrainingSummary?>(null)

    /** What the model in use was trained on, for the home screen. */
    val trainedOn: StateFlow<SkipModel.TrainingSummary?> = _trainedOn.asStateFlow()

    private val _autoThreshold = MutableStateFlow<Float?>(null)

    /** P(skip) from which Auto mode acts with the model in use; null keeps Auto mode locked. */
    val autoThreshold: StateFlow<Float?> = _autoThreshold.asStateFlow()

    /** Loads the encoder, the last fitted model and the history of everything watched so far. */
    fun load(scope: CoroutineScope) {
        scope.launch(inference) {
            encoder = runCatching { FrameEncoder.open(context) }
                .onFailure { Log.w(TAG, "encoder didn't load", it) }
                .getOrNull()
            encoderReady = encoder != null
            if (encoder == null) Log.i(TAG, "no frame encoder in this build; no predictions")
            use(runCatching { modelFile.takeIf { it.exists() }?.let { SkipModel.parse(it.readText()) } }
                .onFailure { Log.w(TAG, "stored skip model not readable", it) }
                .getOrNull())
        }
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) {
                ViewingHistory().also { history -> repository.allReels().forEach { history.add(it) } }
            }
            finishedBeforeLoad.filter { it.startedAtMs > loaded.lastStartedAtMs }.forEach { loaded.add(it) }
            finishedBeforeLoad.clear()
            history = loaded
        }
        scope.launch {
            if (embedStoredFrames() > 0 || !modelFile.exists()) fit()
        }
    }

    /** Refits the model on everything recorded so far. Called when a session ends. */
    fun retrain(scope: CoroutineScope) {
        scope.launch {
            delay(SAVE_GRACE_MS) // the session's last reel is still on its way into the database
            fit()
        }
    }

    /** Everything was deleted: the model goes with the data it was fitted on. */
    fun forget(scope: CoroutineScope) {
        history = ViewingHistory()
        scope.launch(inference) {
            modelFile.delete()
            use(null)
        }
    }

    private suspend fun fit() = training.withLock {
        val started = SystemClock.elapsedRealtime()
        val examples = withContext(Dispatchers.IO) { examples() }
        val current = _trainedOn.value
        if (current != null && current.reels == examples.size) return@withLock // nothing new to learn from
        val fitted = withContext(Dispatchers.Default) { SkipTrainer.train(examples, ViewingHistory.NAMES) }
        if (fitted == null) {
            Log.i(TAG, "${examples.size} labeled reels with a frame: too few to fit a skip model")
            return@withLock
        }
        withContext(Dispatchers.IO) {
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(fitted.toJson())
        }
        withContext(inference) { use(fitted) }
        Log.i(TAG, "fitted on ${examples.size} reels in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Labeled first impressions with a stored embedding, with the features each had when it appeared. */
    private suspend fun examples(): List<TrainingExample> {
        val embeddings = repository.allEmbeddings()
        val past = ViewingHistory()
        val zone = ZoneId.systemDefault()
        return repository.allReels().mapNotNull { reel ->
            val features = past.features(
                reel.packageName, reel.creator, reel.caption, reel.audio, reel.mediaType, reel.paidPromotion,
                reel.likeCount, reel.commentCount, reel.startedAtMs,
            )
            past.add(reel)
            val frame = embeddings[reel.id]
            if (reel.label == null || reel.revisit || frame == null) return@mapNotNull null
            val day = Instant.ofEpochMilli(reel.startedAtMs).atZone(zone).toLocalDate().toEpochDay()
            TrainingExample(features, frame, reel.label == ReelLabel.SKIP, day)
        }
    }

    /**
     * Reels recorded before embeddings were kept can still be learned from if research mode
     * saved their first frame. Returns how many were embedded. Runs once; later reels bring
     * their embedding with them.
     */
    private suspend fun embedStoredFrames(): Int {
        val frames = withContext(Dispatchers.IO) {
            val done = repository.embeddedReelIds()
            repository.allReels()
                .filter { it.label != null && !it.revisit && it.id !in done }
                .mapNotNull { reel -> thumbnails.firstFrame(reel.id)?.let { reel.id to it } }
        }
        if (frames.isEmpty()) return 0
        Log.i(TAG, "embedding the stored first frames of ${frames.size} earlier reels")
        var embedded = 0
        for ((reelId, file) in frames) {
            val input = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.path)?.let { bitmap -> FrameEncoder.input(bitmap).also { bitmap.recycle() } }
            } ?: continue
            // One frame at a time, so a reel on screen never waits behind this.
            val embedding = withContext(inference) { encoder?.encode(input) } ?: return embedded
            withContext(Dispatchers.IO) { repository.saveEmbedding(reelId, embedding) }
            embedded++
        }
        Log.i(TAG, "embedded $embedded earlier reels")
        return embedded
    }

    /** Runs on the inference worker. */
    private fun use(candidate: SkipModel?) {
        val frameSize = encoder?.embeddingSize
        val fits = candidate != null && candidate.features == ViewingHistory.NAMES && candidate.frameSize == frameSize
        if (candidate != null && !fits) Log.w(TAG, "skip model is for other features or another encoder than this build's")
        model = candidate.takeIf { fits }
        _trainedOn.value = model?.trainedOn
        _autoThreshold.value = model?.autoThreshold
        Log.i(TAG, model?.let { "skip model: ${it.trainedOn?.reels} reels up to ${it.trainedOn?.lastDay}, auto from ${it.autoThreshold}" } ?: "no skip model")
    }

    /** Null while there is nothing to embed the frame with. [frame] is only read during the call. */
    fun inputs(reel: ReelSnapshot, startedAtMs: Long, frame: Bitmap): Inputs? {
        val history = history ?: return null
        if (!encoderReady) return null
        return Inputs(history.features(reel, startedAtMs), FrameEncoder.input(frame))
    }

    suspend fun predictSkip(inputs: Inputs): Prediction? = withContext(inference) {
        val encoder = encoder ?: return@withContext null
        val started = SystemClock.elapsedRealtimeNanos()
        val embedding = encoder.encode(inputs.frame)
        val encoderMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        val model = model
        Prediction(embedding, model?.predictSkip(inputs.features, embedding), encoderMs, model?.autoThreshold)
    }

    /** Every reel the tracker stores goes into the history the next prediction uses. */
    fun onReelFinished(reel: ReelEntity) {
        history?.add(reel) ?: finishedBeforeLoad.add(reel)
    }

    private fun ViewingHistory.add(reel: ReelEntity) = add(
        packageName = reel.packageName,
        creator = reel.creator,
        startedAtMs = reel.startedAtMs,
        endedAtMs = reel.endedAtMs,
        dwellMs = reel.endedAtMs - reel.startedAtMs - reel.pausedMs,
        isAd = reel.isAd,
        label = reel.label,
    )

    private companion object {
        const val TAG = "SkipPredictor"
        const val SAVE_GRACE_MS = 2_000L
    }
}
