#!/usr/bin/env bash
# 为 iOS（真机 arm64 + 模拟器 arm64）编译第 1 层 Rust 公共核心并打包 MiaoCastCore.xcframework。
# 依赖：macOS + Xcode (xcodebuild) + rustup iOS targets：
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/ios/Frameworks}"
XCFRAMEWORK="$OUT_DIR/MiaoCastCore.xcframework"
TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"

INSTALLED_TARGETS="$(rustup target list --installed)"
for t in aarch64-apple-ios aarch64-apple-ios-sim; do
  if ! printf '%s\n' "$INSTALLED_TARGETS" | awk -v target="$t" '$0 == target { found = 1 } END { exit !found }'; then
    echo "缺少 Rust target: $t"
    echo "请先运行: rustup target add aarch64-apple-ios aarch64-apple-ios-sim"
    exit 1
  fi
done

cd "$ROOT"
cargo test --workspace

echo "==> Building staticlib for aarch64-apple-ios..."
CARGO_TARGET_DIR="$TARGET_DIR" cargo build --release -p miaocast-core --lib --target aarch64-apple-ios

echo "==> Building staticlib for aarch64-apple-ios-sim..."
CARGO_TARGET_DIR="$TARGET_DIR" cargo build --release -p miaocast-core --lib --target aarch64-apple-ios-sim

mkdir -p "$OUT_DIR"
rm -rf "$XCFRAMEWORK"

xcodebuild -create-xcframework \
  -library "$TARGET_DIR/aarch64-apple-ios/release/libmiaocast_core.a" \
  -headers "$ROOT/core/include" \
  -library "$TARGET_DIR/aarch64-apple-ios-sim/release/libmiaocast_core.a" \
  -headers "$ROOT/core/include" \
  -output "$XCFRAMEWORK"

echo "XCFramework ready: $XCFRAMEWORK"
