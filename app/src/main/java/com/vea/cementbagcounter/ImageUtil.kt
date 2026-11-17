
package com.vea.cementbagcounter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ImageUtil {

    fun toBitmap(image: ImageProxy): Bitmap {

        require(image.format == ImageFormat.YUV_420_888) {
            "Unsupported camera image format: ${image.format}"
        }

        val width = image.width
        val height = image.height

        require(width > 0 && height > 0) {
            "Invalid image dimensions"
        }

        require(width % 2 == 0 && height % 2 == 0) {
            "YUV image dimensions must be even"
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()

        val nv21 = ByteArray(width * height * 3 / 2)

        var outputIndex = 0

        // Copy Y channel
        for (row in 0 until height) {
            for (col in 0 until width) {

                val index =
                    yBase + row * yPlane.rowStride +
                        col * yPlane.pixelStride

                require(index < yBuffer.limit()) {
                    "Invalid Y plane buffer index"
                }

                nv21[outputIndex++] = yBuffer.get(index)
            }
        }

        // Convert U and V channels to NV21 VU order
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {

                val uIndex =
                    uBase + row * uPlane.rowStride +
                        col * uPlane.pixelStride

                val vIndex =
                    vBase + row * vPlane.rowStride +
                        col * vPlane.pixelStride

                require(
                    uIndex < uBuffer.limit() &&
                        vIndex < vBuffer.limit()
                ) {
                    "Invalid chroma plane buffer index"
                }

                nv21[outputIndex++] = vBuffer.get(vIndex)
                nv21[outputIndex++] = uBuffer.get(uIndex)
            }
        }

        val crop = image.cropRect

        val left = crop.left.coerceIn(0, width) and -2
        val top = crop.top.coerceIn(0, height) and -2
        val right = crop.right.coerceIn(0, width) and -2
        val bottom = crop.bottom.coerceIn(0, height) and -2

        require(right > left && bottom > top) {
            "Invalid image crop rectangle"
        }

        val cropRect = Rect(left, top, right, bottom)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val output = ByteArrayOutputStream()

        val compressed = yuvImage.compressToJpeg(
            cropRect,
            95,
            output
        )

        require(compressed) {
            "Could not convert camera image to JPEG"
        }

        val bytes = output.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        ) ?: throw IllegalStateException(
            "Could not decode camera image"
        )

        val rotation = image.imageInfo.rotationDegrees

        if (rotation == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )

        if (rotatedBitmap !== bitmap) {
            bitmap.recycle()
        }

        return rotatedBitmap
    }
}
