package com.example.gemmakey

import android.graphics.Bitmap
import com.example.gemmakey.screen.ScreenContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runCatching

/**
 * Collects multimodal context (screen text + screenshot) in parallel with speech
 * recognition so that context is ready before inference begins.
 *
 * Typical call sequence:
 *   onMicDown()  → startCollection(scope)       ← runs during user speech, zero net latency
 *   onResults()  → awaitContext(supportsVision)  ← returns immediately if already done
 *   after use    → releaseBitmap(ctx)            ← recycles screenshot when no longer needed
 *
 * ## State transitions
 *
 *   IDLE ──startCollection()──► COLLECTING ──awaitContext()──► IDLE
 *
 * ## Modality selection
 *
 *   MINIMAL      — engine not ready or collection failed; ASR text only
 *   TEXT_CONTEXT — screen text available; no screenshot (engine text-only or no bitmap)
 *   FULL         — screen text + screenshot (multimodal engine + bitmap captured)
 */
class ModalityCollector {

    enum class ModalityState { MINIMAL, TEXT_CONTEXT, FULL }

    data class CollectedContext(
        val screenText: String,
        val screenshot: Bitmap?,
        val packageName: String,
        val state: ModalityState
    )

    private var job: Deferred<ScreenContextProvider.ScreenContext>? = null

    /** Call immediately when the mic button is pressed to start parallel collection. */
    fun startCollection(scope: CoroutineScope) {
        job?.cancel()
        job = scope.async(Dispatchers.IO) { ScreenContextProvider.capture() }
    }

    /**
     * Awaits the in-flight collection and returns the collated context.
     *
     * If collection finished during speech the call returns immediately.
     * [supportsVision] controls whether the screenshot is retained or recycled.
     */
    suspend fun awaitContext(supportsVision: Boolean): CollectedContext {
        val deferred = job ?: return CollectedContext("", null, "", ModalityState.MINIMAL)
        job = null

        val ctx = runCatching { deferred.await() }.getOrNull()
            ?: return CollectedContext("", null, "", ModalityState.MINIMAL)

        return if (supportsVision && ctx.screenshot != null) {
            CollectedContext(ctx.text, ctx.screenshot, ctx.packageName, ModalityState.FULL)
        } else {
            // Engine is text-only — discard screenshot immediately to free memory.
            ctx.screenshot?.recycle()
            val state = if (ctx.text.isNotBlank()) ModalityState.TEXT_CONTEXT else ModalityState.MINIMAL
            CollectedContext(ctx.text, null, ctx.packageName, state)
        }
    }

    /** Recycles the screenshot bitmap when the caller is finished with it. */
    fun releaseBitmap(ctx: CollectedContext) {
        ctx.screenshot?.recycle()
    }

    /** Cancels an in-progress collection (e.g. on service destroy). */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
