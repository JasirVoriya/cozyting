# Device Latency Display Implementation Plan

> **For agentic workers:** Execute the steps in order with TDD.

**Goal:** Show per-selected-receiver latency inline in the device row, in integer milliseconds, with loading and hidden states matching casting status.

**Architecture:** Keep stats parsing and indicator-state selection in `LatencyDisplay`; create one indicator view per device row and update only those views in the existing one-second loop.

**Tech Stack:** Kotlin, Android Views, JUnit, Rust/Cargo, Android Gradle Plugin.

**Spec:** `docs/superpowers/specs/2026-11-18-device-latency-design.md`

## Global Constraints

- Show `1080 ms`, never the label `估计` in the device-row value.
- While casting, null latency for a matched receiver shows a spinner followed by `ms`.
- Stopped, failed, disconnected, deselected, missing receiver stats, and malformed values hide the indicator.
- Preserve receiver matching by IP/device ID; do not rebuild the device list during one-second updates.
- Keep the acceptance app installed; install only standard debug, and do not start a cast or adjust speaker volume.

## Review Focus

- A null value must not become `0 ms`; test the loading state.
- String, negative, and fractional latency must not render; test malformed values.
- Receiver error and deselected/stopped rows must hide; test hidden states.
- Refresh must update stable indicator views without rebuilding controls; verify on device.
- Preserve the acceptance package; inspect installed package IDs before install.

### Task 1: Receiver indicator state

**Files:** Modify `android/app/src/main/java/com/miplay/sender/LatencyDisplay.kt` and `MainActivity.kt`; test `android/app/src/test/java/com/miplay/sender/LatencyDisplayTest.kt`.

- [x] Add state mapping tests for value, loading, hidden, failed, and malformed input; run exact standard debug unit test and observe failure.
- [x] Implement strict IP-matched state mapping and inline row view updates.
- [x] Run all JVM tests, Rust workspace tests, native Android build, Android assemble and lint.
- [x] Install standard debug only, launch it via NAS ADB, and verify foreground startup and UI indicator state. Fixed cold-start `RECORD_AUDIO` request on API 29–32 (`NotificationPermissionCompat`), verified cold start directly into foreground UI with `RECORD_AUDIO` ungranted, and verified L17A UI silent-mode casting (`925 ms`) and stop cleanup (`state=0`, `volume=30`).
