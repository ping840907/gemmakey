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

    /**
     * Decodes a URI to a Bitmap scaled to at most [maxSize] pixels on the longest side.
     * Uses a two-pass decode (inJustDecodeBounds then inSampleSize) so the full-resolution
     * image is never loaded into the Java heap — prevents OOM on high-res camera photos.
     */
    fun uriToBitmap(context: Context, uri: Uri, maxSize: Int = 1024): Bitmap? = runCatching {
        // Pass 1: measure dimensions without allocating pixels
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

        val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxSize)

        // Pass 2: decode at reduced resolution
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return@runCatching null

        val rotated = correctOrientation(context, uri, sampled)

        // Final scale to exact maxSize (inSampleSize is a power of 2, may still be slightly over)
        val scale = minOf(maxSize.toFloat() / rotated.width, maxSize.toFloat() / rotated.height, 1f)
        if (scale >= 1f) rotated
        else Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
    }.getOrNull()

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        val longer = maxOf(width, height)
        while (longer / (sample * 2) > maxSize) sample *= 2
        return sample
    }

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
