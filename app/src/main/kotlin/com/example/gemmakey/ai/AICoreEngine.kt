package com.example.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.ModelStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * AICore-backed engine that routes inference through Gemini Nano via ML Kit GenAI Prompt API.
 *
 * Only instantiated when [AIEngineFactory.isAICoreAvailable] returns true.
 * All processing is on-device; no network calls are made.
 */
class AICoreEngine(private val context: Context) : AIEngine {

    // ML Kit GenAI Prompt API is text-only — no image/PCM audio support.
    override val supportsVision: Boolean = false
    override val supportsNativeAudio: Boolean = false

    private val TAG = "AICoreEngine"

    private var model: GenerativeModel? = null

    override var isReady: Boolean = false
        private set

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val gm = Generation.getClient(generationConfig {
                temperature = 0.1f
                topK = 1
                maxOutputTokens = 512
            })

            when (gm.checkStatus()) {
                ModelStatus.AVAILABLE -> Unit
                ModelStatus.DOWNLOADABLE -> {
                    Log.i(TAG, "Downloading Gemini Nano model…")
                    gm.download().collect { status ->
                        when (status) {
                            is DownloadStatus.Failed ->
                                throw IllegalStateException("Gemini Nano download failed: ${status.errorCode}")
                            else -> Unit
                        }
                    }
                    Log.i(TAG, "Gemini Nano download complete")
                }
                else -> throw IllegalStateException("Gemini Nano unavailable on this device")
            }

            gm.warmup()
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
            .collect { chunk -> chunk.candidates.firstOrNull()?.text?.let { sb.append(it) } }
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
    ): TranscriptionResult? = null  // ML Kit GenAI Prompt API does not expose a PCM audio API.

    override fun release() {
        model?.close()
        model = null
        isReady = false
        System.gc()
        Log.d(TAG, "AICoreEngine released")
    }
}
