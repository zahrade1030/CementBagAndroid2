package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class DetectionOverlay(context: Context) : View(context) {

    var isSetup = true
    var lineTop = 150f
    var lineBottom = 411f

    private var imageW = 640f
    private var imageH = 640f
    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint(3).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val linePaint = Paint(3).apply {
        color = Color.YELLOW
        strokeWidth = 5f
    }

    private val textPaint = Paint(3).apply {
        color = Color.WHITE
        textSize = 30f
    }

    private var dragging = 0

    fun setDetections(list: List<Detection>, w: Int, h: Int) {
        detections = list
        imageW = w.toFloat()
        imageH = h.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageW <= 0f || imageH <= 0f || width == 0 || height == 0) return

        val scale = max(width / imageW, height / imageH)
        val offsetX = (width - imageW * scale) / 2f
        val offsetY = (height - imageH * scale) / 2f

        detections.forEach {
            val left = it.left * scale + offsetX
            val top = it.top * scale + offsetY
            val right = it.right * scale + offsetX
            val bottom = it.bottom * scale + offsetY

            canvas.drawRect(left, top, right, bottom, boxPaint)

            canvas.drawText(
                "${(it.confidence * 100).toInt()}%",
                left,
                (top - 8f).coerceAtLeast(28f),
                textPaint
            )
        }

        canvas.drawLine(
            offsetX,
            lineTop * scale + offsetY,
            offsetX + imageW * scale,
            lineTop * scale + offsetY,
            linePaint
        )

        canvas.drawLine(
            offsetX,
            lineBottom * scale + offsetY,
            offsetX + imageW * scale,
            lineBottom * scale + offsetY,
            linePaint
        )

        canvas.drawText(
            if (isSetup) "برای جابه‌جایی خط لمس کن" else "خطوط قفل هستند",
            18f,
            38f,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSetup) return false

        val scale = max(width / imageW, height / imageH)
        val offsetY = (height - imageH * scale) / 2f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val y = (event.y - offsetY) / scale
                dragging = if (abs(y - lineTop) < abs(y - lineBottom)) 1 else 2
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val y = ((event.y - offsetY) / scale)
                    .coerceIn(20f, imageH - 20f)

                if (dragging == 1) {
                    lineTop = y
                } else if (dragging == 2) {
                    lineBottom = y
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                dragging = 0
                return true
            }
        }

        return true
    }
}
