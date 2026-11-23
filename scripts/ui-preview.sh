#!/usr/bin/env bash
# 一键运行 Android JVM UI 截图预览（无需 APK / 真机 / 模拟器）
# 用法：
#   ./scripts/ui-preview.sh                 # 生成 6 张预览 PNG
#   ./scripts/ui-preview.sh --open          # 生成后在 macOS 自动打开深/浅并排总览图
#   ./scripts/ui-preview.sh --out /tmp/ui   # 指定截图输出目录
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="${ROOT_DIR}/android"
OUT_DIR=""
OPEN_PREVIEW=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --open)
      OPEN_PREVIEW=1
      shift
      ;;
    --out)
      OUT_DIR="${2:?--out requires a directory path}"
      shift 2
      ;;
    -h|--help)
      sed -n '2,6p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

GRADLE_ARGS=()
if [[ -n "${GRADLE_PROJECT_CACHE_DIR:-}" ]]; then
  GRADLE_ARGS+=(--project-cache-dir "${GRADLE_PROJECT_CACHE_DIR}")
fi
if [[ -n "${GRADLE_BUILD_DIR:-}" ]]; then
  GRADLE_ARGS+=("-PbuildDir=${GRADLE_BUILD_DIR}")
  DEFAULT_OUT="${GRADLE_BUILD_DIR}/ui-previews"
else
  DEFAULT_OUT="${ANDROID_DIR}/app/build/ui-previews"
fi

OUT_DIR="${OUT_DIR:-${DEFAULT_OUT}}"
GRADLE_ARGS+=("-Dmiaocast.uiPreviewDir=${OUT_DIR}")

(
  cd "${ANDROID_DIR}"
  ./gradlew "${GRADLE_ARGS[@]}" \
    :app:testStandardDebugUnitTest \
    --tests 'com.miaocast.app.ui.UiPreviewRenderTest' \
    --rerun
)

echo ""
echo "UI preview PNGs generated in: ${OUT_DIR}"
ls -lh "${OUT_DIR}"/*.png

if [[ "${OPEN_PREVIEW}" == "1" ]] && command -v open >/dev/null 2>&1; then
  open "${OUT_DIR}/00_overview_dark_vs_light.png"
fi
