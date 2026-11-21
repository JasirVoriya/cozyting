#!/usr/bin/env bash
# 可复现 release 构建与校验（需本机 ANDROID_HOME / NDK / Rust android target / cargo-ndk）
# 正式签名通过环境变量 MIPLAY_RELEASE_STORE_FILE / MIPLAY_RELEASE_STORE_PASSWORD / MIPLAY_RELEASE_KEY_ALIAS / MIPLAY_RELEASE_KEY_PASSWORD
# 或未跟踪的 keystore.properties 提供；若未提供正式 keystore 则产出未签名 release APK 并跳过 apksigner 证书校验。
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
cargo ndk -t arm64-v8a --platform 29 -o android/app/src/main/jniLibs build --release -p miplay-sender-core --features jni
cd android
./gradlew --no-daemon test lintStandardRelease assembleStandardRelease

SIGNED_APK="$ROOT/android/app/build/outputs/apk/standard/release/app-standard-release.apk"
UNSIGNED_APK="$ROOT/android/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"

if [[ -f "$SIGNED_APK" ]]; then
  echo "Signed Release APK: $SIGNED_APK"
  "$ROOT/scripts/verify-acceptance-apk.sh" "$SIGNED_APK"
elif [[ -f "$UNSIGNED_APK" ]]; then
  echo "Unsigned Release APK (未配置 release keystore，跳过 apksigner 校验): $UNSIGNED_APK"
  BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
  "$BT/aapt" dump badging "$UNSIGNED_APK" | egrep 'package:|sdkVersion:|targetSdkVersion:|native-code:|application-label'
  "$BT/zipalign" -c -P 16 -v 4 "$UNSIGNED_APK"
else
  echo "Release APK 未找到" >&2
  exit 1
fi
