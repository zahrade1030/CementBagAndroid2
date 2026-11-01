package com.vea.cementbagcounter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtil {

    fun toBitmap(image: ImageProxy): Bitmap {

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y channel
        var outputIndex = 0

        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[outputIndex++] =
                    yBuffer.get(row * yPlane.rowStride + col)
            }
        }

        // Copy interleaved VU channels
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {

                val uIndex =
                    row * uPlane.rowStride + col * uPlane.pixelStride

                val vIndex =
                    row * vPlane.rowStride + col * vPlane.pixelStride

                nv21[outputIndex++] = vBuffer.get(vIndex)
                nv21[outputIndex++] = uBuffer.get(uIndex)
            }
        }

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            95,
            output
        )

        val bitmap = BitmapFactory.decodeByteArray(
            output.toByteArray(),
            0,
            output.size()
        )

        val rotation = image.imageInfo.rotationDegrees

        if (rotation == 0) return bitmap

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}
