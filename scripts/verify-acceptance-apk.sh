#!/usr/bin/env bash
# 验收 APK：minSdk/targetSdk/abi、zipalign 16KB、ELF LOAD 16KB、签名证书指纹
set -euo pipefail
APK="${1:?usage: $0 app.apk}"
ANDROID_HOME="${ANDROID_HOME:?}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
AAPT="$BT/aapt"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
echo "== badging =="
"$AAPT" dump badging "$APK" | egrep 'package:|sdkVersion:|targetSdkVersion:|native-code:|application-label'

echo "== zipalign -c -P 16 -v 4 =="
"$ZIPALIGN" -c -P 16 -v 4 "$APK"

echo "== apksigner cert =="
"$APKSIGNER" verify --print-certs "$APK" | head -20

echo "== extract .so ELF LOAD align =="
TMP=$(mktemp -d)
unzip -q -o "$APK" "lib/arm64-v8a/*.so" -d "$TMP"
SO=$(find "$TMP" -name '*.so' | head -1)
READELF="$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1)"
if [[ -n "$SO" && -x "$READELF" ]]; then
  "$READELF" -l "$SO" | egrep 'Type|LOAD|Align' | head -30
  if "$READELF" -l "$SO" | grep -q '0x4000'; then
    echo "ELF_16KB_ALIGN=ok"
  else
    echo "ELF_16KB_ALIGN=check_failed"; exit 2
  fi
else
  echo "no .so or readelf"; exit 3
fi
rm -rf "$TMP"
echo "VERIFY_OK $APK"
