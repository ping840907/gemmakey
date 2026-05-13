package com.example.gemmakey

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.example.gemmakey.ai.AIEngine
import com.example.gemmakey.ai.AIEngineFactory
import com.example.gemmakey.ai.TranscriptionRequest
import com.example.gemmakey.dict.CustomDictionary
import com.example.gemmakey.screen.ScreenContextProvider
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Main Input Method Service.
 *
 * Flow for each voice-input event:
 *   1. User presses the mic button → SpeechRecognizer begins listening immediately.
 *   2. User speaks.
 *   3. User releases the mic button → stopListening() signals end-of-speech.
 *   4. SpeechRecognizer delivers raw text via onResults().
 *   5. [GemmaAccessibilityService] provides screen-text context.
 *   6. [AIEngine] (Gemini Nano or Gemma 4 e2b) refines the transcript.
 *   7. Corrected text is committed to the focused field.
 *   8. Detected nouns are persisted; all runtime buffers are freed.
 */
class GemmaKeyIMEService : InputMethodService(), KeyboardViewManager.KeyboardActionListener {

    private val TAG = "GemmaKeyIME"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var keyboardViewManager: KeyboardViewManager
    private lateinit var dictionary: CustomDictionary
    private var aiEngine: AIEngine? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceJob: Job? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dictionary = CustomDictionary(this)
        initAIEngine()
        Log.i(TAG, "GemmaKey IME created")
    }

    override fun onDestroy() {
        scope.cancel()
        aiEngine?.release()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    // ── View creation ─────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        keyboardViewManager = KeyboardViewManager(this, this)
        return keyboardViewManager.createView(currentInputEditorInfo)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
        keyboardViewManager.clearPreview()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            GemmaAccessibilityService.instance?.requestScreenshot()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardViewManager.clearPreview()
        super.onFinishInputView(finishingInput)
    }

    // ── AI engine initialisation ──────────────────────────────────────────────

    private fun initAIEngine() {
        scope.launch(Dispatchers.IO) {
            try {
                val engine = AIEngineFactory.create(this@GemmaKeyIMEService)
                engine.prepare()
                aiEngine = engine
                withContext(Dispatchers.Main) {
                    if (::keyboardViewManager.isInitialized) {
                        keyboardViewManager.setState(
                            KeyboardViewManager.State.IDLE,
                            getString(R.string.status_ready)
                        )
                    }
                }
                Log.i(TAG, "AI engine ready")
            } catch (e: Exception) {
                Log.e(TAG, "AI engine init failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (::keyboardViewManager.isInitialized) {
                        keyboardViewManager.setState(
                            KeyboardViewManager.State.ERROR,
                            getString(R.string.status_model_missing)
                        )
                    }
                }
            }
        }
    }

    // ── Keyboard action listener ──────────────────────────────────────────────

    override fun onMicDown() {
        if (aiEngine?.isReady != true) {
            keyboardViewManager.setState(
                KeyboardViewManager.State.ERROR,
                getString(R.string.status_model_missing)
            )
            return
        }
        keyboardViewManager.setState(KeyboardViewManager.State.RECORDING)
        // Grab a screenshot for context before speech begins
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            GemmaAccessibilityService.instance?.requestScreenshot()
        }
        // Launch the full pipeline; SpeechRecognizer starts listening immediately.
        voiceJob = scope.launch {
            try {
                processVoiceInput()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Voice processing error: ${e.message}", e)
                keyboardViewManager.setState(
                    KeyboardViewManager.State.ERROR,
                    getString(R.string.status_error)
                )
            }
        }
    }

    override fun onMicUp() {
        // Signals end-of-speech to the recognizer; onResults() resumes the pipeline.
        speechRecognizer?.stopListening()
    }

    override fun onDeletePressed() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun onEnterPressed() {
        currentInputConnection?.performEditorAction(
            currentInputEditorInfo?.actionId ?: EditorInfo.IME_ACTION_DONE
        )
    }

    // ── Core voice-input pipeline ─────────────────────────────────────────────

    private suspend fun processVoiceInput() {
        // 1. Start recognizer immediately — user is already speaking.
        val rawAsr = recognizeSpeech()
        if (rawAsr.isBlank()) {
            keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
            return
        }
        Log.d(TAG, "ASR result: $rawAsr")

        // 2. Gather screen context (collected in background during speech).
        val screenCtx = withContext(Dispatchers.IO) { ScreenContextProvider.capture() }
        val hints = withContext(Dispatchers.IO) { dictionary.getHints() }

        // 3. AI correction.
        keyboardViewManager.setState(
            KeyboardViewManager.State.PROCESSING,
            getString(R.string.status_correcting)
        )
        val engine = aiEngine ?: run {
            keyboardViewManager.setState(
                KeyboardViewManager.State.ERROR,
                getString(R.string.status_model_missing)
            )
            screenCtx.screenshot?.recycle()
            return
        }
        val result = withContext(Dispatchers.IO) {
            engine.transcribe(
                TranscriptionRequest(
                    rawAsr = rawAsr,
                    screenText = screenCtx.text,
                    screenBitmap = screenCtx.screenshot,
                    dictionaryHints = hints
                )
            )
        }
        Log.d(TAG, "Corrected: ${result.text} | nouns: ${result.detectedNouns}")

        // 4. Commit text.
        if (result.text.isNotBlank()) {
            currentInputConnection?.commitText(result.text, 1)
            keyboardViewManager.showPreview(result.text)
        }

        // 5. Persist detected nouns.
        if (result.detectedNouns.isNotEmpty()) {
            withContext(Dispatchers.IO) { dictionary.recordNouns(result.detectedNouns) }
        }

        screenCtx.screenshot?.recycle()
        keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
    }

    // ── Speech recognition ────────────────────────────────────────────────────

    /**
     * Starts [SpeechRecognizer] listening immediately and suspends until the
     * recognizer delivers results.  The caller signals end-of-speech by calling
     * [SpeechRecognizer.stopListening] (done in [onMicUp]).
     *
     * Returns the best-match string, or "" on any error.
     */
    @SuppressLint("MissingPermission")
    private suspend fun recognizeSpeech(): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            speechRecognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(this@GemmaKeyIMEService)
            speechRecognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    // Update UI: recognizer is active and listening
                    keyboardViewManager.setState(KeyboardViewManager.State.RECORDING)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    keyboardViewManager.setState(
                        KeyboardViewManager.State.PROCESSING,
                        getString(R.string.status_recognizing)
                    )
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (cont.isActive) cont.resume(text)
                }
                override fun onPartialResults(partial: Bundle?) {}
                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error: $error")
                    if (cont.isActive) cont.resume("")
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            sr.startListening(intent)

            cont.invokeOnCancellation {
                sr.stopListening()
                sr.destroy()
                speechRecognizer = null
            }
        }
    }
}
