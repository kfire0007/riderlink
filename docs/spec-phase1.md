# Spec: RiderLink Offline Motorcycle Intercom — V1 (Phase 1)

## 1. Objective

**RiderLink** (working name) is an offline, Android-to-Android motorcycle intercom application for **Rider + OBR (backride/pillion passenger)** using ordinary wired earphones or Bluetooth headsets—eliminating the cost of dedicated helmet intercom hardware and avoiding cellular dead-zone dropouts.

- **Primary Users:** The Rider, the OBR, and (in Phase 2) close-range riding friends on Android.
- **Target Verification Hardware:**
  - **vivo X300 Pro** (Android 15/16, FuntouchOS / OriginOS)
  - **realme 5** (Android 10, Snapdragon 665, 3–4 GB RAM, realme UI)
- **Core Operating Principles:**
  - **100% Offline:** Uses only the phones' built-in local wireless radios (`LocalOnlyHotspot` + `WifiNetworkSpecifier`, with Manual Hotspot / Same-Wi-Fi LAN fallback). Zero internet, zero cellular data consumed, zero external servers or accounts.
  - **Safe Hands-Free Default:** Automatic full-duplex voice by default with a giant one-tap **Mute** button and adjustable mic sensitivity gate, plus a manual toggle to **Push-to-Talk (PTT)** (giant screen button + Volume/earphone button trigger).
  - **Navigation Coexistence:** Runs reliably in the background with the screen off or alongside Google Maps / Waze without blocking turn-by-turn voice prompts.

---

## 2. Tech Stack & Local Toolchain

| Layer | Choice | Notes / Rationale |
|---|---|---|
| **Language** | Kotlin | Native Android; direct access to audio, Wi-Fi, Bluetooth, and foreground service APIs with minimal overhead. |
| **SDK Targets** | `minSdk = 29` (Android 10), `compileSdk = 35`, `targetSdk = 35` | Supports realme 5 (Android 10) and vivo X300 Pro (Android 15/16); enables Android 10+ `WifiNetworkSpecifier`. |
| **Local Toolchain** | OpenJDK 17.0.21, Android SDK `android-35`, Build-Tools `35.0.0`, `adb` | Verified installed on the local Windows machine. |
| **UI Layer** | Native XML Views + ViewBinding (No Jetpack Compose) | Minimum APK size (~4–6 MB) and lowest RAM footprint on 3–4 GB RAM devices running Maps/Waze. |
| **QR Scanning** | 100% offline ZXing (`zxing-android-embedded` / `com.google.zxing:core`) | Zero Google Play Services dynamic module downloads; works in mountain dead zones on first run. |
| **Audio Pipeline** | `AudioRecord` / `AudioTrack` + `AcousticEchoCanceler` + `AutomaticGainControl` | 16 kHz mono 16-bit PCM, 20 ms frames, adaptive jitter buffer. |
| **Transport** | `WifiManager.startLocalOnlyHotspot()` + `WifiNetworkSpecifier` + `Network.bindSocket()` | Keeps cellular data free for Maps/Waze; Manual Hotspot / Same-Wi-Fi LAN shares the exact same UDP engine. |

---

## 3. Commands

All commands run from the repository root (`c:\Users\kenec\Documents\phone intercom`) on Windows PowerShell:

