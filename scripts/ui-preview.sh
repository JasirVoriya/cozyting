#!/usr/bin/env bash
# 一键运行 Android JVM UI 截图预览（无需 APK / 真机 / 模拟器）
# 用法：
#   ./scripts/ui-preview.sh                          # 默认生成中文预览 PNG
#   ./scripts/ui-preview.sh --locale en              # 生成英文预览 PNG（支持 zh | en | ja | all）
#   ./scripts/ui-preview.sh --locale all --export-docs  # 生成中/英/日三语截图并同步导出至 docs/assets/screenshots/
#   ./scripts/ui-preview.sh --open                   # 生成后在 macOS 自动打开深/浅并排总览图
#   ./scripts/ui-preview.sh --out /tmp/ui            # 指定截图输出目录
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="${ROOT_DIR}/android"
OUT_DIR=""
OPEN_PREVIEW=0
LOCALE_ARG="zh"
EXPORT_DOCS=0

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
    --locale)
      LOCALE_ARG="${2:?--locale requires zh|en|ja|all}"
      shift 2
      ;;
    --export-docs)
      EXPORT_DOCS=1
      shift
      ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

case "${LOCALE_ARG}" in
  zh|en|ja) LOCALES=("${LOCALE_ARG}") ;;
  all) LOCALES=("zh" "en" "ja") ;;
  *)
    echo "Unsupported --locale value: ${LOCALE_ARG} (expected zh|en|ja|all)" >&2
    exit 2
    ;;
esac

GRADLE_BASE_ARGS=()
if [[ -n "${GRADLE_PROJECT_CACHE_DIR:-}" ]]; then
  GRADLE_BASE_ARGS+=(--project-cache-dir "${GRADLE_PROJECT_CACHE_DIR}")
fi
if [[ -n "${GRADLE_BUILD_DIR:-}" ]]; then
  GRADLE_BASE_ARGS+=("-PbuildDir=${GRADLE_BUILD_DIR}")
  DEFAULT_BASE_OUT="${GRADLE_BUILD_DIR}/ui-previews"
else
  DEFAULT_BASE_OUT="${ANDROID_DIR}/app/build/ui-previews"
fi

BASE_OUT="${OUT_DIR:-${DEFAULT_BASE_OUT}}"

export_showcase_set() {
  local loc="$1"
  local src_dir="$2"
  local dst_dir="${ROOT_DIR}/docs/assets/screenshots"
  if [[ "${loc}" != "zh" ]]; then
    dst_dir="${dst_dir}/${loc}"
  fi
  mkdir -p "${dst_dir}"
  cp "${src_dir}/00_overview_dark_vs_light.png" "${dst_dir}/01-main-casting-dark-vs-light.png"
  cp "${src_dir}/15_latency_dialog_dark_vs_light.png" "${dst_dir}/02-latency-tiers-dark-vs-light.png"
  cp "${src_dir}/08_app_picker_dark_vs_light.png" "${dst_dir}/03-app-picker-dark-vs-light.png"
  cp "${src_dir}/11_wireless_guide_dark_vs_light.png" "${dst_dir}/04-wireless-guide-dark-vs-light.png"
  cp "${src_dir}/14_settings_top_dark_vs_light.png" "${dst_dir}/05-settings-core-dark-vs-light.png"
  cp "${src_dir}/12_settings_update_card_dark_vs_light.png" "${dst_dir}/06-settings-update-dark-vs-light.png"
  cp "${src_dir}/13_main_update_banner_dark_vs_light.png" "${dst_dir}/07-main-update-banner-dark-vs-light.png"
  cp "${src_dir}/16_update_dialog_dark_vs_light.png" "${dst_dir}/08-update-dialog-dark-vs-light.png"
  echo "[ui-preview] Exported 8 official ${loc} showcase PNGs -> ${dst_dir}"
}

LAST_OUT="${BASE_OUT}"
for loc in "${LOCALES[@]}"; do
  if [[ "${#LOCALES[@]}" -gt 1 ]]; then
    LOC_OUT="${BASE_OUT}/${loc}"
  else
    LOC_OUT="${BASE_OUT}"
  fi
  LAST_OUT="${LOC_OUT}"
  mkdir -p "${LOC_OUT}"

  GRADLE_ARGS=("-Dmiaocast.uiPreviewDir=${LOC_OUT}" "-Dmiaocast.uiPreviewLocale=${loc}")
  if [[ "${#GRADLE_BASE_ARGS[@]}" -gt 0 ]]; then
    GRADLE_ARGS=("${GRADLE_BASE_ARGS[@]}" "${GRADLE_ARGS[@]}")
  fi
  (
    cd "${ANDROID_DIR}"
    ./gradlew "${GRADLE_ARGS[@]}" \
      :app:testStandardDebugUnitTest \
      --tests 'com.miaocast.app.ui.UiPreviewRenderTest' \
      --rerun
  )

  echo ""
  echo "UI preview PNGs (${loc}) generated in: ${LOC_OUT}"
  ls -lh "${LOC_OUT}"/*.png

  if [[ "${EXPORT_DOCS}" == "1" ]]; then
    export_showcase_set "${loc}" "${LOC_OUT}"
  fi
done

if [[ "${OPEN_PREVIEW}" == "1" ]] && command -v open >/dev/null 2>&1; then
  open "${LAST_OUT}/00_overview_dark_vs_light.png"
fi
