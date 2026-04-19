# WearableAI Mobile App

Kotlin Multiplatform app for the YC voice-agents project. Runs **Gemma 4 E2B** locally on-device via the Cactus SDK, with Gemini 2.5 Flash as a cloud fallback. Audio input comes from paired Meta AI glasses; assistant replies are spoken back through the phone's TTS (which routes to the glasses automatically when they're the active BT audio sink).

## Prerequisites

- JDK 21
- Android SDK with API 36 platform and NDK 28
- Android phone running API 29+ (Android 10+) with at least ~6 GB free on internal storage
- Meta AI glasses paired via the **Meta AI** mobile app
- `adb` on your PATH

## Accounts & keys you'll need

All of these feed into `mobile-app/local.properties` (gitignored). Copy `local.properties.example` to `local.properties` and fill in the values.

| Key                            | Where to get it                                                                                          |
| ------------------------------ | -------------------------------------------------------------------------------------------------------- |
| `sdk.dir`                      | Path to your local Android SDK.                                                                          |
| `github_user` / `github_token` | A GitHub PAT with **`read:packages`** scope — used to pull the Meta Wearables DAT SDK from GitHub Packages. Create at <https://github.com/settings/tokens>. |
| `meta_wearables_app_id` and `meta_wearables_client_token` | Register an app at <https://wearables.developer.meta.com/>, then copy both values from its dashboard. |
| `gemini_api_key`               | Create at <https://aistudio.google.com/app/apikey>. Used for the Gemini 2.5 Flash cloud fallback.        |

## Setup

### 1. Clone Cactus as a sibling directory

The build expects `../cactus` relative to `mobile-app/`:

```bash
cd ..                       # to the parent of mobile-app/
git clone https://github.com/cactus-compute/cactus
```

### 2. Build the Cactus native library

```bash
cd mobile-app
ANDROID_NDK_HOME=~/Android/Sdk/ndk/28.1.13356709 bash ../cactus/android/build.sh
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../cactus/android/libcactus.so app/src/main/jniLibs/arm64-v8a/
```

(Or `bash setup-native.sh` which does the same.)

### 3. Install the Gemma 4 E2B model (manual — no in-app download)

Gemma 4 E2B uses Cactus's own directory format (not GGUF). The weights are ~4.7 GB and must be sideloaded. **You MUST install them into the app's internal ext4 storage**, not `/sdcard`: Cactus's `mmap` + `MADV_DONTNEED` weight paging crashes in `cactus_gemm_int4` on FUSE-mounted `/sdcard`.

**Download** from the Cactus HuggingFace repo (requires `huggingface-cli login` or your HF token):

```bash
huggingface-cli download cactus-compute/google--gemma-4-E2B-it \
  --local-dir /tmp/gemma-4-E2B-it
```

or via `curl`/`git lfs clone` against `https://huggingface.co/cactus-compute/google--gemma-4-E2B-it`.

**Push to the phone and move to internal storage:**

```bash
# 1. Build + install a debug APK first so the app's data directory exists.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Push the model to /sdcard (fast, no root needed).
adb push /tmp/gemma-4-E2B-it /sdcard/gemma-4-E2B-it

# 3. Copy it into the app's private ext4 storage via run-as (works on a
#    debuggable build; no root). This is the path the app actually reads.
adb shell run-as com.example.wearableai cp -r /sdcard/gemma-4-E2B-it files/

# 4. Reclaim the ~4.7 GB on /sdcard.
adb shell rm -r /sdcard/gemma-4-E2B-it
```

Verify: `adb shell run-as com.example.wearableai ls files/gemma-4-E2B-it` should list the model shards.

### 4. Pair your glasses

Open the **Meta AI** app on the phone, sign in, and pair the glasses. The WearableAI app talks to them indirectly through the Meta AI app — the glasses must already be paired and connected there before you launch WearableAI.

