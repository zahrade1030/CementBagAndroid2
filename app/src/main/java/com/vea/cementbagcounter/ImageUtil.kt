package com.vea.cementbagcounter

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

object ImageUtil {
    fun toBitmap(image: ImageProxy): Bitmap {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        val rowStride = image.planes[1].rowStride
        val pixelStride = image.planes[1].pixelStride
        var offset = ySize
        val width = image.width
        val height = image.height
        val uBytes = ByteArray(u.remaining()).also { image.planes[1].buffer.rewind(); image.planes[1].buffer.get(it) }
        val vBytes = ByteArray(v.remaining()).also { image.planes[2].buffer.rewind(); image.planes[2].buffer.get(it) }
        var i = 0
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * rowStride + col * pixelStride
                nv21[offset++] = vBytes[index]
                nv21[offset++] = uBytes[index]
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bmp
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}
