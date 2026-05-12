package com.example.gemmakey

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Collects screen text and (on API 30+) takes a low-res screenshot on demand.
 *
 * The service must be enabled by the user in
 * Settings → Accessibility → GemmaKey.
 *
 * A singleton reference is used so [GemmaKeyIMEService] can query it
 * without binding — both services run in the same process.
 */
class GemmaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GemmaA11y"

        /** Non-null while service is connected. */
        @Volatile
        var instance: GemmaAccessibilityService? = null
            private set
    }

    // Current snapshot of visible screen text
    private val screenTextRef = AtomicReference<String>("")
    // Most recent screenshot captured asynchronously
    private val screenshotRef = AtomicReference<Bitmap?>(null)
    // Package name of the foreground window
    private val foregroundPackageRef = AtomicReference<String>("")

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        screenshotRef.getAndSet(null)?.recycle()
        super.onDestroy()
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        event.packageName?.toString()?.let { foregroundPackageRef.set(it) }

        // Refresh text snapshot on window / content changes
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                refreshScreenText()
            }
        }
    }

    // ── Public API (called from GemmaKeyIMEService) ───────────────────────────

    /** Returns the last-collected visible text (non-blocking). */
    fun getScreenText(): String = screenTextRef.get()

    /** Returns the last screenshot taken, or null if none available. */
    fun getLastScreenshot(): Bitmap? = screenshotRef.getAndSet(null)

    fun getForegroundPackage(): String = foregroundPackageRef.get()

    /**
     * Requests a screenshot asynchronously.  The bitmap is stored in
     * [screenshotRef] and retrieved via [getLastScreenshot] after ~100 ms.
     *
     * Requires API 30+.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestScreenshot() {
        takeScreenshot(
            TAKE_SCREENSHOT_HARD_COPY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val bmp = screenshotResult.hardwareBitmap.copy(
                        Bitmap.Config.ARGB_8888, false
                    )
                    screenshotResult.hardwareBitmap.recycle()
                    // Swap in; recycle the previous one if any
                    screenshotRef.getAndSet(bmp)?.recycle()
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot failed: errorCode=$errorCode")
                }
            }
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun refreshScreenText() {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collectText(root, sb, depth = 0)
        root.recycle()
        screenTextRef.set(sb.toString().take(2000)) // Cap to avoid OOM in prompts
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 10) return
        node.text?.takeIf { it.isNotBlank() }?.let { sb.appendLine(it) }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.appendLine(it) }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }
}