```powershell
# Run JVM unit tests (protocol, jitter buffer, noise gate, heartbeat)
.\gradlew.bat testDebugUnitTest

# Run Android Lint checks
.\gradlew.bat lintDebug

# Build debug APK
.\gradlew.bat assembleDebug

# Build release APK
.\gradlew.bat assembleRelease

# Install debug APK onto a connected Android device via ADB
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 4. Project Structure

```text
phone intercom/
├── docs/
│   └── spec-phase1.md                  # This Phase 1 specification
├── tasks/
│   ├── plan.md                         # Architecture & phased implementation plan
│   └── todo.md                         # Ordered, verifiable task checklist
├── app/
│   ├── build.gradle.kts                # minSdk 29, targetSdk 35, ViewBinding, ZXing
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Permissions & microphone/connectedDevice FGS
│       │   ├── res/                    # AMOLED dark glove-friendly XML layouts & drawables
│       │   └── java/com/riderlink/intercom/
│       │       ├── protocol/           # Binary UDP PacketHeader, PacketCodec, QR payload, SessionToken
│       │       ├── network/            # LocalHotspotHost, WifiSpecifierClient, LanFallback, UdpAudioTransport, HeartbeatMonitor
│       │       ├── audio/              # IntercomAudioEngine, AudioCodec, AudioProcessor, NoiseGateProcessor, JitterBuffer, InEarChimePlayer
│       │       ├── service/            # IntercomForegroundService, HeadsetRouteManager, PhoneCallInterruptionHandler, HardwareButtonController
│       │       ├── oem/                # BatteryOptimizationHelper (vivo / realme guided checklist)
│       │       └── ui/                 # HomeActivity, PairActivity, RideActivity, LiveDiagnosticsDrawer
│       └── test/
│           └── java/com/riderlink/intercom/
│               ├── protocol/           # PacketCodec & QR payload unit tests
│               ├── audio/              # NoiseGate & JitterBuffer unit tests
│               └── network/            # HeartbeatMonitor & loopback transport tests
```

---

## 5. Protocol, Audio & State Machine Contracts

### 5.1 QR Payload & Manual Session Code

- **QR Format:** `RL1|<ssid>|<passphrase>|<hostIp>|<udpPort>|<sessionToken>`
  - Example: `RL1|AndroidShare_4912|a8f9k2m1|192.168.49.1|49152|3847`
- **Manual LAN Fallback:** Displays the Host IP, UDP Port, and a **4-digit session token** so the OBR can join over a manual phone hotspot or shared Wi-Fi if `LocalOnlyHotspot` is restricted by an OEM ROM.

### 5.2 16-Byte Big-Endian UDP Packet Header (Phase-2 Ready)

Every UDP datagram starts with a fixed 16-byte big-endian header:

| Offset | Size | Field | Description |
|---|---|---|---|
| `0` | 2 B | `magic` | Fixed `0x524C` (`"RL"`) |
| `2` | 1 B | `version` | Protocol version (`1`) |
| `3` | 1 B | `packetType` | `0x01` AUDIO, `0x02` PING, `0x03` PONG, `0x04` CONTROL |
| `4` | 1 B | `codecId` | `0x01` PCM16_16K (`0x02` reserved for Opus in Phase 2) |
| `5` | 1 B | `senderId` | `0` = Host (Rider), `1` = OBR (`2..N` reserved for Phase 2 peers) |
| `6` | 2 B | `sessionToken` | Unsigned 16-bit per-session random token (rejects stray LAN packets) |
| `8` | 4 B | `sequenceNumber` | Unsigned 32-bit monotonic packet counter |
| `12` | 4 B | `timestampMs` | Unsigned 32-bit sender monotonic timestamp in ms |
| `16` | *N* B | `payload` | `640 B` for 20 ms 16 kHz mono 16-bit PCM (`656 B` total datagram — zero IP fragmentation) |

### 5.3 Audio Pipeline & Coexistence

- **Capture / Playback:** 16,000 Hz, Mono, `ENCODING_PCM_16BIT`, 20 ms frame size (320 samples / 640 bytes).
- **Pre-processing:** Hardware `AcousticEchoCanceler` (AEC) and `AutomaticGainControl` (AGC) attached to `AudioRecord.audioSessionId` when available, followed by a swappable `AudioProcessor` chain (`NoiseGateProcessor` with attack/release hold in Phase 1; ready for RNNoise/WebRTC NS later).
- **Codec Hook:** Swappable `AudioCodec` interface (`Pcm16Codec` in Phase 1; ready for Opus in Phase 2).
- **Adaptive Jitter Buffer:** Target playout delay 40–80 ms, hard cap 160 ms, packet-loss concealment (PLC) fading missing frames smoothly to silence to prevent clicks.
- **Navigation Coexistence:** `AudioTrack` uses `USAGE_VOICE_COMMUNICATION` and `CONTENT_TYPE_SPEECH`. RiderLink **never** requests exclusive `AUDIOFOCUS_GAIN` that would silence Google Maps or Waze turn-by-turn prompts.

### 5.4 Headset Routing & In-Ear Cues

- **Routing Priority:** Bluetooth HFP/SCO → Wired / USB-C headset → Phone speaker (with prominent UI warning banner).
- **OS API Handling:** Uses `AudioManager.setCommunicationDevice()` on Android 12+ (API 31+) and `startBluetoothSco()` / `isSpeakerphoneOn = false` on Android 10–11 (API 29–30), reacting live to `AudioDeviceCallback` hot-swaps mid-ride.
- **In-Ear Audio Chimes:** Distinct synthesized short tones played into the active headset for:
  - Connected
  - Link Lost
  - Reconnecting / Resumed
  - Mic Muted / Unmuted
  - PTT Pressed / Released
  - Peer On Cellular Call

### 5.5 Talking Modes & Hardware Triggers

- **Hands-Free (Default):** Full-duplex open mic gated by `NoiseGateProcessor` (adjustable sensitivity slider) + giant one-tap **Mute** button.
- **Push-to-Talk (Manual Toggle):** Giant hold-to-talk screen button + Volume/earphone media button trigger (with toggle-to-talk fallback for single-click Bluetooth earphones that do not emit hold/release events).

### 5.6 Interruptions, Heartbeat & OEM Survival

- **Foreground Service:** `IntercomForegroundService` declared with `foregroundServiceType="microphone|connectedDevice"`, holding `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_LOW_LATENCY`, with persistent notification controls (Mute / Disconnect).
- **Heartbeat & Silent Auto-Reconnect:** Sends `PING` every 1 s; marks link `RECONNECTING` after 3 s without `PONG`, plays "link lost" chime, silences output, and automatically resumes audio without tearing down the hotspot or re-scanning the QR code once packets return.
- **Cellular Call Coexistence:** `TelephonyCallback` (API 31+) / `PhoneStateListener` (API 29–30) pauses intercom capture/playback during incoming/active cellular calls, notifies the peer with a `CONTROL` packet + chime, and auto-resumes when the call ends.
- **OEM Battery Exemption:** Guided one-time checklist detecting vivo (FuntouchOS/OriginOS) and realme (realme UI/ColorOS) devices to direct the user to `Unrestricted` / `Allow background activity`.

---

## 6. UI & Live Diagnostics

Three focused, high-contrast **AMOLED dark** glove-friendly screens (large touch targets ≥ 56 dp):

1. **`HomeActivity`:** Role selection (**I'm Riding [Host]** / **I'm on the Back [Join]**), quick-status strip for headset & battery-exemption readiness, and one-tap reconnect to last manual LAN settings.
2. **`PairActivity`:**
   - **Host mode:** Pre-flight checks (Location toggle on Android 10–12, Wi-Fi), starts `LocalOnlyHotspot`, displays large scannable QR + Manual LAN fallback details.
   - **Join mode:** Offline ZXing QR camera view + Manual LAN IP/Port/4-digit token tab.
3. **`RideActivity`:** Clear `LINK` / `VOICE` / `HEADSET` status pills, giant **Mute** (Hands-Free) or **Hold to Talk** (PTT) primary control, Hands-Free ↔ PTT toggle, mic gate sensitivity slider, **End Ride** button, and a collapsible **Live Diagnostics drawer** showing:
   - RTT latency (`ms`)
   - Packet loss (`%`)
   - Active audio route (BT SCO / Wired / Speaker)
   - Active transport (`LocalOnlyHotspot` / `Manual LAN`)
   - `WakeLock`, `WifiLock`, and Battery Exemption status

---

## 7. Code Style

Hot-path audio and codec code avoids per-frame heap allocations and isolates implementations behind interfaces:

```kotlin
interface AudioProcessor {
    /** Processes a 20 ms 16-bit mono PCM frame in-place. Returns false if gated to silence. */
    fun process(samples: ShortArray, sampleCount: Int): Boolean
}

