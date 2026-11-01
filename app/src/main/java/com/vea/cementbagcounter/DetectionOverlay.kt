package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class DetectionOverlay(context: Context) : View(context) {
    var isSetup = true
    var lineTop = 150f
    var lineBottom = 411f
    private var imageW = 640f
    private var imageH = 640f
    private var detections: List<Detection> = emptyList()
    private val boxPaint = Paint(3).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val linePaint = Paint(3).apply { color = Color.YELLOW; strokeWidth = 5f }
    private val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 30f }
    private var dragging = 0

    fun setDetections(list: List<Detection>, w: Int, h: Int) {
        detections = list; imageW = w.toFloat(); imageH = h.toFloat(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width / imageW; val sy = height / imageH
        detections.forEach {
            canvas.drawRect(it.left*sx, it.top*sy, it.right*sx, it.bottom*sy, boxPaint)
            canvas.drawText("${(it.confidence*100).toInt()}%", it.left*sx, (it.top*sy-8).coerceAtLeast(28f), textPaint)
        }
        canvas.drawLine(0f, lineTop*sy, width.toFloat(), lineTop*sy, linePaint)
        canvas.drawLine(0f, lineBottom*sy, width.toFloat(), lineBottom*sy, linePaint)
        canvas.drawText(if (isSetup) "برای جابه‌جایی خط لمس کن" else "خطوط قفل هستند", 18f, 38f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSetup) return false
        val sy = height / imageH
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val y = event.y / sy
                dragging = if (abs(y-lineTop) < abs(y-lineBottom)) 1 else 2
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val y = (event.y / sy).coerceIn(20f, imageH-20f)
                if (dragging == 1) lineTop = y else if (dragging == 2) lineBottom = y
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> { dragging = 0; return true }
        }
        return true
    }
}
