# Tasks: RiderLink — Phase 1

Source plan: [plan.md](file:///c:/Users/kenec/Documents/phone%20intercom/tasks/plan.md) | Source spec: [spec-phase1.md](file:///c:/Users/kenec/Documents/phone%20intercom/docs/spec-phase1.md)

---

## Task 1: Scaffold minimal Android Kotlin project

**Description:** Create the Gradle Kotlin DSL Android project structure targeting `minSdk = 29` (Android 10) and `compileSdk = 35` / `targetSdk = 35` with ViewBinding enabled, offline ZXing dependencies, AMOLED dark theme colors/styles, and `AndroidManifest.xml` declaring all required Wi-Fi, Bluetooth, audio, foreground service, and phone-state permissions.

**Acceptance criteria:**
- [x] Gradle wrapper (`gradlew.bat`) and `app/build.gradle.kts` configure Kotlin, ViewBinding, `minSdk = 29`, `targetSdk = 35`, and offline ZXing.
- [x] `AndroidManifest.xml` declares `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION` (maxSdk 32) / `NEARBY_WIFI_DEVICES`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, and `READ_PHONE_STATE`.
- [x] Base AMOLED dark theme and placeholder `HomeActivity` launch cleanly.

**Verification:**
- [x] Build succeeds: `.\gradlew.bat assembleDebug`
- [x] Lint passes: `.\gradlew.bat lintDebug`

**Dependencies:** None

**Files likely touched:**
- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/themes.xml`

**Estimated scope:** Medium (5 files)

---

## Task 2: Binary UDP packet codec, session token, and QR payload

**Description:** Implement the 16-byte big-endian `PacketHeader` and `PacketCodec` (supporting `AUDIO`, `PING`, `PONG`, `CONTROL` packets, `senderId`, `sessionToken`, `codecId`, `sequenceNumber`, `timestampMs`), `SessionToken` generator/formatter, and `QrPayload` (`RL1|...`) encoder/parser with full JVM unit tests.

**Acceptance criteria:**
- [x] `PacketCodec` encodes/decodes 16-byte big-endian headers with zero extra allocations on pre-allocated `ByteArray` buffers.
- [x] Invalid magic bytes, unsupported version, truncated packets, or mismatched `sessionToken` are rejected safely.
- [x] `QrPayload` round-trips `RL1|<ssid>|<passphrase>|<hostIp>|<udpPort>|<sessionToken>` and validates malformed inputs.

**Verification:**
- [x] Tests pass: `.\gradlew.bat testDebugUnitTest --tests "com.riderlink.intercom.protocol.*"`

**Dependencies:** Task 1

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/protocol/PacketHeader.kt`
- `app/src/main/java/com/riderlink/intercom/protocol/PacketCodec.kt`
- `app/src/main/java/com/riderlink/intercom/protocol/QrPayload.kt`
- `app/src/test/java/com/riderlink/intercom/protocol/PacketCodecTest.kt`
- `app/src/test/java/com/riderlink/intercom/protocol/QrPayloadTest.kt`

**Estimated scope:** Medium (5 files)

---

## Task 3: AudioCodec, NoiseGateProcessor, and adaptive JitterBuffer

**Description:** Define the swappable `AudioCodec` and `AudioProcessor` interfaces, implement `Pcm16Codec` (16 kHz mono 20 ms frames), `NoiseGateProcessor` (RMS threshold with smooth attack/release hold), and adaptive `JitterBuffer` (40–80 ms target, 160 ms cap, fade-to-silence packet-loss concealment) with JVM unit tests.

**Acceptance criteria:**
- [x] `Pcm16Codec` encodes/decodes 320-sample 16-bit PCM frames to/from 640 bytes without per-frame heap allocation.
- [x] `NoiseGateProcessor` suppresses frames below threshold after hold time expires and opens cleanly on speech.
- [x] `JitterBuffer` reorders out-of-order sequences, drops stale packets beyond 160 ms, and outputs fade-to-silence PLC frames on gaps.

**Verification:**
- [x] Tests pass: `.\gradlew.bat testDebugUnitTest --tests "com.riderlink.intercom.audio.*"`

**Dependencies:** Task 1

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/audio/AudioContracts.kt`
- `app/src/main/java/com/riderlink/intercom/audio/Pcm16Codec.kt`
- `app/src/main/java/com/riderlink/intercom/audio/NoiseGateProcessor.kt`
- `app/src/main/java/com/riderlink/intercom/audio/JitterBuffer.kt`
- `app/src/test/java/com/riderlink/intercom/audio/AudioPipelineTest.kt`

**Estimated scope:** Medium (5 files)

---

## Checkpoint 1: After Tasks 1–3
- [x] All protocol and audio unit tests pass (`.\gradlew.bat testDebugUnitTest`)
- [x] `.\gradlew.bat assembleDebug` builds cleanly

---

## Task 4: UdpAudioTransport and HeartbeatMonitor

**Description:** Implement non-blocking/dedicated-thread `UdpAudioTransport` (supporting optional `Network.bindSocket()` for `WifiNetworkSpecifier`) and `HeartbeatMonitor` (1 s `PING`/`PONG`, RTT calculation, packet loss percentage tracking, and 3 s dropout detection transitioning to `RECONNECTING` without tearing down the session).

**Acceptance criteria:**
- [x] `UdpAudioTransport` sends/receives `PacketCodec` datagrams bound to a specific Android `Network` or default LAN interface.
- [x] `HeartbeatMonitor` computes rolling RTT (ms) and packet loss (%), fires `LINK_LOST` at 3 s without `PONG`, and fires `LINK_RESTORED` automatically when packets resume.

**Verification:**
- [x] Tests pass: `.\gradlew.bat testDebugUnitTest --tests "com.riderlink.intercom.network.*"`

**Dependencies:** Task 2

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/network/UdpAudioTransport.kt`
- `app/src/main/java/com/riderlink/intercom/network/HeartbeatMonitor.kt`
- `app/src/test/java/com/riderlink/intercom/network/HeartbeatMonitorTest.kt`

**Estimated scope:** Medium (3 files)

---

## Task 5: LocalHotspotHost, WifiSpecifierClient, and LanFallbackConnector

**Description:** Implement the three local radio connectors feeding `UdpAudioTransport`: `LocalHotspotHost` (wraps `WifiManager.startLocalOnlyHotspot()`), `WifiSpecifierClient` (connects via `ConnectivityManager.requestNetwork(WifiNetworkSpecifier)` and exposes the bound `Network`), and `LanFallbackConnector` (manual hotspot / same-Wi-Fi LAN connection using Host IP, Port, and 4-digit token).

**Acceptance criteria:**
- [x] `LocalHotspotHost` starts a local-only hotspot, resolves host IP/port + `QrPayload`, and cleanly releases the reservation on stop.
- [x] `WifiSpecifierClient` joins the hotspot SSID/passphrase without dropping cellular data and binds the UDP socket to that `Network`.
- [x] `LanFallbackConnector` connects directly via IP/port/token without `WifiNetworkSpecifier`.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Host starts QR on vivo X300 Pro; realme 5 joins via `WifiNetworkSpecifier` (and via Manual LAN).

**Dependencies:** Task 2, Task 4

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/network/ConnectionController.kt`
- `app/src/main/java/com/riderlink/intercom/network/LocalHotspotHost.kt`
- `app/src/main/java/com/riderlink/intercom/network/WifiSpecifierClient.kt`
- `app/src/main/java/com/riderlink/intercom/network/LanFallbackConnector.kt`

**Estimated scope:** Medium (4 files)

---

## Task 6: IntercomForegroundService and vivo/realme BatteryOptimizationHelper

**Description:** Implement `IntercomForegroundService` (`microphone` + `connectedDevice` types) managing `PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_LOW_LATENCY` lock, and persistent notification actions (Mute / End Ride), plus `BatteryOptimizationHelper` to detect vivo/realme OEMs and guide the user through battery-exemption settings.

**Acceptance criteria:**
- [x] `IntercomForegroundService` acquires and cleanly releases `WakeLock` and `WifiLock` and exposes live lock/service state for diagnostics.
- [x] Notification displays current link state and provides working Mute and Disconnect actions.
- [x] `BatteryOptimizationHelper` checks `PowerManager.isIgnoringBatteryOptimizations` and surfaces OEM-specific instructions/intents for vivo and realme.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Service stays active with screen off on realme 5 and vivo X300 Pro.

**Dependencies:** Task 4, Task 5

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/service/IntercomForegroundService.kt`
- `app/src/main/java/com/riderlink/intercom/oem/BatteryOptimizationHelper.kt`

**Estimated scope:** Small (2 files)

---

## Checkpoint 2: After Tasks 4–6
- [x] All unit tests pass (`.\gradlew.bat testDebugUnitTest`)
- [x] APK builds without warnings/errors
- [ ] Two devices connect offline, exchange heartbeats, and survive screen-off in foreground service

---

## Task 7: IntercomAudioEngine and InEarChimePlayer

**Description:** Implement `IntercomAudioEngine` driving `AudioRecord` and `AudioTrack` at 16 kHz mono PCM with hardware `AcousticEchoCanceler` and `AutomaticGainControl`, `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH` (polite coexistence with Maps/Waze), wired to `NoiseGateProcessor`, `Pcm16Codec`, and `JitterBuffer`, plus `InEarChimePlayer` for synthesized status tones.

**Acceptance criteria:**
- [x] Two-way real-time voice streams smoothly over `UdpAudioTransport` with hardware AEC/AGC enabled when supported.
- [x] Google Maps / Waze turn-by-turn prompts remain audible while RiderLink is active.
- [x] `InEarChimePlayer` plays distinct short cues for Connected, Link Lost, Reconnected, Muted/Unmuted, PTT, and Peer On Call.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Two-way voice verified between vivo X300 Pro and realme 5 alongside Google Maps navigation.

**Dependencies:** Task 3, Task 4, Task 6

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/audio/IntercomAudioEngine.kt`
- `app/src/main/java/com/riderlink/intercom/audio/InEarChimePlayer.kt`

**Estimated scope:** Small (2 files)

---

## Task 8: HeadsetRouteManager and PhoneCallInterruptionHandler

**Description:** Implement `HeadsetRouteManager` (auto-preferring BT HFP/SCO via `setCommunicationDevice` on API 31+ and `startBluetoothSco` on API 29–30, then wired/USB-C, then speaker with warning flag, handling `AudioDeviceCallback` hot-swaps) and `PhoneCallInterruptionHandler` (pausing/resuming intercom around cellular calls via `TelephonyCallback` / `PhoneStateListener`).

**Acceptance criteria:**
- [x] Connecting or disconnecting a Bluetooth headset or wired earphone mid-ride automatically updates audio routing and diagnostics status.
- [x] Falling back to phone speaker triggers a visible speaker-warning state.
- [x] Incoming/active cellular call pauses intercom audio, sends `CONTROL` notice to peer, and auto-resumes when the call ends.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Test BT/wired hot-swap and incoming cellular call on both Android 10 (realme 5) and Android 15/16 (vivo X300 Pro).

**Dependencies:** Task 6, Task 7

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/service/HeadsetRouteManager.kt`
- `app/src/main/java/com/riderlink/intercom/service/PhoneCallInterruptionHandler.kt`

**Estimated scope:** Small (2 files)

---

## Task 9: Hands-Free / Push-to-Talk mode toggle and HardwareButtonController

**Description:** Wire up talking-mode state (default Hands-Free with Mute + adjustable mic gate threshold; manual toggle to Push-to-Talk) and implement `HardwareButtonController` supporting Volume button hold-to-talk and Bluetooth/wired earphone media button triggers (with toggle-to-talk fallback for single-click headsets).

**Acceptance criteria:**
- [x] Hands-Free mode streams audio whenever unmuted and above the mic gate threshold.
- [x] Switching to Push-to-Talk mutes mic transmission until screen PTT button, Volume button, or headset media trigger is engaged.
- [x] Single-click headset button fallback cleanly toggles talk state in PTT mode and plays the PTT chime.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Verify Mute, mic gate slider, screen PTT, Volume key PTT, and earphone button trigger.

**Dependencies:** Task 7, Task 8

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/service/HardwareButtonController.kt`
- `app/src/main/java/com/riderlink/intercom/service/IntercomForegroundService.kt`

**Estimated scope:** Small (2 files)

---

## Checkpoint 3: After Tasks 7–9
- [x] `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lintDebug` pass
- [ ] End-to-end audio, headset hot-swap, call pause/resume, and Hands-Free/PTT controls verified on both phones

---

## Task 10: 3-Screen AMOLED glove-friendly UI

**Description:** Build `HomeActivity`, `PairActivity` (Host QR display + Join offline ZXing QR scanner + Manual LAN IP/Port/4-digit token tab + pre-flight permission/Location checks), and `RideActivity` (glove-friendly `LINK`/`VOICE`/`HEADSET` status pills, giant Mute / Hold-to-Talk button, Hands-Free ↔ PTT switch, mic sensitivity slider, speaker warning banner, OEM battery-exemption prompt).

**Acceptance criteria:**
- [x] All primary ride controls use ≥ 56 dp touch targets on an AMOLED black/high-contrast dark palette.
- [x] `PairActivity` handles runtime permissions (`RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`/`ACCESS_FINE_LOCATION`, `CAMERA`, `POST_NOTIFICATIONS`) and Android 10–12 Location-on check cleanly.
- [x] Full Host → QR Scan → Ride flow works offline without leaving the app.

**Verification:**
- [x] Build & lint pass: `.\gradlew.bat assembleDebug lintDebug`
- [ ] Manual check: Complete full pairing and ride UI walkthrough on both vivo X300 Pro and realme 5.

**Dependencies:** Task 5, Task 6, Task 9

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/ui/HomeActivity.kt`
- `app/src/main/java/com/riderlink/intercom/ui/PairActivity.kt`
- `app/src/main/java/com/riderlink/intercom/ui/RideActivity.kt`
- `app/src/main/res/layout/activity_home.xml`
- `app/src/main/res/layout/activity_pair.xml`
- `app/src/main/res/layout/activity_ride.xml`

**Estimated scope:** Medium (5–6 files; split layouts if needed)

---

## Task 11: Collapsible Live Diagnostics drawer and 2-device road-readiness verification

**Description:** Add the collapsible `LiveDiagnosticsDrawer` to `RideActivity` displaying live RTT (ms), packet loss (%), active headset route, active transport mode, `WakeLock`/`WifiLock` state, and battery-exemption status, then execute the full 5-scenario physical verification checklist across the vivo X300 Pro and realme 5.

**Acceptance criteria:**
- [x] Collapsible Live Diagnostics drawer updates in real time (1 Hz) without causing UI jank on the realme 5.
- [ ] All 5 physical verification scenarios from `docs/spec-phase1.md` Section 9 pass on vivo X300 Pro + realme 5.
- [x] `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat lintDebug`, and `.\gradlew.bat assembleRelease` all pass.

**Verification:**
- [x] Full check: `.\gradlew.bat testDebugUnitTest lintDebug assembleRelease`
- [ ] Manual check: All 5 scenarios in `docs/spec-phase1.md` Section 9 verified on hardware.

**Dependencies:** Task 10

**Files likely touched:**
- `app/src/main/java/com/riderlink/intercom/ui/LiveDiagnosticsDrawer.kt`
- `app/src/main/java/com/riderlink/intercom/ui/RideActivity.kt`
- `app/src/main/res/layout/view_diagnostics_drawer.xml`

**Estimated scope:** Small (3 files)

---

## Checkpoint 4: Phase 1 Complete
- [x] Automated Phase 1 build, lint, unit tests, and release APK (`assembleRelease`) verified
- [ ] Physical two-device road test verified on both vivo X300 Pro and realme 5
