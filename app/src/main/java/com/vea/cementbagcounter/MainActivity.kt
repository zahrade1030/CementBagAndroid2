package com.vea.cementbagcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
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

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var detector: YoloDetector
    private val executor = Executors.newSingleThreadExecutor()
    private var running = true
    private var setupMode = true
    private var totalCount = 0
    private val tracker = SimpleLineCounter()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else statusText.text = "مجوز دوربین داده نشد"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detector = YoloDetector(this)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 28, 24))
        }
        val header = TextView(this).apply {
            text = "شمارشگر کیسه"
            textSize = 21f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(8, 14, 8, 14)
        }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = DetectionOverlay(this)
        val cameraFrame = androidx_frame()
        cameraFrame.addView(preview)
        cameraFrame.addView(overlay)
        countText = TextView(this).apply {
            text = "تعداد: 0"; textSize = 27f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(8, 10, 8, 4)
        }
        statusText = TextView(this).apply {
            text = "خطوط را در حالت تنظیم جابه‌جا کن"; textSize = 14f
            setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(8, 4, 8, 8)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("تنظیم خطوط") {
            setupMode = true; overlay.isSetup = true
            statusText.text = "خطوط را با انگشت جابه‌جا کن"
        }
        button("شروع شمارش") {
            setupMode = false; overlay.isSetup = false; running = true
            tracker.setLines(overlay.lineTop, overlay.lineBottom)
            statusText.text = "شمارش فعال است"
        }
        button("توقف") { running = false; statusText.text = "متوقف شد" }
        button("ریست") {
            totalCount = 0; tracker.reset(); countText.text = "تعداد: 0"
            statusText.text = "شمارش از ابتدا آماده است"
        }
        base.addView(header)
        base.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        base.addView(countText)
        base.addView(statusText)
        base.addView(buttons)
        setContentView(base)
    }

    private fun androidx_frame(): android.widget.FrameLayout =
        android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                if (!running) { image.close(); return@setAnalyzer }
                try {
                    val bitmap = ImageUtil.toBitmap(image)
                    val detections = detector.detect(bitmap)
                    runOnUiThread {
                        overlay.setDetections(detections, bitmap.width, bitmap.height)
                        if (!setupMode) {
                            totalCount += tracker.update(detections)
                            countText.text = "تعداد: $totalCount"
                        }
                    }
                } catch (_: Exception) {
                    // Keep camera preview alive; model errors are reported in status.
                } finally { image.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) { statusText.text = "خطای دوربین: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
    }
}
