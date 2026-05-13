package com.example.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.ai.edge.aicore.DownloadCallback
import com.google.android.ai.edge.aicore.DownloadConfig
import com.google.android.ai.edge.aicore.GenerativeAIException
import com.google.android.ai.edge.aicore.GenerativeModel
import com.google.android.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * AICore-backed engine that routes inference through Gemini Nano.
 *
 * This engine is only instantiated when [AIEngineFactory.isAICoreAvailable]
 * returns true.  All processing happens on-device; no network calls are made.
 */
class AICoreEngine(private val context: Context) : AIEngine {

    // AICore/Gemini Nano is text-only in the current experimental SDK (0.0.1-exp03).
    override val supportsVision: Boolean = false
    override val supportsNativeAudio: Boolean = false

    private val TAG = "AICoreEngine"

    private var model: GenerativeModel? = null

    override var isReady: Boolean = false
        private set

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val appContext = context
            val config = generationConfig {
                context = appContext
                temperature = 0.1f
                topK = 1
                maxOutputTokens = 512
            }
            val downloadConfig = DownloadConfig(
                downloadCallback = object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {
                        Log.d(TAG, "Model download started: $bytesToDownload bytes")
                    }
                    override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                    override fun onDownloadCompleted() {
                        Log.d(TAG, "Model download complete")
                    }
                    override fun onDownloadFailed(failureStatus: String, e: GenerativeAIException) {
                        Log.e(TAG, "Model download failed [$failureStatus]: ${e.message}")
                    }
                }
            )

            val gm = GenerativeModel(generationConfig = config, downloadConfig = downloadConfig)
            // Warms up the inference engine; blocks until model weights are ready.
            gm.prepareInferenceEngine()

            model = gm
            isReady = true
            Log.i(TAG, "Gemini Nano (AICore) ready")
        } catch (e: Exception) {
            Log.e(TAG, "AICore prepare failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val m = checkNotNull(model) { "AICoreEngine not prepared" }

            val corrected = runGeneration(m, PromptBuilder.build(request)).trim()
            val nouns = extractNouns(m, corrected)

            System.gc()

            TranscriptionResult(
                text = corrected,
                detectedNouns = nouns,
                engineUsed = EngineType.AICORE_GEMINI_NANO
            )
        }

    private suspend fun runGeneration(m: GenerativeModel, prompt: String): String {
        val sb = StringBuilder()
        m.generateContentStream(prompt)
            .catch { e -> Log.e(TAG, "Generation error: ${e.message}") }
            .collect { response -> response.text?.let { sb.append(it) } }
        return sb.toString()
    }

    private suspend fun extractNouns(m: GenerativeModel, text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return try {
            val raw = runGeneration(m, PromptBuilder.buildNounExtraction(text)).trim()
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

    override suspend fun transcribeAudio(
        pcm: ShortArray,
        screenText: String,
        screenBitmap: Bitmap?,
        dictionaryHints: List<String>
    ): TranscriptionResult? = null  // AICore SDK does not expose a PCM audio API.

    override fun release() {
        model?.close()
        model = null
        isReady = false
        System.gc()
        Log.d(TAG, "AICoreEngine released")
    }
}
