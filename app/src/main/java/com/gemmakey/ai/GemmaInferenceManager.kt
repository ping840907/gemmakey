package com.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GemmaInference"

// 模型檔案：放在 assets/gemma4/ 或裝置內部儲存
// 從 HuggingFace litert-community/gemma-4-E2B-it-litert-lm 下載 .litertlm 格式
private const val MODEL_ASSET_PATH = "gemma4/model.litertlm"
private const val MODEL_FILENAME   = "model.litertlm"

// ── 後端優先序 ────────────────────────────────────────────────────────────────
// NPU  (Qualcomm QNN / MediaTek NeuroPilot) → GPU (OpenCL/Vulkan) → CPU (XNNPACK)
// NNAPI 已於 Android 15 廢棄，LiteRT-LM 直接對接晶片廠 NPU 插件取代之
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
    var state: InferenceState = InferenceState()
        private set

    // ── 初始化 ────────────────────────────────────────────────────────────────

    suspend fun initialize(): InferenceState = withContext(Dispatchers.IO) {
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
        state = InferenceState(isReady = true, backend = backend)
        Log.i(TAG, "Gemma 4 ready — backend=$backend, model=$modelPath")
        state
    }

    // ── 後端選擇：NPU → GPU → CPU ─────────────────────────────────────────────

    private suspend fun tryCreateEngine(modelPath: String): Pair<Engine?, InferenceBackend> {
        // 1. NPU（Qualcomm Snapdragon / MediaTek Dimensity）
        //    LiteRT-LM 透過 nativeLibraryDir 自動尋找晶片廠的加速器插件
        runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.NPU(
                        nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                    )
                )
            ).also { it.initialize() }
        }.onSuccess { return it to InferenceBackend.NPU }
         .onFailure  { Log.w(TAG, "NPU backend unavailable: ${it.message}") }

        // 2. GPU（OpenCL / Vulkan，覆蓋約 90% Android 裝置）
        runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU()
                )
            ).also { it.initialize() }
        }.onSuccess { return it to InferenceBackend.GPU }
         .onFailure  { Log.w(TAG, "GPU backend unavailable: ${it.message}") }

        // 3. CPU（XNNPACK，最終 fallback）
        runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU()
                )
            ).also { it.initialize() }
        }.onSuccess { return it to InferenceBackend.CPU }
         .onFailure  { Log.e(TAG, "CPU backend also failed: ${it.message}") }

        return null to InferenceBackend.CPU
    }

    // ── 模型路徑解析 ──────────────────────────────────────────────────────────

    private fun resolveModelPath(): String? {
        // 優先使用已複製到內部儲存的版本（避免每次啟動都從 assets 複製）
        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists() && internal.length() > 0) return internal.absolutePath

        // 從 assets 複製（首次安裝）
        return runCatching {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                internal.outputStream().use { output -> input.copyTo(output) }
            }
            internal.absolutePath
        }.getOrNull()
    }

    // ── 文字推論（串流）────────────────────────────────────────────────────────

    fun generateStream(prompt: String): Flow<String> = flow {
        val eng = engine ?: run { emit("[模型未載入]"); return@flow }
        runCatching {
            eng.createConversation().use { conv ->
                conv.sendMessageAsync(listOf(Content.Text(prompt))).collect { emit(it) }
            }
        }.onFailure {
            Log.e(TAG, "generateStream error", it)
            emit("❌ 推論錯誤：${it.message}")
        }
    }

    // ── 多模態推論（圖片 + 文字，串流）──────────────────────────────────────────

    fun generateStreamWithImage(prompt: String, bitmap: Bitmap): Flow<String> = flow {
        val eng = engine ?: run { emit("[模型未載入]"); return@flow }

        // LiteRT-LM 多模態 API：先將 Bitmap 存為暫存檔，以 Content.ImageFile 傳入
        val imageFile = saveBitmapToTemp(bitmap)
        runCatching {
            eng.createConversation().use { conv ->
                val contents = if (imageFile != null) {
                    listOf(
                        Content.ImageFile(imageFile.absolutePath),
                        Content.Text(prompt)
                    )
                } else {
                    // 若圖片暫存失敗，退化為純文字
                    Log.w(TAG, "Image temp file failed, falling back to text-only")
                    listOf(Content.Text(prompt))
                }
                conv.sendMessageAsync(contents).collect { emit(it) }
            }
        }.onFailure {
            Log.e(TAG, "generateStreamWithImage error", it)
            emit("❌ 圖片推論錯誤：${it.message}")
        }.also {
            imageFile?.delete()
        }
    }

    // ── 單次完整回應（供解析使用）────────────────────────────────────────────

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext "[模型未載入]"
        val sb = StringBuilder()
        runCatching {
            eng.createConversation().use { conv ->
                conv.sendMessageAsync(listOf(Content.Text(prompt))).collect { sb.append(it) }
            }
        }.onFailure { Log.e(TAG, "generate error", it) }
        sb.toString().trim()
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun saveBitmapToTemp(bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, "llm_images").also { it.mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        file
    }.getOrNull()

    fun close() {
        engine?.close()
        engine = null
        state = InferenceState()
    }
}
