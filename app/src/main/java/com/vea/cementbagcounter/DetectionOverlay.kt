
package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class DetectionOverlay(context: Context) : View(context) {

    var isSetup = true

    var lineTop = 150f
        private set

    var lineBottom = 411f
        private set

    private var imageW = 640f
    private var imageH = 640f

    private var points: List<TrackedPoint> = emptyList()

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var dragging = 0

    private val minimumLineGap = 40f
    private val touchTolerance = 35f

    private fun imageScale(): Float {
        if (imageW <= 0f || imageH <= 0f ||
            width <= 0 || height <= 0
        ) return 1f

        return max(width / imageW, height / imageH)
    }

    private fun offsetX(scale: Float): Float {
        return (width - imageW * scale) / 2f
    }

    private fun offsetY(scale: Float): Float {
        return (height - imageH * scale) / 2f
    }

    fun setImageSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        imageW = w.toFloat()
        imageH = h.toFloat()

        lineTop = lineTop.coerceIn(20f, imageH - 20f)
        lineBottom = lineBottom.coerceIn(
            lineTop + minimumLineGap,
            imageH - 20f
        )

        invalidate()
    }

    fun setTrackedPoints(
        list: List<TrackedPoint>,
        w: Int,
        h: Int
    ) {
        setImageSize(w, h)
        points = list.toList()
        invalidate()
    }

    fun getLines(): Pair<Float, Float> {
        return Pair(lineTop, lineBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (
            imageW <= 0f ||
            imageH <= 0f ||
            width == 0 ||
            height == 0
        ) return

        val scale = imageScale()
        val ox = offsetX(scale)
        val oy = offsetY(scale)

        // Draw tracked object centers
        points.forEach { point ->

            val x = point.x * scale + ox
            val y = point.y * scale + oy

            if (
                x < 0f || x > width.toFloat() ||
                y < 0f || y > height.toFloat()
            ) return@forEach

            pointPaint.color =
                if (point.counted) Color.CYAN else Color.GREEN

            canvas.drawCircle(x, y, 11f, pointPaint)
            canvas.drawCircle(x, y, 16f, ringPaint)

            canvas.drawText(
                "#${point.id}",
                x + 18f,
                y - 15f,
                textPaint
            )
        }

        // Draw counting lines
        val left = ox
        val right = ox + imageW * scale

        val topY = lineTop * scale + oy
        val bottomY = lineBottom * scale + oy

        linePaint.color = Color.YELLOW
        linePaint.strokeWidth = 5f

        canvas.drawLine(left, topY, right, topY, linePaint)
        canvas.drawLine(left, bottomY, right, bottomY, linePaint)

        if (isSetup) {

            val handleRadius = 13f

            canvas.drawCircle(
                width / 2f,
                topY,
                handleRadius,
                handlePaint
            )

            canvas.drawCircle(
                width / 2f,
                bottomY,
                handleRadius,
                handlePaint
            )

            drawLabel(
                canvas,
                "خط اول",
                left + 12f,
                topY - 12f
            )

            drawLabel(
                canvas,
                "خط دوم",
                left + 12f,
                bottomY - 12f
            )
        }

        val message =
            if (isSetup) "خطوط را جابه‌جا کن"
            else "ردیابی فعال"

        canvas.drawRoundRect(
            8f,
            8f,
            245f,
            52f,
            8f,
            8f,
            backgroundPaint
        )

        canvas.drawText(
            message,
            18f,
            39f,
            textPaint
        )
    }

    private fun drawLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        y: Float
    ) {
        canvas.drawText(label, x, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (!isSetup) return false

        if (width == 0 || height == 0) return false

        val scale = imageScale()
        val oy = offsetY(scale)

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                val imageY = (event.y - oy) / scale

                val distanceTop = abs(imageY - lineTop)
                val distanceBottom = abs(imageY - lineBottom)

                dragging = when {
                    distanceTop <= touchTolerance &&
                        distanceTop <= distanceBottom -> 1

                    distanceBottom <= touchTolerance -> 2

                    else -> 0
                }

                return dragging != 0
            }

            MotionEvent.ACTION_MOVE -> {

                if (dragging == 0) return false

                val imageY = ((event.y - oy) / scale)
                    .coerceIn(20f, imageH - 20f)

                when (dragging) {

                    1 -> {
                        lineTop = imageY.coerceAtMost(
                            lineBottom - minimumLineGap
                        )
                    }

                    2 -> {
                        lineBottom = imageY.coerceAtLeast(
                            lineTop + minimumLineGap
                        ).coerceAtMost(imageH - 20f)
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                val wasDragging = dragging != 0
                dragging = 0

                performClick()

                return wasDragging
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
