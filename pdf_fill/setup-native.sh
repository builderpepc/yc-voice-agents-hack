#!/usr/bin/env bash
# Builds libcactus.so from the local cactus repo and copies it into jniLibs.
# Run once before opening the project in Android Studio.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CACTUS_ROOT="$SCRIPT_DIR/../../cactus"
JNI_DIR="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"

echo "Building Cactus Android native library…"
cd "$CACTUS_ROOT"
source ./setup
cactus build --android

echo "Copying libcactus.so…"
mkdir -p "$JNI_DIR"
cp "$CACTUS_ROOT/android/libcactus.so" "$JNI_DIR/libcactus.so"

echo "Done. libcactus.so is ready in app/src/main/jniLibs/arm64-v8a/"
