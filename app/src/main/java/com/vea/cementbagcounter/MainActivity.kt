
package com.vea.cementbagcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var guideImage: ImageView
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var qualityText: TextView
    private lateinit var detector: YoloDetector

    private val executor = Executors.newSingleThreadExecutor()

    private val guidePrefs by lazy {
        getSharedPreferences("camera_guide_settings", MODE_PRIVATE)
    }

    private var guideBitmap: Bitmap? = null

    private var running = true
    private var setupMode = true
    private var totalCount = 0
    private var qualityFrameCounter = 0

    private var guideVisible = true
    private var guideOpacity = 0.35f

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

        guideVisible = guidePrefs.getBoolean("visible", true)
        guideOpacity = guidePrefs.getFloat("opacity", 0.35f)

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

        guideImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = guideOpacity
            visibility =
                if (guideVisible) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
        }

        loadGuideImage()

        overlay = DetectionOverlay(this)

        val cameraFrame = FrameLayout(this)

        cameraFrame.addView(
            preview,
            FrameLayout.LayoutParams(-1, -1)
        )

        // تصویر راهنما فقط یک لایه نمایشی است.
        // این تصویر وارد پردازش YOLO نمی‌شود.
        cameraFrame.addView(
            guideImage,
            FrameLayout.LayoutParams(-1, -1)
        )

        cameraFrame.addView(
            overlay,
            FrameLayout.LayoutParams(-1, -1)
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

        val guideButton = Button(this).apply {
            text = "تنظیم تصویر راهنما"
            setOnClickListener {
                showGuideSettings()
            }
        }

        base.addView(header)

        base.addView(
            cameraFrame,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        base.addView(countText)
        base.addView(statusText)
        base.addView(qualityText)
        base.addView(guideButton)
        base.addView(buttons)

        setContentView(base)
    }

    private fun loadGuideImage() {

        try {
            guideBitmap = assets.open("camera_guide.jpg").use {
                BitmapFactory.decodeStream(it)
            }

            if (guideBitmap != null) {
                guideImage.setImageBitmap(guideBitmap)
                guideImage.alpha = guideOpacity
                guideImage.visibility =
                    if (guideVisible) View.VISIBLE else View.GONE
            } else {
                android.util.Log.e(
                    "CAMERA_GUIDE",
                    "Guide image could not be decoded"
                )
            }

        } catch (e: Exception) {

            android.util.Log.e(
                "CAMERA_GUIDE",
                "Guide image loading failed",
                e
            )
        }
    }

    private fun showGuideSettings() {

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 20, 45, 10)
        }

        val visibilityCheck = CheckBox(this).apply {
            text = "نمایش تصویر راهنما"
            setTextColor(Color.BLACK)
            isChecked = guideVisible
        }

        val opacityLabel = TextView(this).apply {
            text = "شفافیت تصویر: ${(guideOpacity * 100).toInt()}٪"
            textSize = 15f
            setPadding(0, 25, 0, 8)
        }

        val opacitySeekBar = SeekBar(this).apply {
            max = 90
            progress = (guideOpacity * 100).toInt()
                .coerceIn(10, 90)
        }

        panel.addView(visibilityCheck)
        panel.addView(opacityLabel)
        panel.addView(opacitySeekBar)

        visibilityCheck.setOnCheckedChangeListener { _, checked ->

            guideVisible = checked

            guideImage.visibility =
                if (checked) View.VISIBLE else View.GONE

            guidePrefs.edit()
                .putBoolean("visible", checked)
                .apply()
        }

        opacitySeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    guideOpacity =
                        progress.coerceIn(10, 90) / 100f

                    guideImage.alpha = guideOpacity

                    opacityLabel.text =
                        "شفافیت تصویر: ${(guideOpacity * 100).toInt()}٪"

                    guidePrefs.edit()
                        .putFloat("opacity", guideOpacity)
                        .apply()
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {}
            }
        )

        AlertDialog.Builder(this)
            .setTitle("تنظیم تصویر مرجع دوربین")
            .setView(panel)
            .setPositiveButton("بستن", null)
            .show()
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

                    // فقط تصویر واقعی دوربین به YOLO داده می‌شود.
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
        guideBitmap?.recycle()
        guideBitmap = null
    }
}
