package com.example.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device Gemma inference via the official LiteRT-LM Kotlin SDK (v0.11.0).
 *
 * ## Model file
 * Format : `.litertlm`
 * Source  : Hugging Face — litert-community/gemma-4-E2B-it-litert-lm
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
 *
 * ## Multimodal support
 *   Both image and audio APIs are probed via reflection at runtime.
 *   When the SDK exposes the expected overloads they activate automatically;
 *   otherwise the engine falls back to text-only without any code change.
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it-int4.litertlm"
    }

    // Gemma 4 E2B is natively multimodal (text + image + audio).
    override val supportsVision: Boolean = true

    // Cached once in prepare() — reflection is expensive; result never changes for
    // a given SDK version at runtime.
    private var cachedVisionMethod: java.lang.reflect.Method? = null
    private var cachedAudioMethod: java.lang.reflect.Method? = null
    private var nativeAudioCached = false

    override val supportsNativeAudio: Boolean get() = nativeAudioCached

    private val TAG = "LiteRTEngine"

    private val candidatePaths: List<String>
        get() = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath,
            File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
        )

    private var engine: Engine? = null

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
        // Cache reflection results once — avoids per-call method scanning.
        cachedVisionMethod = findVisionMethod()
        cachedAudioMethod = findAudioMethod()
        nativeAudioCached = cachedAudioMethod != null
        isReady = true
        Log.i(TAG, "LiteRT-LM Engine ready | vision=${supportsVision} | nativeAudio=$nativeAudioCached")
    }

    // ── Inference — text (+ optional vision probe) ────────────────────────────

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "LiteRTEngine not prepared" }

            val corrected = eng.createConversation(conversationConfig).use { conv ->
                runCatching {
                    collectMaybeVision(conv, PromptBuilder.build(request), request.screenBitmap).trim()
                }.onFailure { Log.e(TAG, "Transcription inference failed: ${it.message}") }
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

    // ── Inference — native audio ──────────────────────────────────────────────

    override suspend fun transcribeAudio(
        pcm: ShortArray,
        screenText: String,
        screenBitmap: Bitmap?,
        dictionaryHints: List<String>
    ): TranscriptionResult? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val method = cachedAudioMethod ?: return@withContext null

        val contextPrompt = PromptBuilder.buildAudioContext(screenText, screenBitmap, dictionaryHints)
        val pcmArg: Any = if (method.parameterTypes[0] == ByteArray::class.java) shortsToBytes(pcm) else pcm

        val corrected = runCatching {
            eng.createConversation(conversationConfig).use { conv ->
                val sb = StringBuilder()
                val params = if (method.parameterCount >= 2) arrayOf(pcmArg, contextPrompt) else arrayOf(pcmArg)
                @Suppress("UNCHECKED_CAST")
                val flow = method.invoke(conv, *params) as Flow<String>
                flow.catch { e -> Log.e(TAG, "Audio stream error: ${e.message}") }
                    .collect { sb.append(it) }
                Log.i(TAG, "Native audio API active")
                sb.toString().trim()
            }
        }.onFailure { Log.e(TAG, "Native audio transcription failed: ${it.message}") }
         .getOrDefault("")

        if (corrected.isBlank()) return@withContext null

        val nouns = runCatching {
            eng.createConversation(conversationConfig).use { conv ->
                parseJsonArray(collect(conv, PromptBuilder.buildNounExtraction(corrected)).trim())
            }
        }.getOrDefault(emptyList())

        System.gc()
        TranscriptionResult(text = corrected, detectedNouns = nouns, engineUsed = EngineType.LITERT_GEMMA)
    }

    override fun release() {
        engine?.close()
        engine = null
        cachedVisionMethod = null
        cachedAudioMethod = null
        nativeAudioCached = false
        isReady = false
        System.gc()
        Log.d(TAG, "LiteRTEngine released")
    }

    // ── Reflection probes ─────────────────────────────────────────────────────

    /**
     * Probes for a native image overload on [Conversation.sendMessageAsync].
     * Tries `sendMessageAsync(String, List<Bitmap>)` and `sendMessageAsync(String, Bitmap)`.
     * Returns null when no overload is found — callers fall back to text-only.
     */
    private fun findVisionMethod(): Method? = runCatching {
        Conversation::class.java.methods.firstOrNull { m ->
            m.name == "sendMessageAsync" && m.parameterCount >= 2 &&
            (m.parameterTypes[1].isAssignableFrom(List::class.java) ||
             m.parameterTypes[1] == Bitmap::class.java)
        }
    }.getOrNull()

    /**
     * Probes for a native audio method on [Conversation].
     * Checks common names: sendAudioAsync, transcribeAudio, generateFromAudio.
     * Returns null when none is found.
     */
    private fun findAudioMethod(): Method? = runCatching {
        val names = setOf("sendAudioAsync", "transcribeAudio", "generateFromAudio", "sendAudio")
        Conversation::class.java.methods.firstOrNull { m ->
            m.name in names && m.parameterTypes.isNotEmpty() &&
            (m.parameterTypes[0] == ShortArray::class.java || m.parameterTypes[0] == ByteArray::class.java)
        }
    }.getOrNull()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createEngine(modelPath: String): Engine {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
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
        return Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
    }

    /** Collects a streaming response, injecting the bitmap via the vision probe if available. */
    private suspend fun collectMaybeVision(conv: Conversation, prompt: String, bitmap: Bitmap?): String {
        if (bitmap != null) {
            val method = cachedVisionMethod
            if (method != null) {
                val result = runCatching {
                    val imgArg: Any =
                        if (method.parameterTypes[1].isAssignableFrom(List::class.java)) listOf(bitmap)
                        else bitmap
                    @Suppress("UNCHECKED_CAST")
                    val flow = method.invoke(conv, prompt, imgArg) as Flow<String>
                    val sb = StringBuilder()
                    flow.catch { e -> Log.w(TAG, "Vision stream error: ${e.message}") }
                        .collect { sb.append(it) }
                    Log.i(TAG, "Native vision API active")
                    sb.toString()
                }.onFailure { Log.w(TAG, "Vision probe invocation failed: ${it.message}") }
                 .getOrNull()
                if (result != null) return result
            }
        }
        return collect(conv, prompt)
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

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
    }
}
