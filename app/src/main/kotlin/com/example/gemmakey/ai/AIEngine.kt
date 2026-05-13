package com.example.gemmakey.ai

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

// ── Data contracts ─────────────────────────────────────────────────────────────

data class TranscriptionRequest(
    /** Raw text from Android SpeechRecognizer. */
    val rawAsr: String,
    /** Visible text collected by AccessibilityService. */
    val screenText: String,
    /** Low-resolution screenshot (nullable — only for multimodal engines). */
    val screenBitmap: Bitmap? = null,
    /** User's custom dictionary for rare proper-noun hints. */
    val dictionaryHints: List<String> = emptyList()
)

data class TranscriptionResult(
    /** Corrected, output-ready text. */
    val text: String,
    /** Unusual proper nouns / specialist terms detected this turn. */
    val detectedNouns: List<String> = emptyList(),
    /** Which backend actually handled the request. */
    val engineUsed: EngineType = EngineType.UNKNOWN
)

enum class EngineType { AICORE_GEMINI_NANO, LITERT_GEMMA, UNKNOWN }

// ── Engine interface ──────────────────────────────────────────────────────────

interface AIEngine {
    /** True once the model is loaded and ready. */
    val isReady: Boolean

    /** True if the engine accepts image input in [TranscriptionRequest.screenBitmap]. */
    val supportsVision: Boolean

    /** True if the engine can directly transcribe raw 16 kHz 16-bit PCM audio. */
    val supportsNativeAudio: Boolean

    /** Prepare the model. Call once before [transcribe]. */
    suspend fun prepare()

    /** Produce a corrected transcription for the given request. */
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult

    /**
     * Transcribe raw PCM audio (16 kHz, 16-bit, mono) directly without
     * Android SpeechRecognizer.  Only called when [supportsNativeAudio] is true.
     * Returns null if the audio API is unavailable at runtime.
     */
    suspend fun transcribeAudio(
        pcm: ShortArray,
        screenText: String = "",
        screenBitmap: Bitmap? = null,
        dictionaryHints: List<String> = emptyList()
    ): TranscriptionResult?

    /** Release all native/heap resources associated with the engine. */
    fun release()
}

// ── Factory ───────────────────────────────────────────────────────────────────

object AIEngineFactory {

    // Verified package names for AICore on supported hardware:
    //   Pixel 8 / 8 Pro / 9 series (Android 14+): com.google.android.aicore
    //   No public Samsung/ASUS variant package name confirmed as of 2025.
    private val AICORE_PACKAGES = listOf("com.google.android.aicore")
    private const val TAG = "AIEngineFactory"

    /**
     * Returns the best available engine:
     *   1. Gemini Nano via AICore (Pixel 8+)
     *   2. Gemma 4 e2b via LiteRT-LM (universal fallback)
     */
    fun create(context: Context): AIEngine {
        return if (isAICoreAvailable(context)) {
            Log.i(TAG, "AICore detected — using Gemini Nano engine")
            AICoreEngine(context)
        } else {
            Log.i(TAG, "AICore not available — using LiteRT/Gemma engine")
            LiteRTEngine(context)
        }
    }

    fun isAICoreAvailable(context: Context): Boolean {
        val pm = context.packageManager
        val hasPackage = AICORE_PACKAGES.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        }
        if (!hasPackage) return false
        return try {
            // Verify the inference service is actually reachable, not just installed.
            context.getSystemService("android_app_intelligence") != null
        } catch (_: Exception) {
            false
        }
    }
}

// ── Shared prompt builder ─────────────────────────────────────────────────────

internal object PromptBuilder {

    /**
     * Persistent system instruction set on [ConversationConfig.systemInstruction] for
     * engines that support it (LiteRTEngine).  For engines that do not (AICoreEngine),
     * it is prepended to the user message by [build].
     *
     * Rules: audio is primary; screen/image/dictionary are auxiliary context only and
     * must not appear in the output.
     */
    const val SYSTEM_INSTRUCTION = """You are a strict on-device speech transcription assistant.

PRIMARY INPUT: The user's spoken audio. Your sole task is to transcribe and correct it.
AUXILIARY CONTEXT (screen text, screenshot, dictionary hints): Use these ONLY to resolve ambiguous words, homophones, or rare proper nouns. They must NOT appear in your output.

OUTPUT RULES:
- Output ONLY the corrected transcription of what was spoken.
- Do not quote, explain, summarise, or add any text that was not spoken.
- Do not reproduce screen content, image descriptions, app names, or dictionary terms in the output.
- Fix ASR artefacts and filler words.
- Preserve the speaker's language (Chinese, English, code-switched) exactly as spoken."""

