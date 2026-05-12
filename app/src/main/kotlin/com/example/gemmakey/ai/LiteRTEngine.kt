package com.example.gemmakey.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

// ── LiteRT-LM imports ─────────────────────────────────────────────────────────
// litert-lm is in alpha; import paths may shift between releases.
// Primary API surface used here:
//   LlmInference          — inference session
//   LlmInferenceOptions   — builder for model path, tokens, accelerator
import com.google.ai.edge.litert.lm.LlmInference
import com.google.ai.edge.litert.lm.LlmInferenceOptions

/**
 * Runs a LiteRT-compatible Gemma model fully on-device.
 *
 * ## Supported model files
 * Any of the following (place one in a candidate path below):
 *   • gemma-3-1b-it-litert.bin    ← recommended for most phones (~800 MB)
 *   • gemma-3-4b-it-litert.bin    ← better quality, needs ~4 GB RAM
 *   • gemma-2-2b-it-litert.bin    ← well-tested alternative
 *
 * ## Where to download
 *   1. Kaggle — official Google uploads:
 *        kaggle.com/models/google/gemma-3  (select LiteRT / TFLite variant)
 *        kaggle.com/models/google/gemma-2  (select LiteRT / TFLite variant)
 *   2. Hugging Face — search "gemma litert" or "gemma tflite":
 *        huggingface.co/google/gemma-3-1b-it-litert
 *        huggingface.co/google/gemma-2-2b-it-litert
 *   3. Google AI Edge GitHub (ai-edge-torch) — conversion tools and links.
 *
 * ## File placement (probed in order)
 *   1. <external-files-dir>/gemma-model.bin
 *   2. <internal-files-dir>/models/gemma-model.bin
 *
 * Rename your downloaded file to "gemma-model.bin" or update [MODEL_FILENAME].
 *
 * ## Acceleration hierarchy
 *   1. NNAPI → routes to on-chip NPU/DSP (Qualcomm Hexagon, MediaTek APU,
 *      Samsung Exynos NPU, Google Tensor Edge TPU, etc.)
 *   2. GPU delegate — automatic fallback when NNAPI is unavailable.
 *   3. CPU XNNPACK — final guaranteed fallback on all devices.
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    private val TAG = "LiteRTEngine"

    // Update this name to match your downloaded file, e.g. "gemma-3-1b-it-litert.bin"
    private val MODEL_FILENAME = "gemma-model.bin"

    private val candidatePaths: List<String>
        get() = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath,
            File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
        )

    private var inference: LlmInference? = null

    override var isReady: Boolean = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        val modelPath = candidatePaths.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException(
                "Gemma model not found. Place $MODEL_FILENAME in:\n" +
                        candidatePaths.joinToString("\n") +
                        "\n\nDownload from:\n" +
                        "  Kaggle:       kaggle.com/models/google/gemma-3\n" +
                        "  Hugging Face: huggingface.co/google/gemma-3-1b-it-litert"
            )

        Log.i(TAG, "Loading model: $modelPath")

        val options = buildOptions(modelPath)
        inference = LlmInference.createFromOptions(context, options)
        isReady = true
        Log.i(TAG, "LiteRT-LM ready: $modelPath")
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val llm = checkNotNull(inference) { "LiteRTEngine not prepared" }

            val corrected = llm.generateResponse(PromptBuilder.build(request)).trim()
            val nouns = runCatching { extractNouns(llm, corrected) }.getOrDefault(emptyList())

            // Per-event memory release
            System.gc()

            TranscriptionResult(
                text = corrected,
                detectedNouns = nouns,
                engineUsed = EngineType.LITERT_GEMMA
            )
        }

    override fun release() {
        inference?.close()
        inference = null
        isReady = false
        System.gc()
        Log.d(TAG, "LiteRTEngine released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildOptions(modelPath: String): LlmInferenceOptions {
        val base = LlmInferenceOptions.builder()
            .modelPath(modelPath)
            .maxTokens(512)
            .topK(1)
            .temperature(0.1f)

        // Attempt NNAPI (NPU) delegate; fall back gracefully if the alpha API
        // doesn't yet expose this method.
        return runCatching {
            base.preferNnApi(true).build()
        }.getOrElse {
            Log.d(TAG, "NNAPI option unavailable in this litert-lm release; using default")
            base.build()
        }
    }

    private fun extractNouns(llm: LlmInference, text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val raw = llm.generateResponse(PromptBuilder.buildNounExtraction(text)).trim()
        return parseJsonArray(raw)
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
}
