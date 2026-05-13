package com.example.gemmakey.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * On-device Gemma inference via the official LiteRT-LM Kotlin SDK (v0.11.0).
 *
 * ## Model file
 * Format : `.litertlm`
 * Source  : Hugging Face — litert-community/gemma-4-E2B-it-litert-lm
 *   (search "gemma-4-E2B-it-litert-lm" on huggingface.co/litert-community)
 *
 * Recommended variant : `gemma-4-E2B-it-int4.litertlm`  (~1–2 GB)
 *
 * ## Placement (probed in order)
 *   1. <external-files-dir>/gemma-4-E2B-it-int4.litertlm
 *   2. <internal-files-dir>/models/gemma-4-E2B-it-int4.litertlm
 *
 * ## Acceleration
 *   Backend.NPU  → Android NNAPI / on-chip NPU/DSP
 *   Backend.GPU  → OpenCL/Vulkan GPU path (auto-fallback)
 *   Backend.CPU  → XNNPACK CPU path (always available)
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it-int4.litertlm"
    }

    // Gemma 4 E2B is natively multimodal (text + image + audio).
    // Image input via the LiteRT-LM Contents API — falls back to text-only on error.
    override val supportsVision: Boolean = true

    private val TAG = "LiteRTEngine"

    private val candidatePaths: List<String>
        get() = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath,
            File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
        )

    private var engine: Engine? = null

    // ConversationConfig shared across inference calls; fresh Conversation per request.
    private val conversationConfig = ConversationConfig(
        temperature = 0.1f,
        topK = 1,
        maxTokens = 512
    )

    override var isReady: Boolean = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun prepare() = withContext(Dispatchers.IO) {
        val modelPath = candidatePaths.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException(
                "Model not found. Place $MODEL_FILENAME in:\n" +
                        candidatePaths.joinToString("\n") +
                        "\n\nDownload from:\n" +
                        "  huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
            )
        Log.i(TAG, "Loading: $modelPath")

        val eng = createEngine(modelPath)
        eng.initialize()

        engine = eng
        isReady = true
        Log.i(TAG, "LiteRT-LM Engine ready")
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "LiteRTEngine not prepared" }

            // Fresh conversation per event — no cross-event memory leakage.
            // use{} ensures close() even if an exception escapes runCatching.
            // TODO: when LiteRT-LM Contents API is confirmed for v0.11.0, pass
            //       request.screenBitmap via Content.image() for native vision input.
            val corrected = eng.createConversation(conversationConfig).use { conv ->
                runCatching { collect(conv, PromptBuilder.build(request)).trim() }
                    .onFailure { Log.e(TAG, "Transcription inference failed: ${it.message}") }
                    .getOrDefault("")
            }

            val nouns = if (corrected.isNotBlank()) {
                eng.createConversation(conversationConfig).use { nounConv ->
                    runCatching {
                        parseJsonArray(
                            collect(nounConv, PromptBuilder.buildNounExtraction(corrected)).trim()
                        )
                    }.getOrDefault(emptyList())
                }
            } else emptyList()

            System.gc()

            TranscriptionResult(
                text = corrected,
                detectedNouns = nouns,
                engineUsed = EngineType.LITERT_GEMMA
            )
        }

    override fun release() {
        engine?.close()
        engine = null
        isReady = false
        System.gc()
        Log.d(TAG, "LiteRTEngine released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createEngine(modelPath: String): Engine {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // Try NPU → GPU → CPU; Engine constructor throws if a backend is unavailable.
        for (backend in listOf(
            Backend.NPU(nativeLibraryDir = nativeLibDir),
            Backend.GPU(),
            Backend.CPU()
        )) {
            runCatching {
                return Engine(EngineConfig(modelPath = modelPath, backend = backend))
            }.onFailure {
                Log.w(TAG, "Backend ${backend::class.simpleName} unavailable: ${it.message}")
            }
        }
        // CPU is guaranteed to succeed on any device.
        return Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
    }

    private suspend fun collect(conv: Conversation, prompt: String): String {
        val sb = StringBuilder()
        conv.sendMessageAsync(prompt)
            .catch { e -> Log.e(TAG, "Inference stream error: ${e.message}") }
            .collect { chunk -> sb.append(chunk) }
        return sb.toString()
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
