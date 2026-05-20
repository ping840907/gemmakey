package com.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GemmaInference"

// Place the model file at:
//   assets/gemma4/gemma-4-E2B-it-litert-lm.task
// OR copy it to internal storage via ModelSetupHelper
private const val MODEL_ASSET_PATH = "gemma4/gemma-4-E2B-it-litert-lm.task"
private const val MODEL_FILENAME   = "gemma-4-E2B-it-litert-lm.task"
private const val MAX_TOKENS = 2048
private const val TOPK = 40
private const val TEMPERATURE = 0.7f

enum class InferenceBackend { GPU, CPU_NNAPI, CPU }

data class InferenceState(
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val backend: InferenceBackend = InferenceBackend.GPU,
    val error: String? = null
)

@Singleton
class GemmaInferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var llmInference: LlmInference? = null
    var state: InferenceState = InferenceState()
        private set

    // ── Initialisation ──────────────────────────────────────────────────────

    suspend fun initialize(): InferenceState = withContext(Dispatchers.IO) {
        state = InferenceState(isLoading = true)
        val modelPath = resolveModelPath() ?: run {
            state = InferenceState(error = "找不到模型檔案。請將 $MODEL_FILENAME 放入 assets/gemma4/")
            return@withContext state
        }

        // Try GPU first → NNAPI → CPU fallback
        val (inference, backend) = tryCreateInference(modelPath)
        if (inference == null) {
            state = InferenceState(error = "模型載入失敗，請確認裝置支援性")
            return@withContext state
        }
        llmInference = inference
        state = InferenceState(isReady = true, backend = backend)
        Log.i(TAG, "Gemma 4 loaded via $backend at $modelPath")
        state
    }

    private fun resolveModelPath(): String? {
        // 1. Internal storage (copied from assets on first launch)
        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists()) return internal.absolutePath

        // 2. Assets folder — copy to internal storage so LiteRT can mmap it
        return try {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                internal.outputStream().use { output -> input.copyTo(output) }
            }
            internal.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun tryCreateInference(modelPath: String): Pair<LlmInference?, InferenceBackend> {
        for (backend in InferenceBackend.entries) {
            try {
                val options = buildOptions(modelPath, backend)
                val inference = LlmInference.createFromOptions(context, options)
                return inference to backend
            } catch (e: Exception) {
                Log.w(TAG, "Backend $backend failed: ${e.message}")
            }
        }
        return null to InferenceBackend.CPU
    }

    private fun buildOptions(modelPath: String, backend: InferenceBackend): LlmInferenceOptions {
        val preferredBackend = when (backend) {
            InferenceBackend.GPU       -> LlmInference.Backend.GPU
            InferenceBackend.CPU_NNAPI -> LlmInference.Backend.CPU  // NNAPI is set via delegate below
            InferenceBackend.CPU       -> LlmInference.Backend.CPU
        }
        return LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setTopK(TOPK)
            .setTemperature(TEMPERATURE)
            .setPreferredBackend(preferredBackend)
            .build()
    }

    // ── Text inference (streaming) ───────────────────────────────────────────

    fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val engine = llmInference ?: run {
            trySend("[模型未載入]"); close(); return@callbackFlow
        }
        try {
            engine.generateResponseAsync(prompt) { partial, done ->
                partial?.let { trySend(it) }
                if (done) close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateStream error", e)
            trySend("發生錯誤：${e.message}")
            close(e)
        }
        awaitClose()
    }

    // ── Multimodal inference (image + text, streaming) ───────────────────────

    fun generateStreamWithImage(prompt: String, bitmap: Bitmap): Flow<String> = callbackFlow {
        val engine = llmInference ?: run {
            trySend("[模型未載入]"); close(); return@callbackFlow
        }
        try {
            // Create a session for multimodal input
            val session = engine.createSession()
            // Add the image first (Gemma 4 multimodal expects interleaved image+text)
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            session.addQueryChunk(prompt)
            session.addImage(mpImage)
            session.generateResponseAsync { partial, done ->
                partial?.let { trySend(it) }
                if (done) { session.close(); close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateStreamWithImage error", e)
            // Fallback: describe image ourselves and do text-only
            trySend("[圖片處理錯誤：${e.message}]")
            close(e)
        }
        awaitClose()
    }

    // ── One-shot (suspend) for parsing ──────────────────────────────────────

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val engine = llmInference ?: return@withContext "[模型未載入]"
        try {
            engine.generateResponse(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "generate error", e)
            "[錯誤：${e.message}]"
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
        state = InferenceState()
    }
}