interface AudioCodec {
    val codecId: Byte
    fun encode(pcm: ShortArray, sampleCount: Int, outBytes: ByteArray): Int
    fun decode(inBytes: ByteArray, length: Int, outPcm: ShortArray): Int
}
```

- Kotlin standard formatting, explicit `ByteOrder.BIG_ENDIAN` for all network serialization, no blocking calls on the main/UI thread or audio capture thread.

---

## 8. Scope & Limitations Matrix (Phase 1)

| Limitation | Real-World Reality | How Phase 1 Tackles It |
|---|---|---|
| **1. Wireless range** | Phone Wi-Fi hotspot reaches ~20–50 m open air (less in pockets/traffic) — cannot match 500 m+ dedicated helmet intercoms | Phase 1 focuses on Rider + OBR (1–2 m, rock-solid); Phase 2 group mode targets close convoys + auto-reconnect |
| **2. Bluetooth mic quality** | Android Classic Bluetooth (HFP/SCO) drops audio to mono call quality (8–16 kHz) whenever the headset mic is open | Accepted physical BT limit; wired earphones keep full fidelity; UI clearly shows active audio route |
| **3. vivo / realme battery killers** | OriginOS / FuntouchOS & realme UI aggressively kill background apps after 5–15 min screen-off | `ForegroundService` (`microphone` + `connectedDevice`) + `WakeLock`/`WifiLock` + one-time in-app battery-exemption checklist |
| **4. Android hotspot quirks** | Android 10–12 requires Location toggle ON for `LocalOnlyHotspot`; some OEMs restrict or throttle SoftAP | Guided pre-flight check + built-in Manual Hotspot / Same-Wi-Fi LAN fallback sharing the same UDP engine |
| **5. Wind & engine noise** | Full ML noise cancellation is deferred past Phase 1 | Hardware `AcousticEchoCanceler` + `AutomaticGainControl` + adjustable mic threshold gate + giant Mute button, with a clean `AudioProcessor` hook ready for RNNoise/WebRTC NS later |
| **6. Phase 2 group upgrade** | Phase 1 is strictly 2-phone (Rider + OBR) | UDP packet header already carries `senderId`, `sessionToken`, `codecId`, and `seq` + swappable `AudioCodec` interface so Phase 2 star-topology group mixing plugs in without a protocol rewrite |
| **7. Embedded iOS Safari TLS** | Bundled self-signed `.p12` cert enables offline `getUserMedia()` on Safari (`:8443`) | Link confidentiality relies on `LocalOnlyHotspot` WPA2 passphrase; `/ws` enforces rate-limited 4-digit session tokens (5 failures → 10 s lockout) for Manual LAN defense |

---

## 9. Testing Strategy

- **Unit Tests (`app/src/test`):** JUnit 4/5 tests covering `PacketCodec` round-trip serialization & token/magic validation, QR payload encoding/decoding, `NoiseGateProcessor` gating/hold behavior, `JitterBuffer` reordering/PLC, and `HeartbeatMonitor` 3 s timeout/recovery transitions.
- **Two-Device Physical Verification (vivo X300 Pro + realme 5):**
  1. **Zero-Internet Link:** Enable Airplane Mode + Wi-Fi on both phones; pair via Host QR → Join ZXing scan in < 10 s; verify two-way voice.
  2. **Manual LAN Fallback:** Connect via manual hotspot/shared Wi-Fi using IP + 4-digit token; verify identical audio behavior.
  3. **Headset Hot-Swap:** Start on wired earphones, switch mid-ride to Bluetooth headset, verify automatic SCO routing and in-ear chimes.
  4. **Screen-Off & Maps Coexistence:** Lock both phones in pockets for 15+ min with Google Maps/Waze running on the Host; verify zero background kill and audible Maps prompts.
  5. **Dropout & Call Interruption:** Walk out of range until "link lost" chime plays, walk back to verify silent auto-resume without QR re-scan; trigger an incoming cellular call and verify clean pause/resume.

---

## 10. Boundaries

- **Always do:**
  - Run `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lintDebug` before considering any task complete.
  - Pre-allocate buffers in audio/UDP hot paths and bind client sockets to the `WifiNetworkSpecifier` `Network`.
  - Keep all user-facing strings and diagnostics accurate to real device state.
- **Ask first:**
  - Adding third-party libraries beyond AndroidX, Kotlin stdlib/coroutines, Material/AppCompat, and ZXing.
  - Changing the 16-byte UDP packet header layout or QR payload version (`RL1`).
  - Raising `minSdk` above 29.
- **Never do:**
  - Add internet permissions (`INTERNET` is only for local LAN sockets—never call remote servers, analytics, or cloud APIs).
  - Request exclusive audio focus that silences Google Maps / Waze.
  - Add iOS targets, user accounts, or Phase 2 group mixing into Phase 1 builds.

---

## 11. Success Criteria (Phase 1 Done)

- [ ] APK builds cleanly (`assembleDebug` & `assembleRelease`) and installs on both **vivo X300 Pro** (Android 17 verified) and **realme 5** (Android 10 — pending).
- [x] All JVM unit tests (`testDebugUnitTest`) and lint checks (`lintDebug`) pass with zero errors.
- [ ] Two phones pair 100% offline via `LocalOnlyHotspot` + QR scan in under 10 seconds, and via Manual LAN fallback.
- [ ] Full-duplex Hands-Free audio (with Mute & mic gate) and manual Push-to-Talk mode both work on wired and Bluetooth earphones with < 150 ms typical local latency.
- [ ] Intercom survives 15+ minutes screen-off alongside Google Maps/Waze after completing the OEM battery-exemption checklist.
- [ ] Out-of-range dropouts and incoming cellular calls pause cleanly with in-ear chimes and auto-resume without re-scanning QR.

---

## 12. Out of Scope (Deferred to Phase 2+)

- **Multi-bike Group Mode (3–6+ riders):** Star-topology host audio mixer and multi-peer roster (Phase 2).
- **Opus Compression:** Native/JNI Opus codec for lower multi-speaker bandwidth (Phase 2).
- **Advanced ML Wind/Engine Noise Cancellation:** RNNoise or WebRTC NS plugin via `AudioProcessor` (Phase 2+).
- **Native iOS App:** Permanently out of scope; however, RiderLink Android Host includes an embedded offline **HTTPS/WSS (`:8443`) Safari Web Client** (`iPhone OBR` tab) so an iPhone passenger can join with zero app install (subject to Safari's one-time local cert trust and keeping Safari open via Pocket Mode).
- **User Accounts, Cloud Backends, External Hardware/Dongles:** Out of scope.
