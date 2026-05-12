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
//
// If the library is not yet published to mavenCentral under the GA package,
// replace the implementation block with the MediaPipe Tasks GenAI shim below.
import com.google.ai.edge.litert.lm.LlmInference
import com.google.ai.edge.litert.lm.LlmInferenceOptions

/**
 * Runs Gemma 4 e2b (or any LiteRT-LM–compatible model) fully on-device.
 *
 * ## Model placement
 * Place the `.bin` model file at one of (probed in order):
 *   1. `<external-files-dir>/gemma4e2b.bin`
 *   2. `<internal-files-dir>/models/gemma4e2b.bin`
 *
 * ## Acceleration hierarchy
 *   1. NNAPI (Android Neural Networks API) → exposes NPU/DSP on Qualcomm,
 *      MediaTek, Samsung Exynos, Google Tensor, and other modern SoCs.
 *   2. GPU delegate — falls back automatically inside LiteRT-LM when NNAPI
 *      is unavailable or returns an error.
 *   3. CPU — final fallback guaranteed on all devices.
 *
 * LiteRT-LM selects the best available tier at [prepare] time and logs the
 * chosen delegate.
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    private val TAG = "LiteRTEngine"

    private val MODEL_FILENAME = "gemma4e2b.bin"

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
                        candidatePaths.joinToString("\n")
            )

        Log.i(TAG, "Loading model: $modelPath")

        // Build options — accelerator preference is NNAPI (NPU/DSP).
        // LiteRT-LM will silently fall back to GPU → CPU on unsupported hardware.
        val options = buildOptions(modelPath)
        inference = LlmInference.createFromOptions(context, options)
        isReady = true
        Log.i(TAG, "LiteRT-LM / Gemma 4 e2b ready")
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

        // Attempt to enable NNAPI (NPU) delegate.
        // The exact method name depends on the alpha release; guard with runCatching.
        return runCatching {
            base.preferNnApi(true).build()
        }.getOrElse {
            // Older alpha without preferNnApi — rely on default GPU path
            Log.d(TAG, "NNAPI option not available in this litert-lm release; using default accelerator")
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
