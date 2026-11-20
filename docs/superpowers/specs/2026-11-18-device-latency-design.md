# Device latency display

Confirmed in chat on 2026-11-18: show each selected receiver's latency after its device name, in integer milliseconds (`1080 ms`), without the word “估计”. While casting, missing values show an indeterminate spinner followed by `ms`. Stopping hides the entire latency indicator. Remove the top summary, allow names to wrap, and never show IP suffix labels. The underlying protocol estimate is unchanged; this is not acoustic measurement.

Use the existing receiver IP / device ID match and update only the latency widgets every second, preserving row controls. Clear indicators for stopped, failed/disconnected, deselected, missing and malformed statistics. No periodic full-list rebuild. Existing native and notification diagnostics stay intact.

Approaches considered: duplicate top/row summary (does not satisfy request); rebuild rows on every refresh (disrupts sliders); update row indicators in place (selected).

Install Android SDK 36, build-tools 36.0.0, NDK 27.2.12479018, Rust Android target and cargo-ndk 3.5.4 on the NAS. Use NAS USB ADB only. Preserve the existing acceptance app; install standard debug only after verifying its package is absent. No television casting, audio volume changes, network changes or automatic speaker tests in this task. Verify formatting/state mapping via JVM tests, full existing suites, native/Android builds, lint, and a device startup/layout smoke check.
