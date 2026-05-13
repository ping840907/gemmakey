package com.example.gemmakey

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.example.gemmakey.ai.AIEngine
import com.example.gemmakey.ai.AIEngineFactory
import com.example.gemmakey.ai.TranscriptionRequest
import com.example.gemmakey.audio.AudioRecorder
import com.example.gemmakey.dict.CustomDictionary
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Main Input Method Service.
 *
 * ## SpeechRecognizer path (default)
 *   onMicDown → SpeechRecognizer.startListening() + ModalityCollector.startCollection()
 *   onMicUp   → SpeechRecognizer.stopListening()
 *   onResults → AI correction → commitText
 *
 * ## Native audio path (when engine.supportsNativeAudio = true)
 *   onMicDown → AudioRecorder.startRecording() + ModalityCollector.startCollection()
 *   onMicUp   → stop recorder → AI transcribeAudio() → commitText
 *
 * The two paths are mutually exclusive: they never share the microphone.
 */
class GemmaKeyIMEService : InputMethodService(), KeyboardViewManager.KeyboardActionListener {

    private val TAG = "GemmaKeyIME"

    private companion object {
        const val RECOGNITION_TIMEOUT_MS = 15_000L   // prevent SR hanging indefinitely
        const val SCREENSHOT_THROTTLE_MS = 3_000L    // limit capture on rapid field switches
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var keyboardViewManager: KeyboardViewManager
    private lateinit var dictionary: CustomDictionary
    private var aiEngine: AIEngine? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private val audioRecorder = AudioRecorder()
    private var voiceJob: Job? = null
    private val modalityCollector = ModalityCollector()
    private var lastScreenshotRequestMs = 0L

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dictionary = CustomDictionary(this)
        initAIEngine()
        Log.i(TAG, "GemmaKey IME created")
    }

