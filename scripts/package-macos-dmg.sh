#!/usr/bin/env bash
# Package CozyTingMac.app into a compressed macOS DMG + SHA256SUMS.txt.
# Supports optional Developer ID signing & Apple notarization via env vars,
# and cleanly produces an ad-hoc signed local DMG when no Developer ID is configured.
#
# Usage:
#   ./scripts/package-macos-dmg.sh [--universal] [--skip-build] [--version v0.9.12]
#
# Optional signing / notarization environment variables:
#   MACOS_CODESIGN_IDENTITY   e.g. "Developer ID Application: Your Name (TEAMID)"
#   APPLE_NOTARY_PROFILE      keychain profile created via `xcrun notarytool store-credentials`
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UNIVERSAL=false
SKIP_BUILD=false
VERSION_TAG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --universal)
      UNIVERSAL=true
      shift
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --version)
      VERSION_TAG="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--universal] [--skip-build] [--version vX.Y.Z]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

cd "$ROOT"

if [ "$SKIP_BUILD" = false ]; then
  if [ "$UNIVERSAL" = true ]; then
    ./scripts/build-macos.sh --universal
  else
    ./scripts/build-macos.sh
  fi
fi

APP_PATH="$ROOT/apple/macOS/DerivedData/Build/Products/Release/CozyTingMac.app"
if [ ! -d "$APP_PATH" ]; then
  echo "Expected app bundle not found at: $APP_PATH" >&2
  exit 1
fi

if [ -z "$VERSION_TAG" ]; then
  RAW_VER="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP_PATH/Contents/Info.plist" 2>/dev/null || echo '0.9.12')"
  VERSION_TAG="v${RAW_VER#v}"
fi

ARCH_LABEL="arm64"
if [ "$UNIVERSAL" = true ]; then
  ARCH_LABEL="universal"
fi

DIST_DIR="$ROOT/dist"
STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/cozyting-dmg-stage.XXXXXX")"
trap 'rm -rf "$STAGE_DIR"' EXIT

mkdir -p "$DIST_DIR"
cp -R "$APP_PATH" "$STAGE_DIR/CozyTing.app"
ln -s /Applications "$STAGE_DIR/Applications"

SIGNING_IDENTITY="${MACOS_CODESIGN_IDENTITY:-}"
if [ -n "$SIGNING_IDENTITY" ]; then
  echo "==> Signing CozyTing.app with Developer ID identity..."
  codesign --force --deep --options runtime --timestamp \
    --entitlements "$ROOT/apple/macOS/Resources/CozyTingMac.entitlements" \
    --sign "$SIGNING_IDENTITY" \
    "$STAGE_DIR/CozyTing.app"
  codesign --verify --deep --strict "$STAGE_DIR/CozyTing.app"
  DMG_NAME="cozyting-${VERSION_TAG}-macos-${ARCH_LABEL}.dmg"
else
  echo "==> No MACOS_CODESIGN_IDENTITY set; keeping local ad-hoc signature."
  DMG_NAME="cozyting-${VERSION_TAG}-macos-${ARCH_LABEL}-adhoc.dmg"
fi

DMG_PATH="$DIST_DIR/$DMG_NAME"
rm -f "$DMG_PATH"

echo "==> Creating DMG at $DMG_PATH..."
hdiutil create \
  -volname "CozyTing" \
  -srcfolder "$STAGE_DIR" \
  -ov \
  -format UDZO \
  "$DMG_PATH"

if [ -n "$SIGNING_IDENTITY" ]; then
  echo "==> Signing DMG..."
  codesign --force --timestamp --sign "$SIGNING_IDENTITY" "$DMG_PATH"
  if [ -n "${APPLE_NOTARY_PROFILE:-}" ]; then
    echo "==> Submitting DMG for Apple notarization..."
    xcrun notarytool submit "$DMG_PATH" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
    xcrun stapler staple "$DMG_PATH"
  else
    echo "==> Skipping notarization (APPLE_NOTARY_PROFILE not set)."
  fi
fi

echo "==> Generating SHA-256 checksum..."
(cd "$DIST_DIR" && shasum -a 256 "$DMG_NAME" > "${DMG_NAME}.sha256")
cat "$DIST_DIR/${DMG_NAME}.sha256"
echo "==> Done: $DMG_PATH"
