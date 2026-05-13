package com.example.gemmakey.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * On-device Gemma inference via the official LiteRT-LM Kotlin SDK (v0.11.0).
 *
 * ## Model file
 * Format : `.litertlm`
 * Source  : Hugging Face — litert-community/gemma-4-E2B-it-litert-lm
 * Variant : `gemma-4-E2B-it-int4.litertlm` (~1–2 GB)
 *
 * ## Placement (probed in order)
 *   1. <external-files-dir>/gemma-4-E2B-it-int4.litertlm
 *   2. <internal-files-dir>/models/gemma-4-E2B-it-int4.litertlm
 *
 * ## Acceleration fallback order
 *   NPU (+vision+audio) → NPU (+vision) → NPU → GPU (+vision+audio) → … → CPU
 *
 * ## System instruction
 *   Set on [ConversationConfig.systemInstruction] so every conversation starts
 *   with the rule: audio is primary, context inputs must not appear in output.
 */
class LiteRTEngine(private val context: Context) : AIEngine {

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it-int4.litertlm"
        private const val MAX_NUM_TOKENS = 512
    }

    // Set in prepare() based on which EngineConfig succeeded.
    override var supportsVision: Boolean = false
        private set
    override var supportsNativeAudio: Boolean = false
        private set

    private val TAG = "LiteRTEngine"

    private val candidatePaths: List<String>
        get() = listOf(
            File(context.getExternalFilesDir(null), MODEL_FILENAME).absolutePath,
            File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
        )

    private var engine: Engine? = null

    // System instruction is applied once per conversation via ConversationConfig,
    // not repeated in every user message.
    private val conversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,           // Double, not Float (confirmed from SDK source)
            temperature = 0.1,    // Double, not Float
        ),
        systemInstruction = Contents.of(
            listOf(Content.Text(PromptBuilder.SYSTEM_INSTRUCTION))
        )
    )

    override var isReady: Boolean = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @OptIn(ExperimentalApi::class)
    override suspend fun prepare() = withContext(Dispatchers.IO) {
        val modelPath = candidatePaths.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException(
                "Model not found. Place $MODEL_FILENAME in:\n" +
                        candidatePaths.joinToString("\n") +
                        "\n\nDownload from:\n  huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
            )
        Log.i(TAG, "Loading: $modelPath")

        val result = buildEngine(modelPath)
        result.engine.initialize()

        engine = result.engine
        supportsVision = result.hasVision
        supportsNativeAudio = result.hasAudio
        isReady = true
        Log.i(TAG, "LiteRT-LM Engine ready | vision=${result.hasVision} | nativeAudio=${result.hasAudio}")
    }

    // ── Inference — text + optional vision ───────────────────────────────────

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val eng = checkNotNull(engine) { "LiteRTEngine not prepared" }

            // Use buildMessage() — rules are already in systemInstruction.
            val userPrompt = PromptBuilder.buildMessage(request)
            val contents = buildContents(
                text = userPrompt,
                bitmap = if (supportsVision) request.screenBitmap else null
            )

            val corrected = eng.createConversation(conversationConfig).use { conv ->
                runCatching { sendAndCollect(conv, contents).trim() }
                    .onFailure { Log.e(TAG, "Transcription inference failed: ${it.message}") }
                    .getOrDefault("")
            }

            val nouns = if (corrected.isNotBlank()) {
                eng.createConversation(conversationConfig).use { conv ->
                    runCatching {
                        parseJsonArray(
                            sendAndCollect(conv, buildContents(PromptBuilder.buildNounExtraction(corrected))).trim()
                        )
                    }.getOrDefault(emptyList())
                }
            } else emptyList()

            System.gc()
            TranscriptionResult(text = corrected, detectedNouns = nouns, engineUsed = EngineType.LITERT_GEMMA)
        }

    // ── Inference — native audio ──────────────────────────────────────────────

    override suspend fun transcribeAudio(
        pcm: ShortArray,
        screenText: String,
        screenBitmap: Bitmap?,
        dictionaryHints: List<String>
    ): TranscriptionResult? = withContext(Dispatchers.IO) {
        if (!supportsNativeAudio) return@withContext null
        val eng = engine ?: return@withContext null

        // Context is supplementary; audio bytes are the primary input.
        val contextText = PromptBuilder.buildAudioContext(screenText, screenBitmap, dictionaryHints)
        val contents = buildContents(
            text = contextText,
            bitmap = if (supportsVision) screenBitmap else null,
            pcm = pcm
        )

        val corrected = runCatching {
            eng.createConversation(conversationConfig).use { conv ->
                sendAndCollect(conv, contents).trim()
            }
        }.onFailure { Log.e(TAG, "Native audio transcription failed: ${it.message}") }
         .getOrDefault("")

        if (corrected.isBlank()) return@withContext null

        val nouns = runCatching {
            eng.createConversation(conversationConfig).use { conv ->
                parseJsonArray(
                    sendAndCollect(conv, buildContents(PromptBuilder.buildNounExtraction(corrected))).trim()
                )
            }
        }.getOrDefault(emptyList())

        System.gc()
        TranscriptionResult(text = corrected, detectedNouns = nouns, engineUsed = EngineType.LITERT_GEMMA)
    }

    override fun release() {
        engine?.close()
        engine = null
        supportsVision = false
        supportsNativeAudio = false
        isReady = false
        System.gc()
        Log.d(TAG, "LiteRTEngine released")
    }

    // ── Engine construction ───────────────────────────────────────────────────

    private data class EngineResult(val engine: Engine, val hasVision: Boolean, val hasAudio: Boolean)

    /**
     * Tries NPU→GPU→CPU, each time attempting full multimodal (vision+audio) first,
     * then vision-only, then text-only.  Returns the first configuration that succeeds.
     * Note: [Engine.initialize] must be called separately after construction.
     */
    private fun buildEngine(modelPath: String): EngineResult {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val primaries = listOf(
            Backend.NPU(nativeLibraryDir = nativeLibDir),
            Backend.GPU(),
            Backend.CPU()
        )

        for (primary in primaries) {
            val name = primary::class.simpleName

            // Full multimodal
            runCatching {
                Engine(EngineConfig(
                    modelPath = modelPath, backend = primary,
                    visionBackend = Backend.CPU(), audioBackend = Backend.CPU(),
                    maxNumTokens = MAX_NUM_TOKENS
                ))
            }.onSuccess {
                Log.i(TAG, "$name + vision + audio backend selected")
                return EngineResult(it, hasVision = true, hasAudio = true)
            }.onFailure { Log.w(TAG, "$name + multimodal: ${it.message}") }

            // Vision only
            runCatching {
                Engine(EngineConfig(
                    modelPath = modelPath, backend = primary,
                    visionBackend = Backend.CPU(), audioBackend = null,
                    maxNumTokens = MAX_NUM_TOKENS
                ))
            }.onSuccess {
                Log.i(TAG, "$name + vision backend selected")
                return EngineResult(it, hasVision = true, hasAudio = false)
            }.onFailure { Log.w(TAG, "$name + vision: ${it.message}") }

            // Text only
            runCatching {
                Engine(EngineConfig(
                    modelPath = modelPath, backend = primary,
                    maxNumTokens = MAX_NUM_TOKENS
                ))
            }.onSuccess {
                Log.i(TAG, "$name text-only backend selected")
                return EngineResult(it, hasVision = false, hasAudio = false)
            }.onFailure { Log.w(TAG, "$name text-only: ${it.message}") }
        }

        throw IllegalStateException("No backend available for $modelPath")
    }

    // ── Inference helpers ─────────────────────────────────────────────────────

    /**
     * Sends [contents] to the conversation and suspends until [MessageCallback.onDone]
     * or [MessageCallback.onError] is called.  Cancellation triggers [Conversation.cancelProcess].
     */
    private suspend fun sendAndCollect(conv: Conversation, contents: Contents): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            conv.sendMessageAsync(
                contents,
                object : MessageCallback {
                    override fun onMessage(message: Message) { sb.append(message.toString()) }
                    override fun onDone() { if (cont.isActive) cont.resume(sb.toString()) }
                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "Inference error: ${throwable.message}")
                        if (cont.isActive) cont.resume(sb.toString())
                    }
                },
                emptyMap()
            )
            cont.invokeOnCancellation { runCatching { conv.cancelProcess() } }
        }

    private fun buildContents(text: String, bitmap: Bitmap? = null, pcm: ShortArray? = null): Contents {
        val parts = mutableListOf<Content>()
        // Image and audio before text — model attends to primary input (audio) last,
        // matching the instruction that audio is primary.
        bitmap?.let { parts.add(Content.ImageBytes(it.toPngByteArray())) }
        pcm?.let { parts.add(Content.AudioBytes(shortsToBytes(it))) }
        parts.add(Content.Text(text))
        return Contents.of(parts)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun Bitmap.toPngByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
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
