package com.autodoomscroller.features

import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

/**
 * The frame encoder: MobileNetV3-Small without its classifier, FP16 build, run with LiteRT.
 * One 224 px frame in, one L2-normalised embedding out. The model file comes from
 * `python -m autodoom.quantize` and is packaged by the build when it is there.
 *
 * [encode] is not thread-safe; [input] can run anywhere.
 */
class FrameEncoder private constructor(private val interpreter: Interpreter) : AutoCloseable {
    private val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())

    val embeddingSize: Int
        get() = output.capacity() / Float.SIZE_BYTES

    fun encode(input: ByteBuffer): FloatArray {
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val embedding = FloatArray(embeddingSize).also { output.asFloatBuffer().get(it) }
        val norm = sqrt(embedding.sumOf { it.toDouble() * it }).toFloat()
        if (norm > 0) for (i in embedding.indices) embedding[i] /= norm
        return embedding
    }

    override fun close() = interpreter.close()

    companion object {
        private const val ASSET = "encoder_fp16.tflite"
        const val INPUT_SIZE = 224

        /** Null when the build was made without the model file. */
        fun open(context: Context): FrameEncoder? {
            if (context.assets.list("")?.contains(ASSET) != true) return null
            val model = context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            // One thread on the CPU: more threads and the GPU were no faster on a phone
            // (docs/experiments.md, 2026-11-18).
            return FrameEncoder(Interpreter(model, Interpreter.Options().setNumThreads(1).setUseXNNPACK(true)))
        }

        /**
         * The encoder's input for [frame]: its centre square at 224 px, RGB floats in [0, 255],
         * as `load_frame` in `ml/src/autodoom/quantize.py` prepares the frames the model was
         * fitted on. Copies the pixels, so [frame] can be recycled afterwards.
         */
        fun input(frame: Bitmap): ByteBuffer {
            val (left, top, side) = centreSquare(frame.width, frame.height)
            var square = Bitmap.createBitmap(frame, left, top, side, side)
            if (side != INPUT_SIZE) {
                val scaled = Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)
                if (square !== frame && square !== scaled) square.recycle()
                square = scaled
            }
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            square.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            if (square !== frame) square.recycle()
            val rgb = FloatArray(pixels.size * 3)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                rgb[3 * i] = (pixel shr 16 and 0xFF).toFloat()
                rgb[3 * i + 1] = (pixel shr 8 and 0xFF).toFloat()
                rgb[3 * i + 2] = (pixel and 0xFF).toFloat()
            }
            val buffer = ByteBuffer.allocateDirect(rgb.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(rgb)
            return buffer
        }
    }
}

/** Left, top and side of the largest centred square in a [width] x [height] image. */
internal fun centreSquare(width: Int, height: Int): Triple<Int, Int, Int> {
    val side = minOf(width, height)
    return Triple((width - side) / 2, (height - side) / 2, side)
}
