package com.example.gemmakey.ai

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

    /** Prepare the model. Call once before [transcribe]. */
    suspend fun prepare()

    /** Produce a corrected transcription for the given request. */
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult

    /** Release all native/heap resources associated with the engine. */
    fun release()
}

// ── Factory ───────────────────────────────────────────────────────────────────

object AIEngineFactory {

    private const val AICORE_PACKAGE = "com.google.android.aicore"
    private const val TAG = "AIEngineFactory"

    /**
     * Returns the best available engine:
     *   1. Gemini Nano via AICore (Pixel 8+, selected Samsung/ASUS devices)
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
        return try {
            context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
            // Also verify the service is actually reachable
            val mgr = context.getSystemService("android_app_intelligence")
            mgr != null
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}

// ── Shared prompt builder ─────────────────────────────────────────────────────

internal object PromptBuilder {

    /**
     * Constructs the zero-shot correction prompt sent to either engine.
     *
     * The model is instructed to output ONLY the corrected text so callers can
     * commit it directly without further parsing.
     */
    fun build(request: TranscriptionRequest): String {
        val dictSection = if (request.dictionaryHints.isNotEmpty()) {
            "\nKnown specialised terms (use exact spelling if applicable):\n" +
                    request.dictionaryHints.joinToString(", ")
        } else ""

        val screenSection = if (request.screenText.isNotBlank()) {
            "\nCurrent screen text (context only):\n${request.screenText.take(800)}"
        } else ""

        return """
You are a strict transcription correction assistant operating fully offline.

TASK: Given a raw speech-recognition result, output the single best corrected text the user intended to type.

RULES:
- Output ONLY the corrected text — no explanation, no quotes, no preamble.
- Base corrections on context clues from the screen and the known-terms list.
- Fix homophones, filler words, and ASR artefacts.
- Preserve the speaker's language (Chinese, English, mixed) exactly as spoken.
- Do NOT add information not present in the raw ASR.
$dictSection$screenSection

Raw ASR result:
${request.rawAsr}

Corrected text:""".trimIndent()
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
