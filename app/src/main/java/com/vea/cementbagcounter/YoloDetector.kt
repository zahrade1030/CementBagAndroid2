package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float
) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
}

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputSize = 640

    private val confidenceThreshold = 0.20f
    private val nmsThreshold = 0.45f

    init {
        val model = context.assets.open("best.onnx").use {
            it.readBytes()
        }

        session = env.createSession(model)
        inputName = session.inputNames.first()

        Log.i("YOLO", "Model loaded successfully")
        Log.i("YOLO", "Input: $inputName")
    }

    fun detect(source: Bitmap): List<Detection> {

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()

        // Letterbox preprocessing
        val scale = min(
            inputSize / srcW,
            inputSize / srcH
        )

        val resizedW = (srcW * scale).toInt()
        val resizedH = (srcH * scale).toInt()

        val resized = Bitmap.createScaledBitmap(
            source,
            resizedW,
            resizedH,
            true
        )

        val padX = (inputSize - resizedW) / 2
        val padY = (inputSize - resizedH) / 2

        val inputBitmap = Bitmap.createBitmap(
            inputSize,
            inputSize,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(inputBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))

        canvas.drawBitmap(
            resized,
            padX.toFloat(),
            padY.toFloat(),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        if (resized !== source) resized.recycle()

        // RGB normalization
        val pixels = IntArray(inputSize * inputSize)

        inputBitmap.getPixels(
            pixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        inputBitmap.recycle()

        val plane = inputSize * inputSize
        val input = FloatArray(3 * plane)

        for (i in pixels.indices) {
            val p = pixels[i]

            input[i] = ((p shr 16) and 255) / 255f
            input[plane + i] = ((p shr 8) and 255) / 255f
            input[2 * plane + i] = (p and 255) / 255f
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, 640, 640)
        )

        tensor.use {

            val result = session.run(
                mapOf(inputName to tensor)
            )

            result.use {

                val value = result[0].value
                val data = flatten(value)

                if (data.size != 5 * 8400) {
                    Log.e(
                        "YOLO",
                        "Unexpected output size: ${data.size}"
                    )
                    return emptyList()
                }

                return decode(
                    data,
                    srcW,
                    srcH,
                    scale,
                    padX.toFloat(),
                    padY.toFloat()
                )
            }
        }
    }

    private fun flatten(value: Any): FloatArray {

        val out = mutableListOf<Float>()

        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> out.addAll(v.toList())

                is Array<*> -> {
                    v.forEach { walk(it) }
                }
            }
        }

        walk(value)

        return out.toFloatArray()
    }

    // Model output: [1, 5, 8400]
    private fun decode(
        a: FloatArray,
        srcW: Float,
        srcH: Float,
        scale: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {

        val n = 8400
        val candidates = mutableListOf<Detection>()

        var maxConfidence = 0f
        var detectedCandidates = 0

        for (i in 0 until n) {

            val cx = a[i]
            val cy = a[n + i]
            val w = a[2 * n + i]
            val h = a[3 * n + i]
            val score = a[4 * n + i]

            if (!score.isFinite()) continue

            maxConfidence = max(maxConfidence, score)

            if (score < confidenceThreshold || score > 1f) {
                continue
            }

            detectedCandidates++

            // Remove letterbox padding and restore source coordinates
            val left = (cx - w / 2f - padX) / scale
            val top = (cy - h / 2f - padY) / scale
            val right = (cx + w / 2f - padX) / scale
            val bottom = (cy + h / 2f - padY) / scale

            val detection = Detection(
                max(0f, left),
                max(0f, top),
                min(srcW, right),
                min(srcH, bottom),
                score
            )

            if (
                detection.right > detection.left &&
                detection.bottom > detection.top
            ) {
                candidates.add(detection)
            }
        }

        Log.i(
            "YOLO",
            "Max confidence: $maxConfidence | Candidates: $detectedCandidates"
        )

        return nms(candidates, nmsThreshold).take(60)
    }

    private fun nms(
        items: List<Detection>,
        threshold: Float
    ): List<Detection> {

        val sorted = items.sortedByDescending {
            it.confidence
        }.toMutableList()

        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {

            val best = sorted.removeAt(0)
            kept.add(best)

            val iterator = sorted.iterator()

            while (iterator.hasNext()) {
                if (iou(best, iterator.next()) > threshold) {
                    iterator.remove()
                }
            }
        }

        return kept
    }

    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val inter = max(0f, x2 - x1) *
                max(0f, y2 - y1)

        val areaA = (a.right - a.left) *
                (a.bottom - a.top)

        val areaB = (b.right - b.left) *
                (b.bottom - b.top)

        return inter / (areaA + areaB - inter + 1e-6f)
    }

    fun close() {
        session.close()
    }
}
