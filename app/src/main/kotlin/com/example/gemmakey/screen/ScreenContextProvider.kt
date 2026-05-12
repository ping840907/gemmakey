package com.example.gemmakey.screen

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.example.gemmakey.GemmaAccessibilityService

/**
 * Aggregates screen context from [GemmaAccessibilityService] for a single
 * transcription event.
 *
 * Screenshot resolution is deliberately capped to reduce memory pressure; at
 * ~224 × 224 the model still has enough visual signal to identify app type,
 * language, and rough layout without storing a multi-MB bitmap.
 */
object ScreenContextProvider {

    private val TAG = "ScreenContextProvider"
    private const val SCREENSHOT_MAX_DIM = 224

    data class ScreenContext(
        val text: String,
        val screenshot: Bitmap?,
        val packageName: String
    )

    /**
     * Returns a snapshot of the current screen.  Safe to call from any thread;
     * screenshot capture is synchronous only when the accessibility service has
     * a pending bitmap (it captures asynchronously in response to our request).
     */
    fun capture(): ScreenContext {
        val svc = GemmaAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "AccessibilityService not connected")
            return ScreenContext("", null, "")
        }

        val text = svc.getScreenText()
        val rawBitmap = svc.getLastScreenshot()
        val packageName = svc.getForegroundPackage()

        val scaledBitmap = rawBitmap?.let { downscale(it) }
        // Release the original high-res bitmap immediately after scaling
        if (rawBitmap != null && rawBitmap !== scaledBitmap) rawBitmap.recycle()

        return ScreenContext(text, scaledBitmap, packageName)
    }

    private fun downscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= SCREENSHOT_MAX_DIM && h <= SCREENSHOT_MAX_DIM) return src
        val scale = SCREENSHOT_MAX_DIM.toFloat() / maxOf(w, h)
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(src, 0, 0, w, h, matrix, false)
    }
}
