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
"$AAPT" dump badging "$APK" | grep -E 'package:|sdkVersion:|targetSdkVersion:|native-code:|application-label'

echo "== zipalign -c -P 16 -v 4 =="
"$ZIPALIGN" -c -P 16 -v 4 "$APK"

echo "== apksigner cert =="
APKSIGNER_OUT="$("$APKSIGNER" verify --print-certs "$APK")"
printf '%s\n' "$APKSIGNER_OUT" | sed -n '1,20p'

echo "== extract .so ELF LOAD align =="
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" "lib/arm64-v8a/*.so" -d "$TMP"
SO="$(find "$TMP" -name '*.so' | awk 'NR==1 { print }')"
READELF="$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | awk 'NR==1 { print }')"
if [[ -n "$SO" && -x "$READELF" ]]; then
  READELF_OUT="$("$READELF" -l "$SO")"
  printf '%s\n' "$READELF_OUT" | grep -E 'Type|LOAD|Align' | sed -n '1,30p'
  # 先落变量再用 awk 消费全量输入，避免 set -o pipefail 下 grep -q 提前关闭管道触发 SIGPIPE(141)；
  # 同时逐段校验所有 LOAD 段的 Align 均为 16KB（0x4000）的整数倍。
  if printf '%s\n' "$READELF_OUT" | awk '
    function hex2dec(s,    i, c, n, d) {
      sub(/^0[xX]/, "", s)
      if (length(s) == 0) return -1
      n = 0
      for (i = 1; i <= length(s); i++) {
        c = tolower(substr(s, i, 1))
        d = index("0123456789abcdef", c) - 1
        if (d < 0) return -1
        n = n * 16 + d
      }
      return n
    }
    $1 == "LOAD" {
      seen++
      val = hex2dec($NF)
      if (val < 16384 || (val % 16384) != 0) bad = 1
    }
    END { exit (seen > 0 && !bad) ? 0 : 1 }
  '; then
    echo "ELF_16KB_ALIGN=ok"
  else
    echo "ELF_16KB_ALIGN=check_failed"; exit 2
  fi
else
  echo "no .so or readelf"; exit 3
fi
echo "VERIFY_OK $APK"
