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

> **Native multimodality**: Gemma 4 E2B supports text, image, and audio inputs natively (not via adapter layers).  `LiteRTEngine` probes for image and audio overloads on `Conversation` at runtime via reflection — they activate automatically when the SDK exposes them, with silent text-only fallback until then.

---

## Architecture

```
User presses mic  (onMicDown)
       │
       ├─ [supportsNativeAudio=true]  AudioRecorder.startRecording()   ─────────┐
       └─ [default]                   SpeechRecognizer.startListening()          │
                                                                                  │
       ╔══════════════════════════════════════════════════════╗                  │
       ║  ModalityCollector.startCollection() — parallel      ║                  │
       ║  Dispatchers.IO: AccessibilityService screen text    ║                  │
       ║               + downscaled screenshot (224 × 224)    ║                  │
       ╚══════════════════════════════════════════════════════╝                  │
                                                                                  │
User releases mic  (onMicUp)                                                     │
       │                                                                          │
       └──────────────────── stopListening() / stopAndGet() ◄────────────────────┘
       │
       ▼
 raw text (SR)  OR  PCM ShortArray (native audio)
       │
       ▼
 ModalityCollector.awaitContext(supportsVision)
       │  (typically already resolved — zero added latency)
       │
       ├─ FULL         → text/PCM + screen text + screenshot  (LiteRTEngine)
       ├─ TEXT_CONTEXT → text/PCM + screen text               (any engine, no bitmap)
       └─ MINIMAL      → text/PCM only                        (collection failed)
       │
       ▼
 AIEngine.transcribe()  OR  AIEngine.transcribeAudio()
       │  LiteRTEngine  (Gemma 4 E2B)  — default; vision + audio probed at runtime
       │  AICoreEngine  (Gemini Nano)  — if AICore / Pixel 8+ detected; text-only
       │
       ▼
 InputConnection.commitText()  →  text appears in focused field
       │
       ▼
 CustomDictionary.recordNouns()  →  Room DB persists detected terms for future hints
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
| `audio/AudioRecorder` | `AudioRecord` wrapper with daemon drain thread; used by native audio path |
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

| Area | What was done |
|---|---|
| **Build** | Root `build.gradle.kts` fixed; `ndk.abiFilters`/`splits.abi` conflict removed; `kotlinOptions` restored (`compilerOptions` is KMP-only) |
| **AICoreEngine** | Rewritten for correct SDK `com.google.mlkit:genai-prompt:1.0.0-beta2`; `Generation.getClient(generationConfig{})`, Flow-based `generateContentStream`, `checkStatus()`/`download()`/`warmup()`, `response.candidates.firstOrNull()?.text` |
| **LiteRTEngine** | Fully rewritten with correct SDK API: `ConversationConfig(samplerConfig=SamplerConfig(topK, topP:Double, temperature:Double), systemInstruction=Contents.of(...))`, `sendMessageAsync(Contents, MessageCallback, emptyMap())`, `Content.ImageBytes/AudioBytes/Text`, `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens)`, `@OptIn(ExperimentalApi::class)` on `engine.initialize()`; NPU→GPU→CPU fallback with vision+audio/vision/text-only tiers |
| **GemmaAccessibilityService** | `AccessibilityNodeInfo` child recycling memory leak fixed |
| **AudioRecorder** | Daemon drain thread prevents `AudioRecord` buffer overflow beyond ~2 s |
| **GemmaKeyIMEService** | Pipeline starts at `onMicDown()`, not after; `voiceJob` cancelled before re-launch; RECORD_AUDIO permission checked before mic; SR timeout (15 s); screenshot throttle (3 s); native audio path via `AudioRecorder` (mutually exclusive with SR) |
| **ModalityCollector** | Zero-latency parallel context collection; `MINIMAL` / `TEXT_CONTEXT` / `FULL` state machine |
| **AIEngine interface** | `supportsVision`, `supportsNativeAudio`, `transcribeAudio()` added; `LiteRTEngine` implements all; `AICoreEngine` is text-only |
| **PromptBuilder** | 4×4 pixel visual descriptor from screenshot; `buildAudioContext()` for native audio path; `SYSTEM_INSTRUCTION` constant (audio-primary rule); `buildMessage()` for LiteRTEngine (data-only, rules in `systemInstruction`); `build()` embeds `SYSTEM_INSTRUCTION` inline for AICoreEngine |
| **SetupActivity** | RECORD_AUDIO runtime permission flow (`ActivityResultContracts.RequestPermission`); "Don't ask again" redirects to app settings |
| **AIEngineFactory** | AICore package verified (`com.google.android.aicore`); package list + service check |
| **Infrastructure** | `gradle.properties`, `gradle-wrapper.properties`, `gradlew`, `.gitignore` |

### Known issues

| # | Severity | Description |
|---|---|---|
| 1 | Medium | `isAICoreAvailable()` uses the unofficial `"android_app_intelligence"` system service string — if Google renames it, AICore detection silently falls back to LiteRT on a capable device |
| 2 | Low | `GemmaAccessibilityService` fires `refreshScreenText()` on every `TYPE_WINDOW_CONTENT_CHANGED` event (can be very frequent in apps with live updates); consider debouncing by ~300 ms |
| 3 | ~~Medium~~ | ~~`AICoreEngine` used old experimental SDK `com.google.android.ai.edge.aicore:aicore:0.0.1-exp03` with wrong class names — replaced with `com.google.mlkit:genai-prompt:1.0.0-beta2`; `LiteRTEngine` used wrong `ConversationConfig` params, callback-less `sendMessageAsync`, missing `visionBackend`/`audioBackend`/`maxNumTokens` in `EngineConfig`, reflection-based vision/audio probes — all fixed~~ |
| 4 | Info | When `SpeechRecognizer` returns an empty string (no speech detected), the pipeline transitions straight to IDLE with no user feedback; a brief "No speech detected" status would improve UX |

---

## Branch

Active development: `claude/fix-compilation-errors-PUaff`
