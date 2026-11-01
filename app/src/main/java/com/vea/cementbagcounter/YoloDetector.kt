package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
}

class YoloDetector(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputSize = 640

    init {
        val model = context.assets.open("best.onnx").readBytes()
        session = env.createSession(model)
        inputName = session.inputNames.first()
    }

    fun detect(source: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val input = FloatArray(1 * 3 * inputSize * inputSize)
        val plane = inputSize * inputSize
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = ((p shr 16) and 255) / 255f
            input[plane + i] = ((p shr 8) and 255) / 255f
            input[2 * plane + i] = (p and 255) / 255f
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))
        tensor.use {
            val result = session.run(mapOf(inputName to tensor))
            result.use {
                val value = result[0].value
                val data = flatten(value)
                return decode(data, source.width.toFloat(), source.height.toFloat())
            }
        }
    }

    private fun flatten(value: Any): FloatArray {
        fun walk(v: Any?, out: MutableList<Float>) {
            when (v) {
                is FloatArray -> v.forEach(out::add)
                is Array<*> -> v.forEach { if (it != null) walk(it, out) }
            }
        }
        val out = mutableListOf<Float>()
        walk(value, out)
        return out.toFloatArray()
    }

    // Supports the common single-class YOLO output [1,5,N] or [1,N,5].
    private fun decode(a: FloatArray, srcW: Float, srcH: Float): List<Detection> {
        if (a.size < 5) return emptyList()
        val n = a.size / 5
        val channelFirst = a.size % 5 == 0 && n > 100
        val out = mutableListOf<Detection>()
        for (i in 0 until n) {
            fun at(c: Int): Float = if (channelFirst) a[c * n + i] else a[i * 5 + c]
            val score = at(4)
            if (score < 0.40f || score > 1.01f) continue
            val cx = at(0) * srcW / inputSize
            val cy = at(1) * srcH / inputSize
            val w = at(2) * srcW / inputSize
            val h = at(3) * srcH / inputSize
            out.add(Detection(
                max(0f, cx - w / 2), max(0f, cy - h / 2),
                min(srcW, cx + w / 2), min(srcH, cy + h / 2), score
            ))
        }
        return nms(out, 0.45f).take(60)
    }

    private fun nms(items: List<Detection>, threshold: Float): List<Detection> {
        val sorted = items.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) if (iou(best, iterator.next()) > threshold) iterator.remove()
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2-x1) * max(0f, y2-y1)
        val areaA = (a.right-a.left)*(a.bottom-a.top)
        val areaB = (b.right-b.left)*(b.bottom-b.top)
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    fun close() { session.close() }
}
