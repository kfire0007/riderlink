# Changelog

All notable changes to **RiderLink** are documented here.

## [0.1.6] - 2026-11-27
### Fixed
- **1st-attempt QR pairing reliability:**
  - Queued scanned QR payloads in `PairActivity` (`pendingJoinQr`) when the camera returns before `IntercomForegroundService` finishes binding.
  - Added a `1.5 s` SoftAP beacon warm-up settle window in `LocalHotspotHost` before advertising the QR code, plus unexpected system hotspot-stop reporting.
  - Added automatic 1-time retry (`1.8 s` delay) inside `WifiSpecifierClient.onUnavailable()` to handle Android's first-attempt `WifiNetworkSpecifier` scan race.
- Added an on-screen clarity note on the Host QR screen explaining that Android's Quick Settings Hotspot toggle stays OFF during a private `LocalOnlyHotspot` session.

### Documentation
- Added [README.md](README.md) with install instructions, quick-start guide, range expectations, and Phase 2 roadmap.
- Added [docs/intent/phase2-group-intercom.md](docs/intent/phase2-group-intercom.md) and [docs/spec-phase2.md](docs/spec-phase2.md) detailing the Phase 2 Group, Self-Healing, Opus, and Music Sharing architecture.

## [0.1.5] - 2026-11-27
### Fixed
- Removed duplicated `webHostObserverJob` coroutine collectors in `IntercomForegroundService` and extracted `NotificationController` and `SessionLockManager`.
- Fixed `LocalWebBridgeServer` session-token rotation on mode switch, moved startup off the Main thread, added a non-blocking 4-frame TX ring buffer (`RiderLink-WebBridge-AudioTX`), bounded worker pool, `3.5 s` WebSocket read timeout, HTTP method/header validation, and `/ws` brute-force rate-limiting.
- Fixed iPhone Safari (`assets/web/index.html`) 48 kHz → 16 kHz resampling phase discontinuity, `blobUrl` leak, `403`/`429` reconnect loop, and added peer call-pause alerts.
- Fixed `IntercomAudioEngine` `AudioRecord`/`AudioTrack` concurrent release race during call pause / teardown.
- Fixed `HeartbeatMonitor` 32-bit `timestampMs` uptime wraparound, `UdpAudioTransport` peer pinning + zero-allocation `DatagramPacket` reuse, and Wi-Fi Settings fallback `boundNetwork` routing when cellular data is active.
- Synced `RideActivity` talk mode / wind gate controls on recreate and stopped the service cleanly on **End ride**.

## [0.1.4] - 2026-11-26
### Fixed
- Required Location ON on Android 10–12 for Join mode as well as Host mode.
- Added 1-tap **Copy password & open Wi-Fi** + **I connected on Wi-Fi, start voice** fallback after QR scan.
- Prioritized `192.168.x.1` SoftAP IPs and improved dark-on-amber PTT contrast.

## [0.1.3] - 2026-11-26
### Fixed
- Reset `JitterBuffer` talkspurt state cleanly after silence/PTT pauses so speech resumes with zero late drops.
- Separated audio and control sequence counters in `UdpAudioTransport` to prevent false packet-loss percentages.
- Lowered default wind gate threshold to `150` RMS and applied system bar window insets for Android 15+.

## [0.1.2] - 2026-11-26
### Fixed
- Bound `MediaSession.setCallback` to `Looper.getMainLooper()` and dispatched link-state transitions onto `Dispatchers.Main.immediate`.
- Filtered cellular interfaces (`rmnet`, `ccmni`, `tun`, etc.) from local IP selection.

## [0.1.1] - 2026-11-26
### Fixed
- Snapshotted pre-existing IPs before starting `LocalOnlyHotspot` and resolved SoftAP gateway IP from `LinkProperties` in `WifiSpecifierClient`.

## [0.1.0] - 2026-11-26
### Added
- Initial RiderLink Phase 1 offline Android intercom + embedded iPhone Safari HTTPS/WSS (`:8443`) bridge.
