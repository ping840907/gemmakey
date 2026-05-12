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
import com.example.gemmakey.ai.TranscriptionResult
import com.example.gemmakey.audio.AudioRecorder
import com.example.gemmakey.dict.CustomDictionary
import com.example.gemmakey.screen.ScreenContextProvider
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Main Input Method Service.
 *
 * Flow for each voice-input event:
 *   1. User presses and holds the mic button.
 *   2. [AudioRecorder] captures PCM audio; [SpeechRecognizer] runs in parallel
 *      (offline preferred) to produce a raw transcript.
 *   3. [GemmaAccessibilityService] provides screen text + optional screenshot.
 *   4. [AIEngine] (Gemini Nano or Gemma 4 e2b) refines the transcript using
 *      screen context and the custom-noun dictionary.
 *   5. Corrected text is committed to the focused field.
 *   6. Detected nouns are persisted; all runtime buffers are explicitly freed.
 */
class GemmaKeyIMEService : InputMethodService(), KeyboardViewManager.KeyboardActionListener {

    private val TAG = "GemmaKeyIME"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var keyboardViewManager: KeyboardViewManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var dictionary: CustomDictionary
    private var aiEngine: AIEngine? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var engineInitJob: Job? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder()
        dictionary = CustomDictionary(this)
        initAIEngine()
        Log.i(TAG, "GemmaKey IME created")
    }

    override fun onDestroy() {
        scope.cancel()
        aiEngine?.release()
        audioRecorder.release()
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
        // Trigger a screenshot at the moment the keyboard opens for context
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
        engineInitJob = scope.launch(Dispatchers.IO) {
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
        audioRecorder.startRecording()
        // Trigger a fresh screenshot just as recording starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            GemmaAccessibilityService.instance?.requestScreenshot()
        }
    }

    override fun onMicUp() {
        if (!audioRecorder.isRecording) return
        keyboardViewManager.setState(KeyboardViewManager.State.PROCESSING)

        scope.launch {
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

    override fun onDeletePressed() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun onEnterPressed() {
        currentInputConnection?.performEditorAction(currentInputEditorInfo?.actionId
            ?: android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
    }

    // ── Core voice-input pipeline ─────────────────────────────────────────────

    private suspend fun processVoiceInput() {
        // 1. Stop recorder — get raw PCM (kept for reference; not sent to model)
        val pcmSamples = audioRecorder.stopAndGet()
        Log.d(TAG, "Captured ${pcmSamples.size} samples")

        // 2. Offline speech recognition
        keyboardViewManager.setState(
            KeyboardViewManager.State.PROCESSING,
            getString(R.string.status_recognizing)
        )
        val rawAsr = recognizeSpeech()
        if (rawAsr.isBlank()) {
            keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
            clearAudioBuffers(pcmSamples)
            return
        }
        Log.d(TAG, "ASR result: $rawAsr")

        // 3. Gather screen context
        val screenCtx = withContext(Dispatchers.IO) { ScreenContextProvider.capture() }

        // 4. Load dictionary hints
        val hints = withContext(Dispatchers.IO) { dictionary.getHints() }

        // 5. Run AI correction
        keyboardViewManager.setState(
            KeyboardViewManager.State.PROCESSING,
            getString(R.string.status_correcting)
        )
        val request = TranscriptionRequest(
            rawAsr = rawAsr,
            screenText = screenCtx.text,
            screenBitmap = screenCtx.screenshot,
            dictionaryHints = hints
        )
        val result: TranscriptionResult = withContext(Dispatchers.IO) {
            aiEngine!!.transcribe(request)
        }
        Log.d(TAG, "Corrected: ${result.text} | nouns: ${result.detectedNouns}")

        // 6. Commit text to focused field
        if (result.text.isNotBlank()) {
            currentInputConnection?.commitText(result.text, 1)
            keyboardViewManager.showPreview(result.text)
        }

        // 7. Persist detected nouns
        if (result.detectedNouns.isNotEmpty()) {
            withContext(Dispatchers.IO) { dictionary.recordNouns(result.detectedNouns) }
        }

        // 8. Free all buffers — strictly per-event
        clearAudioBuffers(pcmSamples)
        screenCtx.screenshot?.recycle()
        keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
    }

    // ── Offline Speech Recognition ────────────────────────────────────────────

    /**
     * Wraps [SpeechRecognizer] in a coroutine.
     *
     * Uses [RecognizerIntent.EXTRA_PREFER_OFFLINE] so the system routes through
     * the device's offline recognizer (Google, OEM, etc.) without any network
     * call.  Falls back to online if the device has no offline model installed.
     */
    @SuppressLint("MissingPermission")
    private suspend fun recognizeSpeech(): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            speechRecognizer?.destroy()
            val sr = SpeechRecognizer.createSpeechRecognizer(this@GemmaKeyIMEService)
            speechRecognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
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
                // Increase silence timeout for longer dictation
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
            }

            sr.startListening(intent)

            cont.invokeOnCancellation {
                sr.stopListening()
                sr.destroy()
                speechRecognizer = null
            }
        }
    }

    // ── Memory management ─────────────────────────────────────────────────────

    private fun clearAudioBuffers(samples: ShortArray) {
        samples.fill(0)
        System.gc()
    }
}
