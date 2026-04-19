# Project Overview: WearableAI (YC Voice Agents Hack)

WearableAI is a low-latency, on-device voice assistant built for the YC Voice Agents Hackathon. It leverages **Cactus** (a low-latency engine for mobile/wearables) and **Gemma 4** (Google DeepMind's multimodal on-device model) to provide a responsive, voice-first experience.

The app is a Kotlin Multiplatform (KMP) project that runs **Gemma 4 E2B** locally on Android devices, using audio input from paired Meta AI glasses. It features a hybrid routing system that falls back to **Gemini 2.5 Flash** in the cloud when online or for complex tasks.

## Core Technologies
- **Cactus Engine**: Low-latency execution on edge devices with hybrid cloud routing.
- **Gemma 4 E2B**: Multimodal on-device model supporting voice, vision, and function calling.
- **Google GenAI SDK**: Gemini 2.5 Flash used for cloud fallback.
- **Meta Wearables DAT SDK**: Interfaces with Meta AI glasses for high-quality audio capture.
- **Kotlin Multiplatform**: Shared logic across Android and (potentially) iOS.

## Mandates & Challenges
- **Voice-First**: Primary interaction is via voice, processed on-device whenever possible.
- **Low Latency**: Prioritize on-device Gemma 4 execution to minimize response time.
- **Hybrid Routing**: Intelligent fallback to cloud Gemini when network is available and local compute is constrained or task complexity is high.
- **Privacy**: Local processing of voice data ensures user privacy.

## Project Structure
```
/
├── cactus/               # Cactus SDK (cloned as a sibling or subdirectory)
└── mobile-app/           # Main Kotlin Multiplatform project
    ├── app/              # Android application module (UI, TTS, Routing)
    └── shared/           # KMP module (Core logic, Cactus bindings, Meta SDK)
        ├── commonMain/   # Shared business logic and Voice Agent orchestration
        └── androidMain/  # Android-specific implementations (Audio, JNI)
```

## Setup & Building

### 1. Prerequisites
- JDK 21, Android SDK (API 36), NDK 28.
- Meta AI glasses paired with the phone.
- `cactus` CLI installed and authenticated (`cactus auth`).

### 2. Environment Setup
Clone Cactus as a sibling directory to `mobile-app/`:
```bash
git clone https://github.com/cactus-compute/cactus
cd cactus && source ./setup && cd ..
```

### 3. Build Native Dependencies
The app requires `libcactus.so` built for Android:
```bash
cd mobile-app
# Use the setup script or manual build
bash setup-native.sh
```
This copies the built library to `app/src/main/jniLibs/arm64-v8a/`.

### 4. Model Installation
Gemma 4 E2B weights (~4.7 GB) must be sideloaded to the app's **internal ext4 storage** (not `/sdcard`):
1. Download weights from HuggingFace: `cactus-compute/google--gemma-4-E2B-it`.
2. Push to `/sdcard/gemma-4-E2B-it`.
3. Use `adb shell run-as com.example.wearableai cp -r /sdcard/gemma-4-E2B-it files/` to move to internal storage.

### 5. Configuration
Copy `mobile-app/local.properties.example` to `local.properties` and fill in:
- `github_user`/`github_token` (for Meta SDK)
- `meta_wearables_app_id`/`client_token`
- `gemini_api_key`

## Key Workflows

### Voice Processing Loop
1. **Audio Capture**: `VoiceInputProvider` (androidMain) captures 16kHz mono audio from Meta glasses.
2. **VAD**: Voice Activity Detection segments the stream into utterances.
3. **Inference Routing**: `VoiceAgent` (commonMain) decides between local Gemma 4 or cloud Gemini.
4. **Local Inference**: Cactus executes Gemma 4 E2B. Audio turns are handled by resetting the KV cache (`cactusReset`) and replaying text history.
5. **Transcription Memory**: The model uses `<heard>` tags to provide transcripts which are saved to history for multi-turn recall.
6. **Output**: Text responses are shown in the UI and spoken via Android TTS (routed to glasses).

## Development Conventions
- **Low Latency**: Always optimize for local execution.
- **Type Safety**: Use Kotlin's type system strictly, especially for JNI/Cactus interactions.
- **Architectural Integrity**: Keep `shared/commonMain` platform-agnostic. Use `expect`/`actual` for platform-specific hardware access.

## Troubleshooting
- **SIGSEGV in `cactus_gemm_int4`**: Usually means the model is on `/sdcard` (FUSE). Move it to internal `files/` (ext4).
- **No Audio**: Check if glasses are the active BT audio sink in Android settings.
- **Gemini Memory Loss**: Ensure replies include `<heard>` tags; otherwise, transcripts won't be saved to history.
