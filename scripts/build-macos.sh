#!/usr/bin/env bash
# Build L1 Rust static library, assemble CozyTingCore.xcframework,
# run SwiftPM tests, and build the native macOS shell via XcodeGen + xcodebuild.
# Usage: ./scripts/build-macos.sh [--universal]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/apple/Frameworks"
XCFRAMEWORK="$OUT_DIR/CozyTingCore.xcframework"
TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"

UNIVERSAL=false
for arg in "$@"; do
  case "$arg" in
    --universal)
      UNIVERSAL=true
      ;;
    -h|--help)
      echo "Usage: $0 [--universal]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--universal]" >&2
      exit 1
      ;;
  esac
done

# Honor caller SDKROOT if already set; otherwise resolve portably via xcrun.
if [ -z "${SDKROOT:-}" ]; then
  SDKROOT="$(xcrun -sdk macosx --show-sdk-path)"
  export SDKROOT
fi

INSTALLED_TARGETS="$(rustup target list --installed)"
check_rust_target() {
  local target="$1"
  if ! printf '%s\n' "$INSTALLED_TARGETS" | awk -v t="$target" '$0 == t { found = 1 } END { exit !found }'; then
    echo "Missing Rust target: $target" >&2
    echo "Run: rustup target add $target" >&2
    exit 1
  fi
}

check_rust_target "aarch64-apple-darwin"
if [ "$UNIVERSAL" = true ]; then
  check_rust_target "x86_64-apple-darwin"
fi

cd "$ROOT"

echo "==> Running cargo test --workspace..."
CARGO_TARGET_DIR="$TARGET_DIR" cargo test --workspace

echo "==> Checking C11 header core/include/cozyting_core.h..."
clang -std=c11 -Wall -Wextra -Werror -fsyntax-only "$ROOT/core/include/cozyting_core.h"

echo "==> Building cozyting-core staticlib for aarch64-apple-darwin..."
CARGO_TARGET_DIR="$TARGET_DIR" cargo build --release -p cozyting-core --lib --target aarch64-apple-darwin
ARM64_LIB="$TARGET_DIR/aarch64-apple-darwin/release/libcozyting_core.a"

LIB_FOR_XCFRAMEWORK="$ARM64_LIB"
if [ "$UNIVERSAL" = true ]; then
  echo "==> Building cozyting-core staticlib for x86_64-apple-darwin..."
  CARGO_TARGET_DIR="$TARGET_DIR" cargo build --release -p cozyting-core --lib --target x86_64-apple-darwin
  X64_LIB="$TARGET_DIR/x86_64-apple-darwin/release/libcozyting_core.a"
  UNIVERSAL_DIR="$TARGET_DIR/universal-apple-darwin/release"
  mkdir -p "$UNIVERSAL_DIR"
  LIB_FOR_XCFRAMEWORK="$UNIVERSAL_DIR/libcozyting_core.a"
  echo "==> Creating universal staticlib via lipo..."
  lipo -create "$ARM64_LIB" "$X64_LIB" -output "$LIB_FOR_XCFRAMEWORK"
fi

echo "==> Assembling $XCFRAMEWORK..."
mkdir -p "$OUT_DIR"
rm -rf "$XCFRAMEWORK"
xcodebuild -create-xcframework \
  -library "$LIB_FOR_XCFRAMEWORK" \
  -headers "$ROOT/core/include" \
  -output "$XCFRAMEWORK"

echo "==> Running swift test in apple/CozyTingShared..."
swift test --package-path "$ROOT/apple/CozyTingShared"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "Missing xcodegen. Install via: brew install xcodegen" >&2
  exit 1
fi

echo "==> Generating apple/macOS/CozyTingMac.xcodeproj via xcodegen..."
xcodegen generate --spec "$ROOT/apple/macOS/project.yml" --project "$ROOT/apple/macOS"

echo "==> Building CozyTingMac (Release, ad-hoc signed)..."
xcodebuild \
  -project "$ROOT/apple/macOS/CozyTingMac.xcodeproj" \
  -scheme CozyTingMac \
  -configuration Release \
  -derivedDataPath "$ROOT/apple/macOS/DerivedData" \
  CODE_SIGN_IDENTITY="-" \
  CODE_SIGNING_ALLOWED=YES \
  build

echo "==> macOS build and tests passed."
