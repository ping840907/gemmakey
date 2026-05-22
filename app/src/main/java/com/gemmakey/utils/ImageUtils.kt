package com.gemmakey.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
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
     *
     * The content stream is opened exactly once and buffered as a byte array.
     * BitmapFactory then runs two passes from that in-memory buffer:
     *   1. inJustDecodeBounds to read dimensions without pixel allocation
     *   2. inSampleSize to decode at target resolution
     *
     * This avoids the FileProvider "multiple opens" crash that plagued the earlier
     * implementation while still preventing full-resolution allocation on high-MP
     * camera images (which can peak at 40–50 MB before downscaling).
     *
     * EXIF orientation is read from the same byte array via ByteArrayInputStream
     * (no additional URI open). Intermediate bitmaps are recycled immediately
     * after use; the returned Bitmap is owned by the caller.
     *
     * Returns null on any decode failure or if [maxSize] ≤ 0.
     */
    fun uriToBitmap(context: Context, uri: Uri, maxSize: Int = 1024): Bitmap? = runCatching {
        // Single URI open — avoids FileProvider multi-open crash
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null

        // Pass 1: measure dimensions without allocating pixels
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

        // Pass 2: decode at power-of-2 reduced resolution
        val sampled = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxSize)
            }
        ) ?: return@runCatching null

        // Rotate to upright orientation (recycles sampled if a new bitmap is created)
        val rotated = correctOrientationFromBytes(bytes, sampled)

        // Fine-scale: inSampleSize is power-of-2 and may still be slightly over target
        val scale = minOf(maxSize.toFloat() / rotated.width, maxSize.toFloat() / rotated.height, 1f)
        if (scale >= 1f) {
            rotated
        } else {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt(),
                (rotated.height * scale).toInt(),
                true
            ).also { rotated.recycle() }
        }
    }.getOrNull()

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        val longer = maxOf(width, height)
        while (longer / (sample * 2) > maxSize) sample *= 2
        return sample
    }

    /**
     * Returns a correctly-oriented copy of [bitmap], recycling [bitmap] if a
     * rotation matrix is applied. Returns [bitmap] unchanged when no rotation
     * is needed so the caller does not create an unnecessary copy.
     */
    private fun correctOrientationFromBytes(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        // ByteArrayInputStream wraps the existing array — no extra I/O or URI open
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
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
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()   // source is no longer needed after rotation
        return rotated
    }
}
