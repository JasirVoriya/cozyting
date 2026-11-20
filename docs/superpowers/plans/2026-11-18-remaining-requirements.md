# Remaining Requirements Implementation Plan

> **For agentic workers:** Execute the steps in order with TDD.

**Goal:** Implement #1 discovery filtering & fallback toggle, #2/#3 local mute policy boundary & track-change silence survival, and #5 build credential sanitization & reconnect stats continuity.

**Spec:** `docs/superpowers/specs/2026-11-18-remaining-requirements-design.md`

### Task 1: Discovery service/apps metadata, merge priority, and UI filter (#1)

- [x] Add failing Rust tests in `core/src/discovery.rs` (`merge_speaker_prefers_mi_connect_over_lyra`, `format_discover_line_includes_service_and_apps`) and Kotlin tests in `CastRouteSelectionTest.kt` (`parseDiscoveredRow_*`).
- [x] Implement `_service` tagging, `merge_speaker`, and `format_discover_line` in `core/src/discovery.rs` and `core/src/ffi.rs`; implement `CastRouteSelection.parseDiscoveredLine` / `filterVisibleDevices` and the `"显示全部发现设备"` toggle + structured log in `MainActivity.kt`.

### Task 2: Local mute track-change lock, external-unmute detection, and policy toggle (#2, #3)

- [x] Add failing tests in `LocalOutputMuteTest.kt` (`trackChangeSilenceAfterLockedVerify_keepsStreamMutedAndActiveVerified`, `externalUnmuteDuringActiveCast_reportsExternalUnmutedWithoutForceRemuteLoop`, `keepLocalOutputPolicy_skipsStreamMusicMuteWithHonestPlatformBoundary`).
- [x] Implement `verifyAudibleSamples` probe lock, `Status.EXTERNAL_UNMUTED`, `Policy`, and `isStreamMute` transition logging in `LocalOutputMute.kt`, `CastService.kt`, and `MainActivity.kt`.

### Task 3: Build credential sanitization and reconnect stats continuity (#5)

- [x] Add failing tests in `AcceptanceVariantContractTest.kt` (`buildGradle_doesNotContainHardcodedSigningPasswords`) and `core/src/session.rs` (`pending_reconnecting_receiver_is_preserved_in_stats_view`).
- [x] Remove hardcoded passwords from `android/app/build.gradle.kts`, update `.gitignore`, and include pending reconnecting receivers in `core/src/session.rs` `stats()`.

### Task 4: Full build, lint, MAR-AL00 verification, and truthful docs

- [x] Run `cargo test`, `cargo ndk` native build, `./gradlew test assembleStandardDebug lintStandardDebug`, and `git diff --check`.
- [x] Install updated standard debug APK on MAR-AL00 via NAS ADB, verify discovery filtering (`B5A83339` hidden by default, shown as non-castable when toggle checked, L17A/LX06 shown, structured `discover_row` logs) and UI controls without touching LX06 or TV.
- [x] Update `docs/待修复需求.md`, `docs/修复日志.md`, `docs/LOCAL_MUTE.md`, and local `memory/STATE.md` / `memory/TODO.md`.
