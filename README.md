# GemmaKey

An Android voice-first IME (input method / keyboard) that uses **Gemma 4 E2B** running entirely on-device to correct speech-recognition results before they are typed into the focused field.  No internet connection is required — all AI inference happens locally.

---

## Model

| Property | Value |
|---|---|
| Model | **Gemma 4 E2B** (2 billion effective parameters, MoE architecture) |
| Quantisation | INT4 (`.litertlm` format) |
| File | `gemma-4-E2B-it-int4.litertlm` (~1–2 GB) |
| Source | [Hugging Face — litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Developer | Google (official release, part of the Google AI Edge SDK family) |
| Modality | **Natively multimodal** — text, image, and audio inputs |
| Runtime | LiteRT-LM Kotlin SDK v0.11.0 (`com.google.ai.edge.litertlm:litertlm-android:0.11.0`) |
| Context window | 128 K tokens |
| Recommended device | 6 GB RAM+, Pixel 8 or equivalent NPU/DSP device |

> **Note on native multimodality**: Gemma 4 E2B is built with native support for text, image, and audio inputs — not via adapter layers.  The current `LiteRTEngine` implementation wires text + optional screenshot into the request; the image Contents API path is scaffolded and awaits confirmation of the exact `Conversation.sendMessageAsync(Content)` signature in LiteRT-LM v0.11.0 (see `TODO` in `LiteRTEngine.kt`).

---

## Architecture

```
User presses mic
       │
       ▼
 GemmaKeyIMEService  ──── onMicDown() ─────────────────────────────────┐
       │                                                                 │
       │  SpeechRecognizer.startListening()          ModalityCollector.startCollection()
       │  (user speaks)                              (parallel: screen text + screenshot)
       │
 User releases mic
       │
       ▼
 SpeechRecognizer.onResults()  →  raw ASR text
       │
       ▼
 ModalityCollector.awaitContext(supportsVision)
       │  (typically already complete — zero latency)
       │
       ├─ FULL         → ASR + screen text + screenshot  (LiteRT/Gemma 4 E2B)
       ├─ TEXT_CONTEXT → ASR + screen text               (any engine, no bitmap)
       └─ MINIMAL      → ASR only                        (engine not ready / collection failed)
       │
       ▼
 AIEngine.transcribe(TranscriptionRequest)
       │  LiteRTEngine  (Gemma 4 E2B)    — default
       │  AICoreEngine  (Gemini Nano)    — if AICore / Pixel 8+ detected
       │
       ▼
 InputConnection.commitText()  →  text appears in focused field
       │
       ▼
 CustomDictionary.recordNouns()  →  Room DB persists unusual terms for future hints
```

---

## Module overview

| Package / File | Purpose |
|---|---|
| `GemmaKeyIMEService` | `InputMethodService`; owns the voice pipeline and coroutine scope |
| `GemmaAccessibilityService` | Captures screen text (accessibility tree) and screenshots (API 30+) |
| `KeyboardViewManager` | Inflates keyboard UI; mic, delete, enter buttons |
| `ModalityCollector` | State machine for parallel multimodal context collection |
| `SetupActivity` | One-time guided setup: accessibility, IME selection, model file check |
| `ai/AIEngine` | Interface + `TranscriptionRequest` / `TranscriptionResult` data contracts |
| `ai/AIEngineFactory` | Selects best available engine at runtime |
| `ai/LiteRTEngine` | Gemma 4 E2B via LiteRT-LM (`supportsVision = true`) |
| `ai/AICoreEngine` | Gemini Nano via AICore (`supportsVision = false`, text-only SDK) |
| `ai/PromptBuilder` | Zero-shot correction + noun-extraction prompts |
| `audio/AudioRecorder` | `AudioRecord` wrapper with daemon drain thread (available for raw PCM if needed) |
| `screen/ScreenContextProvider` | Aggregates accessibility text + downscaled screenshot (max 224 × 224) |
| `dict/CustomDictionary` | Room database; stores unusual proper nouns to improve future transcriptions |

---

## `ModalityCollector` — state machine detail

```
States:    IDLE ──startCollection()──► COLLECTING ──awaitContext()──► IDLE

ModalityState:
  MINIMAL       — ASR text only (collection failed or engine not ready)
  TEXT_CONTEXT  — ASR + screen text (engine is text-only, or no screenshot captured)
  FULL          — ASR + screen text + screenshot (LiteRTEngine + bitmap available)
```

Collection starts immediately when the user presses the mic button and runs on `Dispatchers.IO` in a `Deferred`.  By the time `SpeechRecognizer` delivers its result the context deferred is typically already resolved, so `awaitContext()` returns without blocking the pipeline.

---

## Build configuration

| Parameter | Value |
|---|---|
| `compileSdk` | 35 |
| `minSdk` | 30 (requires `AccessibilityService.takeScreenshot()`) |
| `targetSdk` | 35 |
| Android Gradle Plugin | 8.7.0 |
| Kotlin | 2.0.21 |
| Gradle | 8.9 |
| JVM target | 17 |
| ABI filter | `arm64-v8a` only (NPU devices) |

`android:largeHeap="true"` is set in the manifest to accommodate the ~1.5 GB INT4 model weights.

---

## Permissions

| Permission | Reason |
|---|---|
| `RECORD_AUDIO` | Microphone access for `SpeechRecognizer` |
| `MODIFY_AUDIO_SETTINGS` | Required alongside RECORD_AUDIO on some devices |
| `BIND_INPUT_METHOD` | Declares this app as a keyboard (system-granted) |
| `BIND_ACCESSIBILITY_SERVICE` | Screen text and screenshot capture (system-granted) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Keeps IME service alive during recording |
| `POST_NOTIFICATIONS` | Service notifications (Android 13+) |
| `VIBRATE` | Optional haptic feedback |

No `INTERNET` permission is declared — the app is intentionally 100% offline.

---

## Setup instructions

1. **Build and install** the APK onto a device running Android 11+ (API 30).
2. **Download the model file**:
   ```
   gemma-4-E2B-it-int4.litertlm
   ```
   from [huggingface.co/litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).
3. **Place the file** at either:
   - `/sdcard/Android/data/com.example.gemmakey/files/gemma-4-E2B-it-int4.litertlm`
   - `<internal-files>/models/gemma-4-E2B-it-int4.litertlm`
4. Open the **GemmaKey** app and follow the on-screen checklist:
   - Enable the **GemmaKey Accessibility Service**
   - Select **GemmaKey** as the active keyboard
5. Tap any text field, switch to GemmaKey, and press-and-hold the **mic button** to speak.

---

## Engine selection logic

```
isAICoreAvailable(context)?
  YES → AICoreEngine  (Gemini Nano, Pixel 8+)   — supportsVision = false
  NO  → LiteRTEngine  (Gemma 4 E2B, universal)  — supportsVision = true
```

AICore detection checks for the `com.google.android.aicore` package and the `android_app_intelligence` system service.  If either is absent the app falls back to LiteRT automatically.

---

## Current status

### Completed

- [x] Root `build.gradle.kts` restructured with correct plugin version declarations
- [x] Removed `ndk.abiFilters` / `splits.abi` conflict (AGP rejects both simultaneously)
- [x] `kotlinOptions { jvmTarget = "17" }` — `compilerOptions` block reverted (KMP only)
- [x] `AICoreEngine` — fixed `GenerativeAIException`, `DownloadCallback` signatures, `DownloadConfig` constructor, `generateContentStream()` Flow API, `prepareInferenceEngine()`
- [x] `LiteRTEngine` — `ConversationConfig` parameter, `use {}` pattern for `Conversation`, `companion object MODEL_FILENAME`, `engine?.close()` in `release()`
- [x] `GemmaAccessibilityService` — `AccessibilityNodeInfo` child recycling memory leak fixed
- [x] `AudioRecorder` — daemon drain thread prevents `AudioRecord` buffer overflow beyond ~2 s
- [x] `GemmaKeyIMEService` — pipeline starts at `onMicDown()` (not after); `stopListening()` on `onMicUp()`; safe null checks replace `!!`
- [x] `SetupActivity` — uses `LiteRTEngine.MODEL_FILENAME` companion constant
- [x] `ModalityCollector` — zero-latency parallel context collection state machine
- [x] `AIEngine.supportsVision` — interface property; `LiteRTEngine = true`, `AICoreEngine = false`
- [x] `gradle.properties`, `gradle-wrapper.properties`, `gradlew` — project infrastructure

### Completed (continued)

- [x] **RECORD_AUDIO runtime permission**: `SetupActivity` now uses `ActivityResultContracts.RequestPermission`; button shown only while permission is absent; "Don't ask again" case redirects to app settings.
- [x] **AICore package name**: verified as `com.google.android.aicore` (Pixel 8+, Android 14+); `isAICoreAvailable()` refactored to iterate a package list and separately verify the inference service is reachable.
- [x] **Visual context via text API**: `PromptBuilder.build()` samples a 4×4 pixel grid from `screenBitmap` to derive a compact descriptor (theme, dominant hue, brightness) appended to the prompt — gives the model app-context signal with zero extra tokens compared to embedding raw image data.

### Completed (continued)

- [x] **Native image token injection**: `LiteRTEngine.collectMaybeVision()` probes `Conversation.sendMessageAsync` at runtime for a `(String, List<Bitmap>)` or `(String, Bitmap)` overload via reflection.  When the SDK exposes the method it activates automatically; otherwise falls back to the text visual descriptor with no code change required.
- [x] **Native audio input**: `AIEngine.supportsNativeAudio` + `transcribeAudio()` interface added.  `LiteRTEngine` probes `Conversation` for `sendAudioAsync` / `transcribeAudio` / `generateFromAudio` at runtime.  `GemmaKeyIMEService` routes `onMicDown`/`onMicUp` to `AudioRecorder` (PCM, 16 kHz, 16-bit) when `supportsNativeAudio = true`, and to `SpeechRecognizer` otherwise — the two paths are mutually exclusive so they never conflict for the microphone.
- [x] **Speech recognition timeout**: `recognizeSpeech()` wrapped in `withTimeoutOrNull(15 s)` — prevents the UI from hanging indefinitely if `SpeechRecognizer` never delivers `onResults`.
- [x] **Screenshot throttle**: `requestScreenshotThrottled()` helper limits screenshot captures to once per 3 s, preventing redundant AccessibilityService calls when the user taps rapidly between text fields.

---

## Branch

Active development: `claude/fix-compilation-errors-PUaff`
