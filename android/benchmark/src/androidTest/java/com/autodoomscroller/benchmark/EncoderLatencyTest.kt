package com.autodoomscroller.benchmark

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * Times every frame encoder build in the test APK's assets on this phone.
 *
 * Each build is timed two ways. Back to back is the usual benchmark figure. Paced leaves a
 * second between runs, which is how the app uses the encoder (once per reel): by then the
 * cores have slowed down and the caches are cold, so it is the slower and the more honest
 * of the two.
 *
 * Every measurement goes out as one line of JSON in the instrumentation status, which
 * `am instrument -r` prints. scripts/benchmark-encoder.sh collects them.
 */
@RunWith(AndroidJUnit4::class)
class EncoderLatencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.context

    @Test
    fun timeEncoders() {
        val models = context.assets.list("").orEmpty()
            .filter { it.startsWith("encoder_") && it.endsWith(".tflite") }
            .sorted()
        check(models.isNotEmpty()) { "No encoder_*.tflite in the assets. Build them with python -m autodoom.quantize." }

        report(device())
        for (model in models) {
            for (threads in THREADS) report(measure(model, threads, gpu = false))
        }
        // Last, so that a GPU driver taking the process down costs none of the CPU numbers.
        for (model in models) {
            report(runCatching { measure(model, threads = 1, gpu = true) }.getOrElse { failure(model, it) })
        }
    }

    private fun measure(model: String, threads: Int, gpu: Boolean): JSONObject {
        val started = System.nanoTime()
        val delegate = if (gpu) GpuDelegate() else null
        val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
        delegate?.let { options.addDelegate(it) }
        try {
            Interpreter(map(model), options).use { interpreter ->
                val source = interpreter.getInputTensor(0)
                check(source.dataType() == DataType.FLOAT32) { "$model takes ${source.dataType()}, expected FLOAT32" }
                val input = frame(source.numBytes())
                val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes())
                    .order(ByteOrder.nativeOrder())
                val loadMs = (System.nanoTime() - started) / 1e6

                val firstUs = run(interpreter, input, output)
                repeat(WARMUP_RUNS) { run(interpreter, input, output) }
                val backToBack = List(RUNS) { run(interpreter, input, output) }
                val paced = List(PACED_RUNS) {
                    Thread.sleep(PACE_MS)
                    run(interpreter, input, output)
                }
                return JSONObject()
                    .put("kind", "latency")
                    .put("variant", model.removePrefix("encoder_").removeSuffix(".tflite"))
                    .put("delegate", if (gpu) "gpu" else "cpu")
                    .put("threads", threads)
                    .put("load_ms", loadMs)
                    .put("first_us", firstUs)
                    .put("back_to_back_us", JSONArray(backToBack))
                    .put("paced_us", JSONArray(paced))
                    .put("thermal_status", thermalStatus())
            }
        } finally {
            delegate?.close()
        }
    }

    private fun run(interpreter: Interpreter, input: ByteBuffer, output: ByteBuffer): Long {
        input.rewind()
        output.rewind()
        val started = System.nanoTime()
        interpreter.run(input, output)
        return (System.nanoTime() - started) / 1000
    }

    private fun map(model: String): ByteBuffer =
        context.assets.openFd(model).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /** Random pixels in [0, 255]. What the frame shows makes no difference to the time it takes. */
    private fun frame(bytes: Int): ByteBuffer {
        val random = Random(0)
        val buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        repeat(bytes / Float.SIZE_BYTES) { buffer.putFloat(random.nextFloat() * 255f) }
        return buffer
    }

    private fun device(): JSONObject = JSONObject()
        .put("kind", "device")
        .put("model", Build.MODEL)
        .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
        .put("android", Build.VERSION.RELEASE)
        .put("cores", Runtime.getRuntime().availableProcessors())
        .put("litert", BuildConfig.LITERT_VERSION)
        .put("runtime", TensorFlowLite.runtimeVersion())
        .put("warmup_runs", WARMUP_RUNS)
        .put("pace_ms", PACE_MS)

    private fun failure(model: String, error: Throwable): JSONObject = JSONObject()
        .put("kind", "failure")
        .put("variant", model.removePrefix("encoder_").removeSuffix(".tflite"))
        .put("delegate", "gpu")
        .put("error", error.message ?: error.javaClass.simpleName)

    private fun thermalStatus(): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun report(record: JSONObject) {
        Log.i(TAG, record.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_KEY, record.toString()) })
    }

    private companion object {
        const val TAG = "EncoderLatency"
        const val STATUS_KEY = "encoder_latency"
        val THREADS = listOf(1, 2, 4)
        const val WARMUP_RUNS = 20
        const val RUNS = 200
        const val PACED_RUNS = 40
        const val PACE_MS = 1000L
    }
}
