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

enum class InferenceBackend { NPU, GPU, CPU }

data class InferenceState(
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val backend: InferenceBackend = InferenceBackend.GPU,
    val error: String? = null
)

@Singleton
class GemmaInferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapability: DeviceCapability
) {
    private var engine: Engine? = null
    private var activeBackend: InferenceBackend = InferenceBackend.GPU
    var state: InferenceState = InferenceState()
        private set

    // ── 初始化 ─────────────────────────────────────────────────────────────

    suspend fun initialize(): InferenceState = withContext(Dispatchers.IO) {
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
        Log.i(TAG, "Gemma ready — backend=$backend  tier=${deviceCapability.tier}  model=$modelPath")
        state
    }

    // ── 後端選擇 ──────────────────────────────────────────────────────────────

    private suspend fun tryCreateEngine(modelPath: String): Pair<Engine?, InferenceBackend> {
        // NPU: only attempt on Qualcomm / MediaTek SoCs (API 31 → Build.SOC_MANUFACTURER).
        // LiteRT-LM 0.11.0 calls std::terminate() when QNN / NeuroPilot runtime is absent.
        if (isNpuSocLikely()) {
            runCatching {
                buildEngine(modelPath, Backend.NPU(context.applicationInfo.nativeLibraryDir), Backend.CPU())
            }.onSuccess { return it to InferenceBackend.NPU }
             .onFailure  { Log.w(TAG, "NPU unavailable: ${it.message}") }
        }

        runCatching {
            buildEngine(modelPath, Backend.GPU(), Backend.GPU())
        }.onSuccess { return it to InferenceBackend.GPU }
         .onFailure  { Log.w(TAG, "GPU unavailable: ${it.message}") }

        runCatching {
            buildEngine(modelPath, Backend.CPU(), Backend.CPU())
        }.onSuccess { return it to InferenceBackend.CPU }
         .onFailure  { Log.e(TAG, "CPU also failed: ${it.message}") }

        return null to InferenceBackend.CPU
    }

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

    // ── 對話建立 ──────────────────────────────────────────────────────────────

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

    // ── 文字推論（串流）────────────────────────────────────────────────────────
    //
    // @Volatile active flag: LiteRT-LM does not expose a cancel API for
    // sendMessageAsync. When the Flow is collected and then cancelled (e.g. the
    // user navigates away), awaitClose fires and sets active=false. Subsequent
    // MessageCallback invocations then become no-ops instead of writing into the
    // closed channel (trySend on a closed channel returns false but wastes CPU).

    fun generateStream(
        prompt: String,
        conversation: Conversation? = null
    ): Flow<String> = callbackFlow {
        val conv = conversation ?: runCatching { createConversation() }.getOrNull()
            ?: run { trySend("[模型未初始化]"); close(); return@callbackFlow }

        val contents = Contents.of(listOf(Content.Text(prompt)))
        @Volatile var active = true

        conv.sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (active) trySend(message.toString())
                }
                override fun onDone() {
                    if (active) close()
                }
                override fun onError(throwable: Throwable) {
                    if (!active) return
                    Log.e(TAG, "MessageCallback.onError", throwable)
                    trySend("❌ 推論錯誤：${throwable.message}")
                    close(throwable)
                }
            },
            emptyMap()
        )
        awaitClose { active = false }
    }

    // ── 多模態推論（圖片 + 文字）──────────────────────────────────────────────
    //
    // Gemma 4 E2B/E4B 及 Gemma 3n 均支援視覺輸入（visionBackend 已設）。
    // LiteRT-LM 0.11.0 已知 bug (Issue #1874)：Gemma 4 需要 prompt template 中
    // 包含 image placeholder，但 ConversationConfig 未暴露此 C++ 介面。
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

        // Heap guard: PNG encoding + native KV-cache allocation typically requires
        // 4–5× the raw bitmap size. Reject early with a user-readable message
        // rather than crashing deep in native code where Throwable cannot be caught.
        val estimatedMb = (bitmap.byteCount.toLong() * 5) / 1_048_576L
        if (!deviceCapability.hasHeapFor(estimatedMb.toInt().coerceAtLeast(30))) {
            Log.w(TAG, "Heap guard fired: bitmap=${bitmap.byteCount / 1024}KB  estimated=${estimatedMb}MB")
            trySend("⚠️ 裝置可用記憶體不足，無法處理圖片。請清除對話後再試，或改用較小的圖片。")
            close()
            return@callbackFlow
        }

        @Volatile var active = true

        try {
            val imageContent = Content.ImageBytes(bitmap.toPngByteArray())
            val contents = Contents.of(listOf(imageContent, Content.Text(prompt)))

            conv.sendMessageAsync(
                contents,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (active) trySend(message.toString())
                    }
                    override fun onDone() { if (active) close() }
                    override fun onError(throwable: Throwable) {
                        if (!active) return
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
            if (active) {
                Log.e(TAG, "generateStreamWithImage setup error", e)
                trySend(if (e is OutOfMemoryError) "❌ 圖片過大，記憶體不足" else "❌ 圖片推論錯誤：${e.message}")
                close()
            }
        }
        awaitClose { active = false }
    }

    // ── 單次完整回應 ──────────────────────────────────────────────────────────

    suspend fun generate(prompt: String, conversation: Conversation? = null): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            generateStream(prompt, conversation).collect { sb.append(it) }
            sb.toString().trim()
        }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun Bitmap.toPngByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun resolveModelPath(): String? {
        val external = context.getExternalFilesDir(null)?.let { File(it, MODEL_FILENAME) }
        if (external != null && external.exists() && external.length() > 0)
            return external.absolutePath

        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists() && internal.length() > 0) return internal.absolutePath

        val devPath = File("/data/local/tmp", MODEL_FILENAME)
        if (devPath.exists() && devPath.length() > 0) return devPath.absolutePath

        return null
    }

    fun isModelInstalled(): Boolean = resolveModelPath() != null

    fun close() {
        engine?.close()
        engine = null
        state = InferenceState()
    }
}
