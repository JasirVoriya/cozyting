# Remaining Requirements (#1, #2, #3, #5) Design

Date: 2026-11-18

## 1. Unnamed non-MiPlay device filtering (#1)

- Passive LAN mDNS inspection (`miplay-cli discover --local 192.168.31.248`) confirmed that `B5A83339` is advertised on `_lyra-mdns._udp.local` (port 5353) without `_mi-connect` TXT `name` or `apps=[5]` (`advertises_miplay = 0`), whereas L17A (`192.168.31.34`) and LX06 (`192.168.31.234`) advertise on `_mi-connect._udp.local` with `apps=[5]` (`advertises_miplay = 1`).
- Fix `core/src/discovery.rs` so `parse_response` records `_service` in `Speaker.txt`, `merge_speaker` prefers `_mi-connect` / `advertises_miplay` entries over `_lyra-mdns` entries for the same IPv4 address, and `format_discover_line` emits 6 tab-separated columns: `ip\tname\tmodel\tadvertises_miplay\tservice\tapps`.
- In `CastRouteSelection`, parse the 6-column discovery row (`parseDiscoveredLine`):
  - Mark a row `castable = true` with `protocol = CastProtocol.MIPLAY` when `advertises_miplay == true` or `looksLikeTv(name, model)` on `_mi-connect`.
  - Mark non-MiPlay `_lyra-mdns` / unadvertised nodes `castable = false`, `protocol = null`, with an explicit `detail` reason (`未声明 MiPlay 投送能力（service=... apps=...）`). Never filter by hex-string name regex alone.
  - `filterVisibleDevices(devices, showAll)`: hide `castable == false` unadvertised nodes by default (`showAll = false`); expose a `"显示全部发现设备（含未声明 MiPlay 节点）"` toggle in `MainActivity` so the user can inspect all raw nodes while preventing false cast attempts via `RejectNonCastable`.
  - Log structured per-row discovery diagnostics (`service`, `apps`, `advertises_miplay`, `castable`, `visible_default`).

## 2. Local per-app mute boundary (#2) and track-change double-audio fix (#3)

- **Platform boundary (#2)**: On unrooted Android 10+ (API 29+) with ordinary third-party app permissions, `AudioPlaybackCapture` filters captured audio by UID, while `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)` acts on the global `STREAM_MUSIC` stream (affecting other media/video apps on the phone). There is no public API for third-party apps to mute only another arbitrary app's local speaker output.
- Expose an explicit `LocalOutputMute.Policy` (`GLOBAL_STREAM_MUTE` vs `KEEP_LOCAL_OUTPUT`) in `LocalOutputMute`, `CastService`, and `MainActivity` settings, with truthful UI copy explaining the tradeoff instead of using a prohibited global force-mute loop or making false promises.
- **Track-change silence survival (#3)**:
  - Root cause: `LocalOutputMute.onCaptureEnergy` previously treated any `verifySilentSamples` (4 samples ≈ 4s) of low RMS after `hadEnergyBeforeOrDuring == true` as "ROM mute killed capture" and permanently unmuted `STREAM_MUSIC` (`UNAVAILABLE_CAPTURE_SILENCED`). Inter-track silence or user pauses after normal playback therefore unmuted the phone speaker.
  - Fix: Bound the "mute killed capture" fallback to the initial verification window (`audibleSamples < verifyAudibleSamples`, default 6 audible samples). Once capture has delivered `verifyAudibleSamples` audible blocks while `STREAM_MUSIC` is muted, lock verification (`probeLocked = true`): subsequent inter-track or pause silence keeps `Status.ACTIVE_VERIFIED` and keeps `STREAM_MUSIC` muted.
  - If `volume.isMuted()` becomes `false` externally (system/hardware volume key) while `ACTIVE_VERIFIED`, transition to `Status.EXTERNAL_UNMUTED` and log `isStreamMute=false` without calling `volume.mute()` in a loop.

## 3. Build credential sanitization & reconnect stats continuity (#5)

- Remove hardcoded `storePassword` / `keyPassword` literals from `android/app/build.gradle.kts`; read acceptance and release signing credentials from environment variables (`MIPLAY_ACCEPTANCE_STORE_PASSWORD`, `MIPLAY_ACCEPTANCE_KEY_PASSWORD`, `MIPLAY_RELEASE_*`) or untracked `local.properties` / `keystore.properties`, falling back cleanly when absent. Add a contract test in `AcceptanceVariantContractTest` verifying no plaintext password literals exist in `build.gradle.kts`.
- In `core/src/session.rs`, preserve disconnected receivers awaiting backoff in `CastSession::stats()` (`merge_active_and_pending_stats`) with `phase = "reconnecting"` and updated `reconnects` / `error` fields so multi-speaker diagnostics and UI state remain continuous across transient network drops.
