package com.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG            = "GemmaInference"
private const val MODEL_FILENAME = "model.litertlm"
private const val MAX_TOKENS     = 8192
private const val TOP_K          = 40
private const val TOP_P          = 0.95
private const val TEMPERATURE    = 0.7f

// ── 後端優先序：NPU → GPU → CPU（對照 Gallery LlmChatModelHelper.kt） ────────
enum class InferenceBackend { NPU, GPU, CPU }

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
    private var engine: Engine? = null
    private var activeBackend: InferenceBackend = InferenceBackend.GPU
    var state: InferenceState = InferenceState()
        private set

    // ── 初始化 ─────────────────────────────────────────────────────────────

    suspend fun initialize(): InferenceState = withContext(Dispatchers.IO) {
        // Close any existing engine before reinitializing to prevent resource leaks
        engine?.close()
        engine = null
        state = InferenceState(isLoading = true)
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val modelPath = resolveModelPath() ?: run {
            state = InferenceState(
                error = "找不到模型檔案。請依照 MODEL_SETUP.md 下載並放置 $MODEL_FILENAME"
            )
            return@withContext state
        }

        val (eng, backend) = tryCreateEngine(modelPath)
        if (eng == null) {
            state = InferenceState(error = "模型初始化失敗，請確認裝置相容性與記憶體空間")
            return@withContext state
        }
        engine = eng
        activeBackend = backend
        state = InferenceState(isReady = true, backend = backend)
        Log.i(TAG, "Gemma 4 ready — backend=$backend, model=$modelPath")
        state
    }

    // ── 後端選擇（對照 Gallery Accelerator enum：CPU / GPU / NPU）────────────

    private suspend fun tryCreateEngine(modelPath: String): Pair<Engine?, InferenceBackend> {
        // NPU: only attempt on Qualcomm / MediaTek SoCs (API 31 → Build.SOC_MANUFACTURER).
        // LiteRT-LM 0.11.0 calls std::terminate() (bad_variant_access in -fno-exceptions)
        // when the QNN / NeuroPilot runtime is absent — a native SIGABRT that Kotlin's
        // runCatching cannot intercept. Skipping on other SoCs prevents the crash.
        if (isNpuSocLikely()) {
            runCatching {
                buildEngine(modelPath, Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.CPU())
            }.onSuccess { return it to InferenceBackend.NPU }
             .onFailure  { Log.w(TAG, "NPU unavailable: ${it.message}") }
        }

        // GPU — visionBackend must match (Gemma 4 is multimodal; omitting visionBackend
        // causes GPU engine init to fail even when only text inference is used).
        runCatching {
            buildEngine(modelPath, Backend.GPU(), Backend.GPU())
        }.onSuccess { return it to InferenceBackend.GPU }
         .onFailure  { Log.w(TAG, "GPU unavailable: ${it.message}") }

        // CPU（XNNPACK）
        runCatching {
            buildEngine(modelPath, Backend.CPU(), Backend.CPU())
        }.onSuccess { return it to InferenceBackend.CPU }
         .onFailure  { Log.e(TAG, "CPU also failed: ${it.message}") }

        return null to InferenceBackend.CPU
    }

    /** True on Qualcomm and MediaTek SoCs, which ship QNN / NeuroPilot NPU runtimes. */
    private fun isNpuSocLikely(): Boolean {
        val soc = Build.SOC_MANUFACTURER.lowercase()
        return soc.contains("qualcomm") || soc.contains("mediatek")
    }

    private fun buildEngine(modelPath: String, backend: Backend, visionBackend: Backend): Engine {
        val cfg = EngineConfig(
            modelPath     = modelPath,
            backend       = backend,
            visionBackend = visionBackend,
            maxNumTokens  = MAX_TOKENS,
            cacheDir      = if (modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath else null
        )
        return Engine(cfg).also { it.initialize() }
    }

    // ── 對話建立（含 systemInstruction、tools）────────────────────────────────

    /**
     * 建立 Conversation，對照 Gallery ConversationConfig：
     * - NPU/TPU 不傳 SamplerConfig（硬體有自己的取樣策略）
     * - GPU/CPU 傳完整 SamplerConfig
     */
    fun createConversation(
        systemInstruction: String? = null,
        tools: List<ToolProvider> = emptyList()
    ): Conversation {
        val eng = engine ?: error("Engine 未初始化")
        val samplerCfg = if (activeBackend == InferenceBackend.NPU) null
            else SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE.toDouble())

        val sysContents = systemInstruction?.let { Contents.of(listOf(Content.Text(it))) }

        return eng.createConversation(
            ConversationConfig(
                samplerConfig     = samplerCfg,
                systemInstruction = sysContents,
                tools             = tools
            )
        )
    }

    // ── 文字推論（串流，Flow 包裝 MessageCallback）────────────────────────────

    fun generateStream(
        prompt: String,
        conversation: Conversation? = null
    ): Flow<String> = callbackFlow {
        val conv = conversation ?: runCatching { createConversation() }.getOrNull()
            ?: run { trySend("[模型未初始化]"); close(); return@callbackFlow }

        val contents = Contents.of(listOf(Content.Text(prompt)))

        // 對照 Gallery sendMessageAsync(Contents, MessageCallback, extraContext)
        conv.sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message.toString())
                }
                override fun onDone() { close() }
                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "MessageCallback.onError", throwable)
                    trySend("❌ 推論錯誤：${throwable.message}")
                    close(throwable)
                }
            },
            emptyMap()
        )
        awaitClose()
    }

    // ── 多模態推論（圖片 + 文字）──────────────────────────────────────────────
    //
    // Gemma 4 E2B/E4B 及 Gemma 3n 均支援視覺輸入（visionBackend 已設）。
    // LiteRT-LM 0.11.0 Kotlin API 已知 bug (Issue #1874)：Gemma 4 需要 prompt
    // template 中包含 image placeholder，但 ConversationConfig 未暴露此 C++ 介面。
    // 若遇到 "INVALID_ARGUMENT: Provided more images than expected"，表示該模型
    // 受此 bug 影響；錯誤由 onError callback 上報，不會造成 native crash。
    // Gemma 3n 不受影響，可正常使用多模態推論。

    fun generateStreamWithImage(
        prompt: String,
        bitmap: Bitmap,
        conversation: Conversation? = null
    ): Flow<String> = callbackFlow {
        val conv = conversation ?: runCatching { createConversation() }.getOrNull()
            ?: run { trySend("[模型未初始化]"); close(); return@callbackFlow }

        try {
            // LiteRT-LM 規範：圖片 content 必須排在文字 content 之前
            val imageContent = Content.ImageBytes(bitmap.toPngByteArray())
            val contents = Contents.of(listOf(imageContent, Content.Text(prompt)))

            conv.sendMessageAsync(
                contents,
                object : MessageCallback {
                    override fun onMessage(message: Message) { trySend(message.toString()) }
                    override fun onDone() { close() }
                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "Multimodal error", throwable)
                        val msg = if (throwable.message?.contains("more images than expected") == true)
                            "⚠️ 此 Gemma 模型版本不支援圖片輸入（LiteRT-LM Issue #1874）。請使用 Gemma 3n 或切換至 Gemini API。"
                        else
                            "❌ 圖片推論錯誤：${throwable.message}"
                        trySend(msg)
                        close()
                    }
                },
                emptyMap()
            )
        } catch (e: Throwable) {
            Log.e(TAG, "generateStreamWithImage setup error", e)
            trySend(if (e is OutOfMemoryError) "❌ 圖片過大，記憶體不足" else "❌ 圖片推論錯誤：${e.message}")
            close()
        }
        awaitClose()
    }

    // ── 單次完整回應 ──────────────────────────────────────────────────────────

    suspend fun generate(prompt: String, conversation: Conversation? = null): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            generateStream(prompt, conversation).collect { sb.append(it) }
            sb.toString().trim()
        }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** Bitmap → PNG byte array（對照 Gallery 的 toPngByteArray extension） */
    private fun Bitmap.toPngByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // Model resolution order (never from assets — 3 GB files crash compressDebugAssets):
    //   1. App-specific external storage: Android/data/com.gemmakey/files/model.litertlm
    //      → adb push model.litertlm /sdcard/Android/data/com.gemmakey/files/
    //   2. Internal app storage (persists across reboots, no permission needed)
    //   3. Developer adb scratch path: /data/local/tmp/model.litertlm
    private fun resolveModelPath(): String? {
        // External app-specific dir (no READ_EXTERNAL_STORAGE permission needed on API 19+)
        val external = context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) }
        if (external != null && external.exists() && external.length() > 0)
            return external.absolutePath

        // Internal storage (already copied on a previous launch)
        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists() && internal.length() > 0) return internal.absolutePath

        // Developer convenience path (adb push to /data/local/tmp/)
        val devPath = File("/data/local/tmp", MODEL_FILENAME)
        if (devPath.exists() && devPath.length() > 0) return devPath.absolutePath

        return null
    }

    /** Returns true if the model file is present in any expected location, without loading it. */
    fun isModelInstalled(): Boolean = resolveModelPath() != null

    fun close() {
        engine?.close()
        engine = null
        state = InferenceState()
    }
}
