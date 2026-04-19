# Known Limitations

## iOS / Cross-Platform

The KMP `shared` module is structured for cross-platform but iOS support is incomplete.

### What works
- All 36 Cactus functions have real `iosMain` actual implementations via cinterop FFI (`Cactus.ios.kt`)
- iOS targets (`iosArm64`, `iosSimulatorArm64`) and XCFramework bundling are configured in `shared/build.gradle.kts`
- The cinterop `.def` is wired to the Cactus headers at `../../cactus/cactus/ffi/`

### What's missing

**`WearableConnector.ios.kt`** — `connect()` and `startAudioStream()` are `TODO()` stubs. The Meta Wearables DAT SDK has an iOS counterpart but hasn't been integrated. Until this is implemented the audio pipeline does nothing on iOS.

**No iOS app module** — there is no Xcode project or SwiftUI/UIKit layer. To ship on iOS you would need to:
1. Run `./gradlew assembleSharedXCFramework` to produce `Shared.xcframework`
2. Create a new Xcode project and import the framework
3. Implement `WearableConnector.ios.kt` using the Meta Wearables iOS SDK
4. Build the equivalent of `MainActivity` / `MainViewModel` in SwiftUI

## Android

### Model not bundled
The Gemma 4 E2B model (~4.7 GB on disk) is not included in the APK and must be sideloaded into the app's internal ext4 storage. See [README.md](README.md) for the `adb push` + `run-as cp` workflow. There is no in-app download or progress UI.

### KV cache reset on every local turn
Cactus's prefix KV cache does not invalidate audio-bearing positions when the audio blob changes, so `VoiceAgent` calls `cactusReset()` before every local inference. This costs ~1 s of re-prefill per turn on this device. A narrower invalidation API (cache text prefix only, drop audio positions) would recover that cost.

### First-turn cold start is slow
Loading and prefilling Gemma 4 E2B takes ~15 s on the test device. Subsequent turns are ~4–6 s. We don't have a warm-up path yet.

### libcactus.so is not checked in
The native library must be built from the `../cactus` repo before building the APK. See README for build steps. CI will fail without this step.
