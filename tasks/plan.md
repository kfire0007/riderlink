# Implementation Plan: RiderLink — Phase 1

Source spec: [spec-phase1.md](file:///c:/Users/kenec/Documents/phone%20intercom/docs/spec-phase1.md)

## Overview

We are building **RiderLink** (Phase 1), a lightweight, 100% offline native Android (Kotlin) intercom app for Rider + OBR using ordinary wired or Bluetooth earphones. The architecture centers on a low-overhead XML Views + ViewBinding UI, an offline ZXing QR pairing flow over `LocalOnlyHotspot` + `WifiNetworkSpecifier` (with Manual LAN fallback), a 16-byte Phase-2-ready UDP binary protocol carrying 16 kHz mono PCM frames, and a resilient `ForegroundService` hardened against vivo and realme background killers.

## Architecture Decisions

- **Native Kotlin + XML Views + ViewBinding (`minSdk = 29`, `targetSdk = 35`):** Maximizes snappiness and minimizes RAM/APK footprint on the Android 10 realme 5 alongside Google Maps/Waze (Q2, Q3, Q12).
- **`LocalOnlyHotspot` + Offline ZXing QR (`WifiNetworkSpecifier`) + Manual LAN Fallback:** Primary link needs zero internet and preserves cellular data for navigation; Manual LAN shares the same UDP engine if an OEM restricts `LocalOnlyHotspot` (Q1, Q6, Q12).
- **UDP + 16 kHz Mono PCM (20 ms frames) + Adaptive Jitter Buffer:** Keeps CPU/battery minimal and latency low without NDK/Opus complexity in Phase 1, while `AudioCodec` and `AudioProcessor` interfaces + 16-byte header (`senderId`, `sessionToken`, `codecId`, `seq`) keep Phase 2 group mixing & ML noise cancellation drop-in ready (Q7, Q11, Q13).
- **Hardware AEC/AGC + Adjustable Mic Gate + Dual Talk Modes:** Default automatic hands-free with giant Mute + manual Push-to-Talk toggle (screen + Volume/earphone button trigger with toggle fallback) (Q9).
- **Polite Navigation Coexistence + Auto Headset Routing + In-Ear Chimes:** Never steals exclusive audio focus from Maps/Waze; auto-routes BT SCO (API 29–30 & 31+) or wired earphones with hot-swap and speaker warnings (Q4, Q8).
- **ForegroundService + Locks + Guided OEM Checklist + Call Pause/Resume:** Survives vivo/realme screen-off killing, auto-reconnects after 3 s dropouts without re-scanning QR, and pauses/resumes cleanly around cellular calls (Q10, Q11).

## Dependency Graph

```text
Task 1: Android Project Scaffold & Manifest
   │
   ├── Task 2: UDP PacketCodec, SessionToken & QR Payload ──┐
   │                                                        │
   ├── Task 3: AudioCodec, NoiseGate & JitterBuffer ────────┼── Checkpoint 1
   │                                                        │
   ▼                                                        ▼
Task 4: UdpAudioTransport & HeartbeatMonitor ───────────────┐
   │                                                        │
   ├── Task 5: LocalHotspotHost, WifiSpecifier & LAN ───────┤
   │                                                        │
   └── Task 6: IntercomForegroundService & OEM Checklist ───┼── Checkpoint 2
                                                            │
                                                            ▼
Task 7: IntercomAudioEngine & InEarChimePlayer ─────────────┐
   │                                                        │
   ├── Task 8: HeadsetRouteManager & CallHandler ───────────┤
   │                                                        │
   └── Task 9: Hands-Free / PTT & HardwareButtonController ─┼── Checkpoint 3
                                                            │
                                                            ▼
Task 10: 3-Screen AMOLED Glove-Friendly UI ─────────────────┐
   │                                                        │
   └── Task 11: Live Diagnostics Drawer & 2-Device Road Test┴── Checkpoint 4
```

## Task List

### Phase 1: Foundation & Core Contracts
- [x] Task 1: Scaffold minimal Android Kotlin project (`minSdk 29`, `targetSdk 35`, ViewBinding, AMOLED theme)
- [x] Task 2: Implement binary UDP `PacketCodec`, `SessionToken`, and `QrPayload` + unit tests
- [x] Task 3: Implement `AudioCodec`, `NoiseGateProcessor`, and adaptive `JitterBuffer` + unit tests

### Checkpoint 1: Foundation Verified
- [x] `./gradlew.bat testDebugUnitTest` and `assembleDebug` pass cleanly

### Phase 2: Transport & Background Survival
- [x] Task 4: Implement `UdpAudioTransport` and `HeartbeatMonitor` (1 s ping, 3 s dropout recovery)
- [x] Task 5: Implement `LocalHotspotHost`, `WifiSpecifierClient`, and `LanFallbackConnector`
- [x] Task 6: Implement `IntercomForegroundService` (`WakeLock`/`WifiLock`) and vivo/realme `BatteryOptimizationHelper`

### Checkpoint 2: Transport & Service Verified
- [x] Unit/loopback tests pass; two devices discover, bind socket, and hold service alive

### Phase 3: Audio Engine, Headset Routing & Controls
- [x] Task 7: Implement `IntercomAudioEngine` (AEC/AGC, Maps coexistence) and `InEarChimePlayer`
- [x] Task 8: Implement `HeadsetRouteManager` (BT SCO / wired hot-swap) and `PhoneCallInterruptionHandler`
- [x] Task 9: Implement Hands-Free/PTT mode toggle and `HardwareButtonController`

### Checkpoint 3: Audio & Controls Verified
- [ ] End-to-end two-way audio works over wired and Bluetooth earphones with Maps/Waze active (pending Phase 4 UI & 2-device hardware test)

### Phase 4: Glove-Friendly UI & Road-Readiness Verification
- [x] Task 10: Build 3-screen AMOLED glove-friendly UI (`HomeActivity`, `PairActivity`, `RideActivity`)
- [x] Task 11: Add collapsible `LiveDiagnosticsDrawer` and run vivo X300 Pro + realme 5 verification (automated checks passed; 2-device road test ready)

### Checkpoint 4: Phase 1 Complete
- [x] `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleRelease` pass cleanly
- [ ] Physical two-device road test verified on vivo X300 Pro + realme 5

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **vivo / realme OEM background process killing** | High | `ForegroundService` (`microphone` + `connectedDevice`), `PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY`, plus guided in-app battery-exemption checklist. |
| **OEM `LocalOnlyHotspot` or `WifiNetworkSpecifier` quirks** | High | Guided Location pre-flight check (Android 10–12) + built-in Manual Hotspot / Same-Wi-Fi LAN fallback sharing the exact same UDP engine. |
| **API 29–30 vs API 31+ Bluetooth SCO & Telephony differences** | Medium | Explicit version branches: `startBluetoothSco` / `PhoneStateListener` on API 29–30 vs `setCommunicationDevice` / `TelephonyCallback` on API 31+, verified on both realme 5 (API 29) and vivo X300 Pro (API 35+). |
| **RAM pressure on realme 5 alongside Google Maps/Waze** | Medium | XML Views + ViewBinding (no Compose), pre-allocated audio buffers, no heavy background dependencies. |
| **Bluetooth earphone button behavior variance** | Low | Support hold-to-talk on Volume/screen PTT plus toggle-to-talk fallback for single-click headset media buttons. |

## Open Questions

None — all design decisions were resolved during the interview and 3 grilling rounds. Ready to proceed to Task 1 once the user approves the spec and plan.
