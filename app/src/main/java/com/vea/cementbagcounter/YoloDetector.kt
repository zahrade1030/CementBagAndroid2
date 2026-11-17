
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
    val cx: Float
        get() = (left + right) / 2f

    val cy: Float
        get() = (top + bottom) / 2f
}

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputSize = 640
    private val outputChannels = 5
    private val outputCandidates = 8400

    private val confidenceThreshold = 0.40f
    private val nmsThreshold = 0.45f

    private val maxCandidates = 1000
    private val maxDetections = 60

    init {

        val model = context.assets.open("best.onnx").use {
            it.readBytes()
        }

        session = env.createSession(model)
        inputName = session.inputNames.first()

        Log.i("YOLO", "Model loaded successfully")
        Log.i("YOLO", "Input: $inputName")
        Log.i(
            "YOLO",
            "Input size: $inputSize x $inputSize"
        )
        Log.i(
            "YOLO",
            "Expected output: [1, $outputChannels, $outputCandidates]"
        )
    }

    fun detect(source: Bitmap): List<Detection> {

        if (
            source.isRecycled ||
            source.width <= 0 ||
            source.height <= 0
        ) {
            Log.e("YOLO", "Invalid source bitmap")
            return emptyList()
        }

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()

        // Calculate letterbox scale.
        val scale = min(
            inputSize / srcW,
            inputSize / srcH
        )

        val resizedW = max(
            1,
            (srcW * scale).toInt()
        )

        val resizedH = max(
            1,
            (srcH * scale).toInt()
        )

        // Actual scales after integer rounding.
        val scaleX = resizedW / srcW
        val scaleY = resizedH / srcH

        val padX = (inputSize - resizedW) / 2
        val padY = (inputSize - resizedH) / 2

        val resized = Bitmap.createScaledBitmap(
            source,
            resizedW,
            resizedH,
            true
        )

        val inputBitmap = Bitmap.createBitmap(
            inputSize,
            inputSize,
            Bitmap.Config.ARGB_8888
        )

        try {

            val canvas = Canvas(inputBitmap)

            canvas.drawColor(
                Color.rgb(114, 114, 114)
            )

            val paint = Paint(
                Paint.FILTER_BITMAP_FLAG
            )

            canvas.drawBitmap(
                resized,
                padX.toFloat(),
                padY.toFloat(),
                paint
            )

        } finally {

            if (resized !== source) {
                resized.recycle()
            }
        }

        // Convert image to RGB float tensor.
        val pixels = IntArray(
            inputSize * inputSize
        )

        try {

            inputBitmap.getPixels(
                pixels,
                0,
                inputSize,
                0,
                0,
                inputSize,
                inputSize
            )

        } finally {

            inputBitmap.recycle()
        }

        val plane = inputSize * inputSize
        val input = FloatArray(3 * plane)

        for (i in pixels.indices) {

            val pixel = pixels[i]

            input[i] =
                ((pixel shr 16) and 255) / 255f

            input[plane + i] =
                ((pixel shr 8) and 255) / 255f

            input[2 * plane + i] =
                (pixel and 255) / 255f
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(
                1,
                3,
                inputSize.toLong(),
                inputSize.toLong()
            )
        )

        tensor.use { inputTensor ->

            session.run(
                mapOf(inputName to inputTensor)
            ).use { result ->

                if (result.size() == 0) {
                    Log.e("YOLO", "Model returned no output")
                    return emptyList()
                }

                val data = flatten(
                    result[0].value
                )

                val expectedSize =
                    outputChannels * outputCandidates

                if (data.size != expectedSize) {

                    Log.e(
                        "YOLO",
                        "Unexpected output size: ${data.size}, " +
                            "expected: $expectedSize"
                    )

                    return emptyList()
                }

                return decode(
                    data = data,
                    srcW = srcW,
                    srcH = srcH,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    padX = padX.toFloat(),
                    padY = padY.toFloat()
                )
            }
        }
    }

    // Flatten nested ONNX output arrays.
    private fun flatten(value: Any): FloatArray {

        val output = FloatArray(
            outputChannels * outputCandidates
        )

        var index = 0

        fun walk(item: Any?) {

            when (item) {

                is FloatArray -> {

                    for (number in item) {

                        if (index >= output.size) {
                            throw IllegalStateException(
                                "ONNX output contains too many values"
                            )
                        }

                        output[index++] = number
                    }
                }

                is Array<*> -> {

                    item.forEach {
                        walk(it)
                    }
                }

                else -> {

                    throw IllegalStateException(
                        "Unsupported ONNX output type: " +
                            (item?.javaClass?.name ?: "null")
                    )
                }
            }
        }

        walk(value)

        if (index != output.size) {
            throw IllegalStateException(
                "Incomplete ONNX output: $index / ${output.size}"
            )
        }

        return output
    }

    // Model output: [1, 5, 8400]
    private fun decode(
        data: FloatArray,
        srcW: Float,
        srcH: Float,
        scaleX: Float,
        scaleY: Float,
        padX: Float,
        padY: Float
    ): List<Detection> {

        val n = outputCandidates

        val candidates = mutableListOf<Detection>()

        var maxConfidence = 0f
        var detectedCandidates = 0

        for (i in 0 until n) {

            val cx = data[i]
            val cy = data[n + i]
            val w = data[2 * n + i]
            val h = data[3 * n + i]
            val score = data[4 * n + i]

            if (!score.isFinite()) continue

            maxConfidence = max(
                maxConfidence,
                score
            )

            if (
                score < confidenceThreshold ||
                score > 1f
            ) {
                continue
            }

            if (
                !cx.isFinite() ||
                !cy.isFinite() ||
                !w.isFinite() ||
                !h.isFinite() ||
                w <= 0f ||
                h <= 0f
            ) {
                continue
            }

            detectedCandidates++

            // Restore original image coordinates.
            val left = (
                cx - w / 2f - padX
            ) / scaleX

            val top = (
                cy - h / 2f - padY
            ) / scaleY

            val right = (
                cx + w / 2f - padX
            ) / scaleX

            val bottom = (
                cy + h / 2f - padY
            ) / scaleY

            if (
                !left.isFinite() ||
                !top.isFinite() ||
                !right.isFinite() ||
                !bottom.isFinite()
            ) {
                continue
            }

            val detection = Detection(
                left = left.coerceIn(0f, srcW),
                top = top.coerceIn(0f, srcH),
                right = right.coerceIn(0f, srcW),
                bottom = bottom.coerceIn(0f, srcH),
                confidence = score
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
            "Max confidence: $maxConfidence | " +
                "Candidates: $detectedCandidates"
        )

        // Keep highest-confidence candidates.
        val limitedCandidates = candidates
            .sortedByDescending {
                it.confidence
            }
            .take(maxCandidates)

        val results = nms(
            limitedCandidates,
            nmsThreshold
        ).take(maxDetections)

        Log.i(
            "YOLO",
            "Final detections: ${results.size}"
        )

        return results
    }

    // Non-Maximum Suppression.
    private fun nms(
        items: List<Detection>,
        threshold: Float
    ): List<Detection> {

        val sorted = items
            .sortedByDescending {
                it.confidence
            }
            .toMutableList()

        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {

            val best = sorted.removeAt(0)

            kept.add(best)

            val iterator = sorted.iterator()

            while (iterator.hasNext()) {

                val candidate = iterator.next()

                if (
                    iou(best, candidate) > threshold
                ) {
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

        val interWidth = max(
            0f,
            x2 - x1
        )

        val interHeight = max(
            0f,
            y2 - y1
        )

        val intersection =
            interWidth * interHeight

        val areaA =
            (a.right - a.left) *
                (a.bottom - a.top)

        val areaB =
            (b.right - b.left) *
                (b.bottom - b.top)

        val union =
            areaA + areaB - intersection

        if (
            union <= 0f ||
            !union.isFinite()
        ) {
            return 0f
        }

        return (intersection / union)
            .coerceIn(0f, 1f)
    }

    fun close() {
        session.close()
    }
}
