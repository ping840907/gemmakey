package com.gemmakey.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtils {

    fun createImageFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(context.cacheDir, "camera_images").also { it.mkdirs() }
        return File(dir, "IMG_${timeStamp}.jpg")
    }

    fun uriToBitmap(context: Context, uri: Uri, maxSize: Int = 1024): Bitmap? = runCatching {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Correct orientation
        val rotated = correctOrientation(context, uri, original)

        // Downscale to maxSize while preserving aspect ratio
        val scale = minOf(maxSize.toFloat() / rotated.width, maxSize.toFloat() / rotated.height, 1f)
        if (scale >= 1f) rotated
        else Bitmap.createScaledBitmap(
            rotated,
            (rotated.width * scale).toInt(),
            (rotated.height * scale).toInt(),
            true
        )
    }.getOrNull()

    private fun correctOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = runCatching { ExifInterface(inputStream) }.getOrNull()
        inputStream.close()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        ) ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
