<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/logo.svg">
  <img alt="MiPlay Sender Logo" src="docs/assets/logo.svg" width="108" height="108">
</picture>

# MiPlay Sender (Pure LAN)

**Cross-Platform LAN Audio Capture & Casting (Android & iOS) · Zero-Cloud Pure LAN · Synchronized Multi-Speaker Engine**

<p>
  <a href="README.md">简体中文</a> · <strong>English</strong>
</p>

<p>
  <a href="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/JasirVoriya/miplay-sender/releases"><img alt="Version v0.9.4" src="https://img.shields.io/badge/Version-v0.9.4-1F2430?style=flat-square"></a>
  <a href="LICENSE"><img alt="License: AGPL-3.0-only" src="https://img.shields.io/badge/License-AGPL--3.0--only-FF5C6C?style=flat-square&logo=gnu&logoColor=white"></a>
  <img alt="Android 10+ (API 29-36)" src="https://img.shields.io/badge/Android-10%2B%20(API%2029--36)-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="iOS FFI Ready · SwiftUI Planned" src="https://img.shields.io/badge/iOS-FFI%20Ready%20%C2%B7%20SwiftUI-1F2430?style=flat-square&logo=apple&logoColor=white">
  <img alt="Pure LAN · Zero Cloud" src="https://img.shields.io/badge/Network-Pure%20LAN%20%C2%B7%20Zero%20Cloud-0EA5E9?style=flat-square">
  <img alt="Multi-Room Up to 63" src="https://img.shields.io/badge/Multi--Room-Max%2063%20Speakers-10B981?style=flat-square">
</p>

<p>
  <a href="https://jasirvoriya.github.io/miplay-sender/">Interactive Architecture</a> ·
  <a href="docs/COMPATIBILITY.md">Device Compatibility</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="SECURITY.md">Security</a>
</p>

</div>

---

## Highlights

| Area | Capability |
| --- | --- |
| **Strict Per-App Audio Isolation** | Uses Android `AudioPlaybackCapture` with a strict single-UID + `USAGE_MEDIA/GAME/UNKNOWN` allowlist (with iOS `ReplayKit` / `AudioToolbox` planned on the same L1 core). Verifies package visibility, same-user boundary, non-shared UID, and signing digest before starting (**fail-closed**) — never silently falls back to system-wide capture or microphone input. |
| **Multi-Speaker Sync Engine** | Pure Rust real-time engine (`std::thread` + bounded `sync_channel`, zero async runtime) with unified monotonic `EPOCH`, UDP 40B `TimerServer` clock master, `OffsetBarrier` backlog flush + 14 AU preroll alignment, supporting up to **63 concurrent LAN speakers**, dynamic join/leave, and exponential-backoff watchdog reconnect. |
| **Pure LAN & Dual Auth Isolation** | 100% local LAN communication with zero cloud APIs or account logins. Strictly isolates Legacy (`MD5 + HMAC-SHA1`) and Safety (`OPack + mutual HMAC + chained AES-128-CBC`) control authentication channels. |
| **Cross-Platform Clients + CLI** | Shared L1 Rust core (`engine` / `device_catalog`) and `design/` spec across Android (Kotlin + JNI `.so`, API 29–36, 16KB ELF page alignment), iOS (`ffi_c` + C11 header + `.xcframework` script ready, SwiftUI UI planned), and standalone `miplay-cli` tool (including read-only `inspect`). |

---

## Architecture Overview

MiPlay Sender follows a **3-layer cross-platform architecture**:

- **L1 Shared Core (`core/`)**: Platform-agnostic Rust protocol engine — mDNS discovery (`_mi-connect` + `_lyra-mdns`), TCP 8899 control & Legacy/Safety auth, reverse WFD RTSP, UDP clock master, ADTS → PES → MPEG-TS → RTP (`PT=33`) packetization, multi-room coordination, and unified UI state/latency normalization. Exposed via thin JNI (`core/src/ffi.rs`) for Android and C ABI (`core/src/ffi_c.rs` + `core/include/miplay_core.h`) for iOS SwiftUI.
- **L2 Platform System Layer**: OS-native capture, hardware AAC-LC (48kHz stereo, 256kbps) encoding, and foreground service lifecycle (`android/` implemented; iOS ReplayKit/AudioToolbox planned).
- **L3 Native UI Layer**: Native Kotlin UI on Android and planned SwiftUI on iOS, unified by shared design tokens and vector icons in `design/`.

> Explore modules, upstream/downstream dependencies, and the 6-stage runtime lifecycle in the zero-dependency **[Interactive Architecture Explorer](https://jasirvoriya.github.io/miplay-sender/)** (source: [docs/architecture.html](docs/architecture.html)).

![MiPlay Sender Cross-Platform 3-Layer Architecture](docs/assets/cross-platform-architecture.svg)

---

## Build & Test

**Prerequisites**: JDK 17+, Android SDK (`platforms;android-36`, `build-tools;36.0.0`, NDK), Rust ≥ 1.75, `aarch64-linux-android` target, and `cargo-ndk` (plus Xcode / `aarch64-apple-ios` targets when building the iOS XCFramework).

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
rustup target add aarch64-linux-android
cargo install cargo-ndk --locked

# Build 16KB-aligned JNI library + run Rust & Android tests + Debug APK
./scripts/build-debug.sh

# Build Release APK (signed via MIPLAY_RELEASE_* env vars or untracked keystore.properties)
./scripts/build-release.sh

# Build iOS static libraries and MiPlayCore.xcframework (macOS with Xcode)
./scripts/build-ios-xcframework.sh

# Run unit tests directly
cargo test --workspace
cd android && ./gradlew --no-daemon test lintStandardDebug
```

Prebuilt APKs and cross-platform `miplay-cli` binaries are published automatically on **[GitHub Releases](https://github.com/JasirVoriya/miplay-sender/releases)** for tagged versions.

---

## Known Boundaries & Honest Scope

We explicitly document what is **not** promised (see [docs/LIMITATIONS.md](docs/LIMITATIONS.md) and [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)):

- Third-party apps that opt out via `allowAudioPlaybackCapture="false"` cannot and will not be bypassed.
- Unprivileged Android APIs cannot mute a single third-party app locally without affecting `STREAM_MUSIC`; the app offers explicit global-mute vs. keep-local-output policies.
- OEM background freezing (e.g., HyperOS Greeze) varies by device and requires real-device validation.
- Certain speaker firmware variants (such as LX06 late-join multi-room) remain under investigation and are not claimed as fixed.

---

## License & Disclaimer

- **License**: Released under **[GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE)**.
- **Non-Affiliation Disclaimer**: This is an independent third-party open-source implementation created for local-network device interoperability and technical research. It is **not** affiliated with, authorized by, endorsed by, or associated with Xiaomi Corporation or any of its subsidiaries. All product names, protocols, and trademarks belong to their respective owners.