    override fun onDestroy() {
        scope.cancel()
        modalityCollector.cancel()
        audioRecorder.release()
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
        // Throttle: avoid spamming screenshot capture when user taps between fields rapidly.
        requestScreenshotThrottled()
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
                Log.i(TAG, "AI engine ready | nativeAudio=${engine.supportsNativeAudio}")
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
        // Gate on runtime permission — SpeechRecognizer and AudioRecord both need it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            keyboardViewManager.setState(
                KeyboardViewManager.State.ERROR,
                getString(R.string.status_error)
            )
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }
        // Cancel any in-flight pipeline before starting a new one.
        voiceJob?.cancel()
        voiceJob = null

        keyboardViewManager.setState(KeyboardViewManager.State.RECORDING)
        requestScreenshotThrottled()
        modalityCollector.startCollection(scope)

        if (aiEngine?.supportsNativeAudio == true) {
            // Native audio path: AudioRecorder captures PCM; processing starts on onMicUp.
            audioRecorder.startRecording()
        } else {
            // SpeechRecognizer path: pipeline starts listening immediately.
            voiceJob = scope.launch {
                try {
                    processVoiceInput()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Voice processing error: ${e.message}", e)
                    keyboardViewManager.setState(KeyboardViewManager.State.ERROR, getString(R.string.status_error))
                }
            }
        }
    }

    override fun onMicUp() {
        if (aiEngine?.supportsNativeAudio == true) {
            // Stop PCM capture and hand off to the native audio pipeline.
            voiceJob = scope.launch {
                try {
                    processNativeAudioInput()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Native audio processing error: ${e.message}", e)
                    keyboardViewManager.setState(KeyboardViewManager.State.ERROR, getString(R.string.status_error))
                }
            }
        } else {
            speechRecognizer?.stopListening()
        }
    }

    override fun onDeletePressed() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun onEnterPressed() {
        currentInputConnection?.performEditorAction(
            currentInputEditorInfo?.actionId ?: EditorInfo.IME_ACTION_DONE
        )
    }

    // ── SpeechRecognizer pipeline ─────────────────────────────────────────────

    private suspend fun processVoiceInput() {
        val rawAsr = recognizeSpeech()
        if (rawAsr.isBlank()) {
            keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
            return
        }
        Log.d(TAG, "ASR result: $rawAsr")

        val engine = aiEngine ?: run {
            keyboardViewManager.setState(KeyboardViewManager.State.ERROR, getString(R.string.status_model_missing))
            modalityCollector.cancel()
            return
        }
        val ctx = modalityCollector.awaitContext(engine.supportsVision)
        val hints = withContext(Dispatchers.IO) { dictionary.getHints() }
        Log.d(TAG, "Modality: ${ctx.state} | screenText=${ctx.screenText.length}ch | bitmap=${ctx.screenshot != null}")

        keyboardViewManager.setState(KeyboardViewManager.State.PROCESSING, getString(R.string.status_correcting))
        val result = withContext(Dispatchers.IO) {
            engine.transcribe(
                TranscriptionRequest(
                    rawAsr = rawAsr,
                    screenText = ctx.screenText,
                    screenBitmap = ctx.screenshot,
                    dictionaryHints = hints
                )
            )
        }
        Log.d(TAG, "Corrected: ${result.text} | nouns: ${result.detectedNouns}")

        commitResult(result)
        modalityCollector.releaseBitmap(ctx)
        keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
    }

    // ── Native audio pipeline ─────────────────────────────────────────────────

    private suspend fun processNativeAudioInput() {
        keyboardViewManager.setState(KeyboardViewManager.State.PROCESSING, getString(R.string.status_processing))
        val pcm = audioRecorder.stopAndGet()
        if (pcm.isEmpty()) {
            modalityCollector.cancel()
            keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
            return
        }
        Log.d(TAG, "Native audio: ${pcm.size} samples captured")

        val engine = aiEngine ?: run {
            modalityCollector.cancel()
            keyboardViewManager.setState(KeyboardViewManager.State.ERROR, getString(R.string.status_model_missing))
            return
        }
        val ctx = modalityCollector.awaitContext(engine.supportsVision)
        val hints = withContext(Dispatchers.IO) { dictionary.getHints() }

        keyboardViewManager.setState(KeyboardViewManager.State.PROCESSING, getString(R.string.status_correcting))
        val result = withContext(Dispatchers.IO) {
            engine.transcribeAudio(pcm, ctx.screenText, ctx.screenshot, hints)
        }
        Log.d(TAG, "Native audio result: ${result?.text} | nouns: ${result?.detectedNouns}")

        if (result != null) {
            commitResult(result)
        } else {
            // Engine returned null — audio API unexpectedly unavailable at runtime.
            Log.w(TAG, "transcribeAudio returned null; audio API not available in this SDK build")
            keyboardViewManager.setState(KeyboardViewManager.State.ERROR, getString(R.string.status_error))
        }
        modalityCollector.releaseBitmap(ctx)
        // Always return to IDLE so the user can retry without reopening the keyboard.
        keyboardViewManager.setState(KeyboardViewManager.State.IDLE)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private suspend fun commitResult(result: com.example.gemmakey.ai.TranscriptionResult) {
        if (result.text.isNotBlank()) {
            currentInputConnection?.commitText(result.text, 1)
            keyboardViewManager.showPreview(result.text)
        }
        if (result.detectedNouns.isNotEmpty()) {
            withContext(Dispatchers.IO) { dictionary.recordNouns(result.detectedNouns) }
        }
    }

    private fun requestScreenshotThrottled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        if (now - lastScreenshotRequestMs >= SCREENSHOT_THROTTLE_MS) {
            GemmaAccessibilityService.instance?.requestScreenshot()
            lastScreenshotRequestMs = now
        }
    }

    // ── Speech recognition ────────────────────────────────────────────────────

    /**
     * Starts [SpeechRecognizer] and suspends until results arrive or
     * [RECOGNITION_TIMEOUT_MS] elapses (prevents indefinite hang on SR errors).
     * End-of-speech is signalled by [SpeechRecognizer.stopListening] in [onMicUp].
     */
    @SuppressLint("MissingPermission")
    private suspend fun recognizeSpeech(): String =
        withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    speechRecognizer?.destroy()
                    val sr = SpeechRecognizer.createSpeechRecognizer(this@GemmaKeyIMEService)
                    speechRecognizer = sr

                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
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
        } ?: run {
            Log.w(TAG, "Speech recognition timed out after ${RECOGNITION_TIMEOUT_MS / 1000}s")
            ""
        }
}
