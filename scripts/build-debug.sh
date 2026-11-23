#!/usr/bin/env bash
# 可复现 debug 构建（需本机 ANDROID_HOME / NDK / Rust android target / cargo-ndk）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  export ANDROID_NDK_HOME
fi
: "${ANDROID_NDK_HOME:?请安装 NDK 并设置 ANDROID_NDK_HOME}"

cd "$ROOT"
# Android 15+ 部分设备要求 native LOAD 对齐 16KB
export RUSTFLAGS="${RUSTFLAGS:--C link-arg=-Wl,-z,max-page-size=16384}"
cargo test --workspace
# --platform 29：与 app minSdk 对齐，避免 native 符号要求高于 API29
cargo ndk -t arm64-v8a --platform 29 -o android/app/src/main/jniLibs build --release -p miaocast-core --features jni
cd android
./gradlew --no-daemon test assembleDebug
echo "APK: $ROOT/android/app/build/outputs/apk/standard/debug/app-standard-debug.apk"
