package com.autodoomscroller.model

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.autodoomscroller.data.ReelEntity
import com.autodoomscroller.data.ReelRepository
import com.autodoomscroller.features.FrameEncoder
import com.autodoomscroller.features.ViewingHistory
import com.autodoomscroller.tracking.ReelSnapshot
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Predicts whether the user would skip the reel that just appeared, from its first frame and
 * the viewing history. Everything is computed on the phone.
 *
 * The weights are read from [MODEL_FILE] in the app's external files, where
 * `adb push ml/models/skip_model.json /sdcard/Android/data/com.autodoomscroller/files/model/`
 * puts them; training on the phone is still to come. Without that file or the encoder there
 * are simply no predictions.
 *
 * [inputs] and [onReelFinished] are called from the main thread.
 */
class SkipPredictor(private val context: Context, private val repository: ReelRepository) {
    /** What one prediction needs, read on the main thread while the frame is still valid. */
    class Inputs internal constructor(internal val features: FloatArray, internal val frame: ByteBuffer)

    class Prediction(val skipProbability: Float, val encoderMs: Long, val autoThreshold: Float?)

    // The encoder and the model are only touched on this one worker.
    private val inference = Dispatchers.Default.limitedParallelism(1)
    private var encoder: FrameEncoder? = null
    private var model: SkipModel? = null
    private var modelReadAtMs = -1L

    /** A usable model is loaded; read from the main thread to skip the work when there is none. */
    @Volatile
    private var ready = false

    private var history: ViewingHistory? = null
    private val finishedBeforeLoad = mutableListOf<ReelEntity>()

    private val _trainedOn = MutableStateFlow<SkipModel.TrainingSummary?>(null)

    /** What the loaded model was trained on, for the home screen. */
    val trainedOn: StateFlow<SkipModel.TrainingSummary?> = _trainedOn.asStateFlow()

    private val _autoThreshold = MutableStateFlow<Float?>(null)

    /** P(skip) from which Auto mode acts with the loaded model; null keeps Auto mode locked. */
    val autoThreshold: StateFlow<Float?> = _autoThreshold.asStateFlow()

    /** Loads the encoder, the weights and the history of everything watched so far. */
    fun load(scope: CoroutineScope) {
        scope.launch(inference) {
            encoder = runCatching { FrameEncoder.open(context) }
                .onFailure { Log.w(TAG, "encoder didn't load", it) }
                .getOrNull()
            if (encoder == null) Log.i(TAG, "no frame encoder in this build; no predictions")
            readModel()
        }
        scope.launch(Dispatchers.Main) {
            val loaded = withContext(Dispatchers.IO) {
                ViewingHistory().also { history -> repository.allReels().forEach { history.add(it) } }
            }
            finishedBeforeLoad.filter { it.startedAtMs > loaded.lastStartedAtMs }.forEach { loaded.add(it) }
            finishedBeforeLoad.clear()
            history = loaded
        }
    }

    /** Picks up a weight file pushed since the last look. Cheap; called when a session starts. */
    fun refreshModel(scope: CoroutineScope) {
        scope.launch(inference) { readModel() }
    }

    private fun readModel() {
        val file = File(context.getExternalFilesDir(MODEL_DIR), MODEL_FILE)
        val modifiedAtMs = file.lastModified()
        if (modifiedAtMs == modelReadAtMs) return
        modelReadAtMs = modifiedAtMs
        model = if (!file.exists()) null else runCatching { SkipModel.parse(file.readText()) }
            .onFailure { Log.w(TAG, "skip model not usable", it) }
            .getOrNull()
            ?.takeIf { usable(it) }
        ready = model != null
        _trainedOn.value = model?.trainedOn
        _autoThreshold.value = model?.autoThreshold
        Log.i(TAG, model?.let { "skip model: ${it.trainedOn?.reels} reels up to ${it.trainedOn?.lastDay}, auto from ${it.autoThreshold}" } ?: "no skip model")
    }

    private fun usable(model: SkipModel): Boolean {
        val frameSize = encoder?.embeddingSize ?: return false
        val fits = model.features == ViewingHistory.NAMES && model.frameSize == frameSize
        if (!fits) Log.w(TAG, "skip model is for other features or another encoder than this build's")
        return fits
    }

    /** Null while there is nothing to predict with. [frame] is only read during the call. */
    fun inputs(reel: ReelSnapshot, startedAtMs: Long, frame: Bitmap): Inputs? {
        val history = history ?: return null
        if (!ready) return null
        return Inputs(history.features(reel, startedAtMs), FrameEncoder.input(frame))
    }

    suspend fun predictSkip(inputs: Inputs): Prediction? = withContext(inference) {
        val encoder = encoder ?: return@withContext null
        val model = model ?: return@withContext null
        val started = SystemClock.elapsedRealtimeNanos()
        val embedding = encoder.encode(inputs.frame)
        val encoderMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        Prediction(model.predictSkip(inputs.features, embedding), encoderMs, model.autoThreshold)
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
        const val MODEL_DIR = "model"
        const val MODEL_FILE = "skip_model.json"
    }
}