    /**
     * Full standalone prompt for engines without [ConversationConfig.systemInstruction]
     * support (e.g. AICoreEngine).  Embeds [SYSTEM_INSTRUCTION] inline.
     */
    fun build(request: TranscriptionRequest): String {
        val dictSection = if (request.dictionaryHints.isNotEmpty()) {
            "\n[Dictionary hints — do not reproduce: ${request.dictionaryHints.joinToString(", ")}]"
        } else ""

        val screenSection = if (request.screenText.isNotBlank()) {
            "\n[Screen text — do not reproduce: ${request.screenText.take(800)}]"
        } else ""

        val visualSection = request.screenBitmap?.let { bmp ->
            "\n[Screen visual: ${describeScreenshot(bmp)} — do not reproduce]"
        } ?: ""

        return """$SYSTEM_INSTRUCTION
$dictSection$screenSection$visualSection

Raw ASR input: ${request.rawAsr}

Corrected transcription:""".trimIndent()
    }

    /**
     * Data-only user message for engines that receive [SYSTEM_INSTRUCTION] via
     * [ConversationConfig.systemInstruction] (LiteRTEngine).
     * Contains only the input data — rules are already set at the conversation level.
     */
    fun buildMessage(request: TranscriptionRequest): String {
        val parts = mutableListOf<String>()
        if (request.dictionaryHints.isNotEmpty())
            parts.add("[Dictionary: ${request.dictionaryHints.joinToString(", ")}]")
        if (request.screenText.isNotBlank())
            parts.add("[Screen text: ${request.screenText.take(800)}]")
        request.screenBitmap?.let {
            parts.add("[Screen visual: ${describeScreenshot(it)}]")
        }
        parts.add("Audio input: ${request.rawAsr}")
        return parts.joinToString("\n")
    }

    /**
     * Samples a 4×4 grid of pixels from the bitmap to derive a compact, token-cheap
     * visual descriptor — theme (dark/light), dominant hue family, and overall brightness.
     * This gives the model signal about the app context (e.g. dark-themed terminal,
     * colourful social feed, document editor) without embedding raw image data.
     */
    private fun describeScreenshot(bmp: Bitmap): String {
        val samples = 4
        val stepX = maxOf(1, bmp.width / samples)
        val stepY = maxOf(1, bmp.height / samples)

        var totalR = 0; var totalG = 0; var totalB = 0; var count = 0
        for (y in 0 until bmp.height step stepY) {
            for (x in 0 until bmp.width step stepX) {
                val pixel = bmp.getPixel(x, y)
                totalR += Color.red(pixel)
                totalG += Color.green(pixel)
                totalB += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return "unknown"

        val avgR = totalR / count
        val avgG = totalG / count
        val avgB = totalB / count
        val brightness = (avgR * 299 + avgG * 587 + avgB * 114) / 1000
        val theme = if (brightness < 128) "dark" else "light"

        val dominantHue = when {
            avgR > avgG && avgR > avgB -> "red-toned"
            avgG > avgR && avgG > avgB -> "green-toned"
            avgB > avgR && avgB > avgG -> "blue-toned"
            else -> "neutral"
        }
        return "$theme theme, $dominantHue, brightness=$brightness/255, size=${bmp.width}×${bmp.height}px"
    }

    /**
     * Supplementary context string sent alongside raw audio tokens when the
     * engine's native audio API is active.  Includes screen text, visual
     * descriptor, and dictionary hints but no ASR text (that is in the audio).
     */
    fun buildAudioContext(screenText: String, bitmap: Bitmap?, hints: List<String>): String {
        val parts = mutableListOf<String>()
        if (screenText.isNotBlank()) parts.add("[Screen text — do not reproduce: ${screenText.take(400)}]")
        bitmap?.let { parts.add("[Screen visual: ${describeScreenshot(it)} — do not reproduce]") }
        if (hints.isNotEmpty()) parts.add("[Dictionary — do not reproduce: ${hints.joinToString(", ")}]")
        return parts.joinToString("\n")
    }

    /**
     * Secondary prompt to extract unusual proper nouns / specialist vocabulary
     * from the already-corrected text for dictionary storage.
     */
    fun buildNounExtraction(correctedText: String): String = """
Extract any unusual proper nouns, brand names, technical terms, or specialised vocabulary from the sentence below.
Output a JSON array of strings only (e.g. ["Term1","Term2"]).
If none, output [].

Sentence: $correctedText

JSON array:""".trimIndent()
}
