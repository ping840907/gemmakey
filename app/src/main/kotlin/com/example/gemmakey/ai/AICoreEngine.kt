package com.example.gemmakey.ai

import android.content.Context
import android.util.Log
import com.google.android.ai.edge.aicore.GenerativeModel
import com.google.android.ai.edge.aicore.GenerativeException
import com.google.android.ai.edge.aicore.DownloadCallback
import com.google.android.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AICore-backed engine that routes inference through Gemini Nano.
 *
 * This engine is only instantiated when [AIEngineFactory.isAICoreAvailable]
 * returns true.  All processing happens on-device; no network calls are made.
 */
class AICoreEngine(private val context: Context) : AIEngine {

    private val TAG = "AICoreEngine"

    private var model: GenerativeModel? = null

    override var isReady: Boolean = false
        private set

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val config = generationConfig {
                this.context = this@AICoreEngine.context
                temperature = 0.1f   // Low temperature → deterministic / factual
                topK = 1
                maxOutputTokens = 512
            }
            val gm = GenerativeModel(generationConfig = config)

            // Ensure the on-device model weights are available
            ensureModelDownloaded(gm)

            model = gm
            isReady = true
            Log.i(TAG, "Gemini Nano (AICore) ready")
        } catch (e: Exception) {
            Log.e(TAG, "AICore prepare failed: ${e.message}", e)
            throw e
        }
    }

    /** Blocks until AICore reports the model is fully available locally. */
    private suspend fun ensureModelDownloaded(gm: GenerativeModel) =
        suspendCancellableCoroutine { cont ->
            gm.downloadAiCoreModel(object : DownloadCallback {
                override fun onDownloadStarted(bytesTotal: Long) {
                    Log.d(TAG, "Model download started: $bytesTotal bytes")
                }
                override fun onDownloadProgress(bytesDownloaded: Long, bytesTotal: Long) {}
                override fun onDownloadCompleted() {
                    Log.d(TAG, "Model download complete")
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onDownloadFailed(e: GenerativeException) {
                    Log.e(TAG, "Model download failed: ${e.message}")
                    // Model may already be cached — treat as non-fatal and continue
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            checkNotNull(model) { "AICoreEngine not prepared" }

            val correctionPrompt = PromptBuilder.build(request)
            val corrected = runGeneration(correctionPrompt).trim()

            val nouns = extractNouns(corrected)

            // Explicitly help GC reclaim the prompt strings
            System.gc()

            TranscriptionResult(
                text = corrected,
                detectedNouns = nouns,
                engineUsed = EngineType.AICORE_GEMINI_NANO
            )
        }

    private suspend fun runGeneration(prompt: String): String =
        suspendCancellableCoroutine { cont ->
            model!!.generateContentAsync(
                prompt,
                object : com.google.android.ai.edge.aicore.GenerateContentCallback {
                    private val sb = StringBuilder()
                    override fun onResponse(response: com.google.android.ai.edge.aicore.GenerateContentResponse) {
                        response.text?.let { sb.append(it) }
                    }
                    override fun onComplete() {
                        if (cont.isActive) cont.resume(sb.toString())
                    }
                    override fun onError(e: GenerativeException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            )
        }

    private suspend fun extractNouns(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return try {
            val raw = runGeneration(PromptBuilder.buildNounExtraction(text)).trim()
            parseJsonArray(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseJsonArray(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(raw.substring(start, end + 1))
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun release() {
        model = null
        isReady = false
        System.gc()
        Log.d(TAG, "AICoreEngine released")
    }
}