## Build & Run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.wearableai/.MainActivity
```

### First-run flow

1. App asks for Bluetooth + microphone permissions, then registers with the Meta AI app and requests the glasses-mic permission.
2. Tap **Connect Glasses** — this loads Gemma 4 into memory (~10–15 s cold) and opens the mic stream from the glasses.
3. Tap **Start Agent** — VAD begins segmenting utterances. Each one is saved as a 16 kHz mono WAV, routed to either Gemini or Gemma based on connectivity + the **Force local inference** checkbox.
4. Replies appear in the transcript and are spoken aloud. When the glasses are the active BT audio sink, Android routes TTS there.

## Routing decisions

- By default the agent prefers **cloud (Gemini 2.5 Flash)** when the phone is online.
- Check **Force local inference** to bypass cloud and always use on-device Gemma 4 E2B.
- Offline → automatic fallback to Gemma 4.

## Architecture

```
mobile-app/
├── app/                          # Android application module
│   ├── GeminiCloudFallback.kt    # Gemini 2.5 Flash via com.google.ai.client.generativeai
│   ├── MainViewModel.kt          # UI state, TTS, model path resolution, routing decision
│   ├── MainActivity.kt           # Permissions, Meta registration, view bindings
│   └── WearableAIApp.kt          # Application class — initializes Cactus telemetry / logging
├── shared/                       # Kotlin Multiplatform module
│   ├── commonMain/
│   │   ├── ModelConfig.kt        # Model constants + system prompt (including <heard> directive)
│   │   ├── VoiceAgent.kt         # Local/cloud orchestration; serializes turns via Mutex; parses <heard>
│   │   ├── MobileAISession.kt  # Connect → listen → inference queue
│   │   └── VoiceInputProvider.kt  # expect/actual interface for audio from glasses
│   └── androidMain/
│       ├── VoiceInputProvider.android.kt   # Meta Wearables DAT SDK + AudioRecord + VAD
│       └── com/cactus/                    # Cactus JNI bindings
└── app/src/main/jniLibs/arm64-v8a/libcactus.so   # Built from ../cactus (gitignored)
```

### Why the model lives in internal ext4 storage

Cactus mmaps the weight files and releases unused pages with `MADV_DONTNEED`. Android's `/sdcard` is FUSE-mounted and does not reliably honor `DONTNEED`; under memory pressure this crashes inside `cactus_gemm_int4`. Copying the model into the app's private `files/` directory (ext4) fixes it.

### Why each local turn resets the KV cache

Cactus's prefix KV cache works great for text-only chat but does not invalidate audio-bearing positions when the audio blob changes. Without a reset, turn 2's prefill is ~20× faster than turn 1 and the model emits a character-identical response to turn 1, ignoring new audio. `VoiceAgent.processUtterance` calls `cactusReset()` before each local inference as a workaround. Text history is still replayed every turn, so multi-turn recall works; we just pay the text-prefill cost (~1 s on this device) each time instead of getting it for free.

### Multi-turn memory via transcription

Gemma 4's audio adapter behaves poorly when prior audio turns are replayed in the conversation history. Instead, the system prompt instructs the model to begin every reply with `<heard>verbatim transcript</heard>`. `VoiceAgent.splitHeard` strips the tag for display and uses the transcript as the text of that user turn in history. Subsequent turns see only text, so attention on the current audio is clean.

## Troubleshooting

- **`libcactus.so not found`** — rerun step 2. Check that `app/src/main/jniLibs/arm64-v8a/libcactus.so` exists.
- **App crashes on Connect Glasses with `SIGSEGV` in `cactus_gemm_int4`** — the model is on `/sdcard` instead of internal storage. Redo step 3 with `run-as cp`.
- **Local Gemma echoes the first answer on every subsequent turn** — you're on a build without `cactusReset()`; pull latest.
- **Gemini replies but has no memory of prior turns** — check the `[Gemini] raw reply:` log and confirm responses start with `<heard>…</heard>`. Without it, transcripts aren't saved to history.
- **TTS plays from phone speaker instead of glasses** — the glasses aren't the active BT audio output. Open Android Bluetooth settings and switch the glasses to "Media audio".
