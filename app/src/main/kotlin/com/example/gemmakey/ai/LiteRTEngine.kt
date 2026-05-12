package com.example.gemmakey.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * On-device Gemma 4 E2B inference via the official LiteRT-LM Kotlin SDK.
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
 *   Backend.NPU  → Android NNAPI, routes to on-chip NPU/DSP
 *   Backend.GPU  → OpenCL/Vulkan GPU path (auto-fallback)
 *   Backend.CPU  → XNNPACK CPU path (final fallback)
 *
 * The engine tries NPU first; falls back to GPU, then CPU on error.
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    private val TAG = "LiteRTEngine"

    val MODEL_FILENAME = "gemma-4-E2B-it-int4.litertlm"

    private val candidatePaths: List<String>
        get() = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath,
            File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
        )

    private var engine: Engine? = null
    private var conversation: Conversation? = null

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

        // Keep a single reusable conversation; reset between events via new conversation
        conversation = eng.createConversation()
        engine = eng
        isReady = true
        Log.i(TAG, "LiteRT-LM Engine ready")
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "LiteRTEngine not prepared" }

            // Fresh conversation per event — no cross-event memory
            val conv = eng.createConversation()

            val corrected = collect(conv, PromptBuilder.build(request)).trim()
            val nouns = runCatching {
                val nounConv = eng.createConversation()
                val raw = collect(nounConv, PromptBuilder.buildNounExtraction(corrected)).trim()
                nounConv.close()
                parseJsonArray(raw)
            }.getOrDefault(emptyList())

            conv.close()
            System.gc()

            TranscriptionResult(
                text = corrected,
                detectedNouns = nouns,
                engineUsed = EngineType.LITERT_GEMMA
            )
        }

    override fun release() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        isReady = false
        System.gc()
        Log.d(TAG, "LiteRTEngine released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun createEngine(modelPath: String): Engine {
        // Try NPU → GPU → CPU in order
        for (backend in preferredBackends()) {
            runCatching {
                val cfg = EngineConfig(modelPath = modelPath, backend = backend)
                return Engine(cfg)
            }.onFailure {
                Log.w(TAG, "Backend ${backend::class.simpleName} unavailable: ${it.message}")
            }
        }
        // CPU is always available
        return Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
    }

    private fun preferredBackends(): List<Backend> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return listOf(
            Backend.NPU(nativeLibraryDir = nativeLibDir),
            Backend.GPU(),
            Backend.CPU()
        )
    }

    private suspend fun collect(conv: Conversation, prompt: String): String {
        val sb = StringBuilder()
        conv.sendMessageAsync(prompt)
            .catch { e -> Log.e(TAG, "Inference error: ${e.message}") }
            .collect { sb.append(it) }
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
