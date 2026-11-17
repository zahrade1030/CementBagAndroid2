
package com.vea.cementbagcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var qualityText: TextView
    private lateinit var detector: YoloDetector

    private val executor = Executors.newSingleThreadExecutor()

    private var running = true
    private var setupMode = true
    private var totalCount = 0
    private var qualityFrameCounter = 0

    private val tracker = SimpleLineCounter()

    private val permission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            if (it) {
                startCamera()
            } else {
                statusText.text = "مجوز دوربین داده نشد"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        detector = YoloDetector(this)

        buildUi()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {

        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 28, 24))
        }

        val header = TextView(this).apply {
            text = "شمارشگر کیسه"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 14, 8, 14)
        }

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DetectionOverlay(this)

        val cameraFrame = android.widget.FrameLayout(this)

        cameraFrame.addView(
            preview,
            android.widget.FrameLayout.LayoutParams(-1, -1)
        )

        cameraFrame.addView(
            overlay,
            android.widget.FrameLayout.LayoutParams(-1, -1)
        )

        countText = TextView(this).apply {
            text = "تعداد: 0"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 4)
        }

        statusText = TextView(this).apply {
            text = "خطوط را در حالت تنظیم جابه‌جا کن"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 4)
        }

        qualityText = TextView(this).apply {
            text = "کیفیت تصویر: در حال بررسی..."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun button(label: String, action: () -> Unit) {
            buttons.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                }
            )
        }

        button("تنظیم خطوط") {
            setupMode = true
            overlay.isSetup = true
            statusText.text = "خطوط را با انگشت جابه‌جا کن"
        }

        button("شروع شمارش") {
            setupMode = false
            overlay.isSetup = false
            running = true

            tracker.setLines(
                overlay.lineTop,
                overlay.lineBottom
            )

            statusText.text = "شمارش فعال است"
        }

        button("توقف") {
            running = false
            statusText.text = "متوقف شد"
        }

        button("ریست") {
            totalCount = 0
            tracker.reset()
            countText.text = "تعداد: 0"
            statusText.text = "شمارش از ابتدا آماده است"
        }

        base.addView(header)

        base.addView(
            cameraFrame,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        base.addView(countText)
        base.addView(statusText)
        base.addView(qualityText)
        base.addView(buttons)

        setContentView(base)
    }

    private fun startCamera() {

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({

            val provider = future.get()

            val previewUseCase = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 640))
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()

            analysis.setAnalyzer(executor) { image ->

                if (!running) {
                    image.close()
                    return@setAnalyzer
                }

                var bitmap: android.graphics.Bitmap? = null

                try {

                    qualityFrameCounter++

                    if (qualityFrameCounter % 8 == 0) {
                        val quality = analyzeImageQuality(image)

                        if (quality != null) {
                            runOnUiThread {
                                qualityText.text =
                                    getQualityMessage(
                                        quality.first,
                                        quality.second
                                    )
                            }
                        }
                    }

                    bitmap = ImageUtil.toBitmap(image)

                    val detections = detector.detect(bitmap)

                    val imageWidth = bitmap.width
                    val imageHeight = bitmap.height

                    runOnUiThread {

                        if (!setupMode && running) {

                            totalCount += tracker.update(detections)

                            countText.text = "تعداد: $totalCount"

                            overlay.setTrackedPoints(
                                tracker.getTrackedPoints(),
                                imageWidth,
                                imageHeight
                            )
                        }
                    }

                } catch (e: Exception) {

                    android.util.Log.e(
                        "CAMERA",
                        "Processing error",
                        e
                    )

                } finally {

                    bitmap?.recycle()
                    image.close()
                }
            }

            try {

                provider.unbindAll()

                val viewPort = preview.viewPort

                if (viewPort == null) {
                    statusText.text =
                        "در حال آماده‌سازی تصویر دوربین..."
                    return@addListener
                }

                val useCaseGroup = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(previewUseCase)
                    .addUseCase(analysis)
                    .build()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCaseGroup
                )

                statusText.text = "دوربین آماده است"

            } catch (e: Exception) {

                statusText.text =
                    "خطای دوربین: ${e.localizedMessage}"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImageQuality(
        image: ImageProxy
    ): Pair<Float, Float>? {

        return try {

            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (width < 8 || height < 8) return null

            var brightnessSum = 0.0
            var brightnessCount = 0

            var sharpnessSum = 0.0
            var sharpnessCount = 0

            val step = 4

            fun pixel(x: Int, y: Int): Int {
                val index =
                    y * rowStride + x * pixelStride

                return buffer.get(index).toInt() and 0xFF
            }

            for (y in step until height - step step step) {
                for (x in step until width - step step step) {

                    val center = pixel(x, y)

                    brightnessSum += center
                    brightnessCount++

                    val left = pixel(x - step, y)
                    val right = pixel(x + step, y)
                    val top = pixel(x, y - step)
                    val bottom = pixel(x, y + step)

                    val laplacian =
                        left + right + top + bottom - 4 * center

                    sharpnessSum +=
                        laplacian.toDouble() * laplacian.toDouble()

                    sharpnessCount++
                }
            }

            if (brightnessCount == 0 || sharpnessCount == 0) {
                return null
            }

            val brightness =
                (brightnessSum / brightnessCount).toFloat()

            val sharpness =
                (sharpnessSum / sharpnessCount).toFloat()

            Pair(brightness, sharpness)

        } catch (e: Exception) {

            android.util.Log.e(
                "IMAGE_QUALITY",
                "Quality analysis failed",
                e
            )

            null
        }
    }

    private fun getQualityMessage(
        brightness: Float,
        sharpness: Float
    ): String {

        val messages = mutableListOf<String>()

        when {
            brightness < 55f ->
                messages.add("نور محیط کم است")

            brightness > 205f ->
                messages.add("نور تصویر بیش از حد زیاد است")
        }

        when {
            sharpness < 35f ->
                messages.add("احتمال تاری تصویر وجود دارد")

            sharpness < 100f ->
                messages.add("وضوح تصویر متوسط است")
        }

        if (messages.isEmpty()) {
            return "کیفیت تصویر: مناسب"
        }

        return "کیفیت تصویر: " + messages.joinToString(" | ")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
    }
}
