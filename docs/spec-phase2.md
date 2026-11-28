# Spec: RiderLink Phase 2 — Group Intercom, Hands-Free Self-Healing & Music Sharing

**Target Platform:** Android 10+ (`minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`), 100% offline.  
**Upstream Intent:** [docs/intent/phase2-group-intercom.md](intent/phase2-group-intercom.md)  
**Predecessor:** [docs/spec-phase1.md](spec-phase1.md) (Phase 1 `v0.1.x`)

---

## 1. Architecture Overview

RiderLink Phase 2 evolves the 2-phone UDP engine into a **Host-mixed Star (mix-minus) group intercom** designed for **3–5 scooters (up to 6–10 phones)** riding together, while keeping:
- **100% offline local Wi-Fi transport** (zero mobile data, zero servers, zero accounts)
- **Bluetooth dedicated to wireless earbuds** (zero BT audio mesh or background BLE chatter during rides)
- **Hands-free safety while moving** (zero mid-ride Wi-Fi approval popups via exact-BSSID auto-rejoin)

Phase 2 is delivered in **three vertical, independently testable milestones**:

| Milestone | Target | Headline Capabilities |
|---|---|---|
| **M1 (`v0.2.0`)** | 6–10 Rider Group & Opus | `GroupAudioMixer` (mix-minus), **Opus** codec (`CodecId.OPUS_24K`), Rider Display Name, Live Group Roster, Host mute/kick, iPhone Safari peer slot |
| **M2 (`v0.4.0`)** | Hands-Free Self-Healing | `RL2` QR with Host BSSID + 128-bit `groupKey`, exact-BSSID + `CompanionDeviceManager` silent auto-rejoin, Backup Host failover |
| **M3 (`v0.3.0`, shipped before M2)** | Group Music Sharing | Host in-app music player & streaming decoder, auto-ducking (~20% during speech), per-rider Off/Low/Medium/Full music level |

---

## 2. Milestone 1 (`v0.2.0`) — 6–10 Rider Group, Opus & Live Roster

### 2.1 Topology: Host-Mixed Star (Mix-Minus)
- Each connected peer sends **1 uplink audio stream** to the Host (`senderId` `1..9`) and receives **1 personalized downlink stream** (`senderId = 0`, Host mix-minus) containing **every *other* active speaker's voice** (+ shared music in M3) minus that peer's own voice (preventing echo).
- **Group Capacity:** Up to **10 total nodes** (Host `0` + peers `1..9`, matching typical modern Android SoftAP client caps), with **6 riders (Host + 5 peers)** as the recommended sweet spot for low airtime contention.
- **Host Placement Tip:** UI reminds group riders to place the Host phone on the **middle scooter** in a 3–5 scooter convoy to maximize front-to-back coverage (~60–100 m total span).

### 2.2 Opus Compression (`CodecId.OPUS = 0x02`)
- **Why Opus:** Shrinks per-stream bandwidth from `256 kbps` (PCM16) to **`24–32 kbps` (voice) / `48 kbps` (music-active mix)** — reducing 6–10 rider Wi-Fi airtime by **5×–10×** and enabling in-band **Forward Error Correction (FEC)** + native Opus PLC for moving-scooter micro-dropouts.
- **Integration:** Implement `OpusCodec : AudioCodec` behind our existing `AudioCodec` interface using native `libopus` (`Kopus` or bundled JNI), keeping `Pcm16Codec` (`0x01`) for backward compatibility and for **iPhone Safari (`:8443`)**, where the Host automatically transcodes between Opus (Android group) and 16 kHz PCM16 (Safari WebSocket).
- **Noise Cancellation:** Software ML noise cancellation (RNNoise / WebRTC NS) remains **out of scope**; earbuds' built-in hardware mic processing + existing `NoiseGateProcessor` handle wind gating.

### 2.3 Protocol Additions (Backward-Compatible 16-Byte Header)
The fixed 16-byte big-endian header in [PacketHeader.kt](../app/src/main/java/com/riderlink/intercom/protocol/PacketHeader.kt) remains unchanged (`magic = 0x524C`, `version = 1`, `senderId`, `sessionToken`, `sequenceNumber`, `timestampMs`).

New `ControlCode` values:
- `0x10 JOIN_REQ`: `[nameLen:1][utf8Name][flags:1]` (flags bit 0 = Android/failover-capable)
- `0x11 JOIN_ACK`: `[assignedSenderId:1][codecId:1][rosterVersion:1]`
- `0x12 ROSTER_SYNC`: Host broadcast (1 Hz or on change) listing `[count:1]` + entries `[senderId:1][statusFlags:1][nameLen:1][utf8Name]` (`statusFlags`: connected / reconnecting / speaking / on-call / host-muted)
- `0x13 HOST_MUTE_PEER`: `[targetSenderId:1][muted:1]`
- `0x14 HOST_KICK_PEER`: `[targetSenderId:1]`
- `0x15 MUSIC_PREF`: Peer → Host `[muted:1][volume0to100:1]` (used in M3)

### 2.4 Group Audio Mixer (`GroupAudioMixer`)
- Maintains per-peer `JitterBuffer` (`1..9`) + Host mic input (`0`).
- Every 20 ms tick:
  1. Pops 320-sample frame from each active, unmuted peer's `JitterBuffer` into pre-allocated `IntArray(320)` accumulators (zero heap allocations per tick).
  2. Computes global sum `totalSum[i] = hostMic[i] + sum(peer[k][i])`.
  3. For each peer `k`, computes `mixMinus[i] = clamp16(totalSum[i] - peer[k][i])` (with soft-knee limiter when >2 speakers overlap), encodes to Opus (or PCM16 for iPhone Safari), and sends via `UdpAudioTransport`.

### 2.5 UI Additions (`v0.2.0`)
- **Rider Display Name:** Prompted once on Home/Pair screen and persisted in `SharedPreferences` (default e.g. device model or `"Rider"`).
- **Live Group Roster Sheet / Card on `RideActivity`:**
  - Lists all connected riders (`You (Host)`, `GF`, `Bike 2`, etc.) with live **Speaking**, **Reconnecting**, **On Call**, and **Muted** indicators.
  - Host sees **Mute** and **Remove** actions per peer.

---

## 3. Milestone 2 (`v0.4.0`) — Hands-Free Self-Healing Network

### 3.1 Zero-Tap Exact-BSSID Auto-Rejoin
- **Problem Solved:** Android `WifiNetworkSpecifier` shows a user confirmation popup whenever connecting to an unfamiliar network, which is unsafe while riding. However, Android skips the prompt on subsequent connections when **SSID + exact BSSID (`MacAddress`)** match (and/or `CompanionDeviceManager` pre-approval is registered on Android 11+).
- **`RL2` QR Format:**
  - `RL2|<ssid>|<passphrase>|<hostIp>|<udpPort>|<sessionToken>|<bssid>|<groupKeyBase64>`
  - `QrPayload.parse()` continues supporting `RL1` for backward compatibility.
- **Behavior:**
  - Client resolves/records the Host's SoftAP BSSID on first connect (from `RL2` or `WifiInfo.bssid`), optionally associates via `CompanionDeviceManager` on Android 11+, and persists the approved tuple for the ride.
  - When a scooter separates past ~50 m and drops off Wi-Fi (`onLost`), `WifiSpecifierClient` enters **Silent Rejoin** mode (exponential backoff `2 s → 5 s`) using the exact `SSID + BSSID` so Android reconnects **with zero screen taps** the moment the scooter catches back up.

### 3.2 Bluetooth-Earbud-Safe Backup Host Failover
- **Zero-BT Rule During Normal Rides:** While connected to the Host, RiderLink performs **zero BLE advertising or scanning**, keeping Bluetooth 100% dedicated to wireless earbuds (HFP/SCO).
- **Pre-Paired Static Backup Hotspot (Zero-BLE Path, Recommended for Planned Rides):**
  - At the meetup, a designated Backup Host (e.g., Bike #2) can optionally share its static Android Hotspot QR once so peers pre-approve its BSSID. If the primary Host disappears for `>8 s`, Backup Host enables its hotspot and peers pivot silently with zero BLE needed.
- **Dynamic `LocalOnlyHotspot` Failover (Encrypted BLE Wake-on-Loss Fallback):**
  - During `JOIN_ACK`, Host distributes a random 128-bit AES-GCM `groupKey`.
  - If the Host is unreachable for `8 s` (staggered `+4 s` per deterministic lowest surviving Android `senderId`; iPhone peers skipped), the promoted Backup Host spins up a fresh `LocalOnlyHotspot` and broadcasts a short-lived **AES-GCM encrypted BLE control beacon** (`<=31 bytes`, `15 s` window) containing its new SSID/passphrase/port, then stops BLE immediately once peers join.
  - If the original Host recovers later, it rejoins as a normal peer (`senderId` reassigned) so the group never splits.

---

## 4. Milestone 3 (`v0.3.0`) — Group Music Sharing

> **As built** ([ADR 0003](adr/0003-group-music-through-the-host-mix.md)): music ships before M2. Any format MediaCodec plays (not only MP3), a windowed-sinc resampler, four shared Music levels (Off/Low/Medium/Full) instead of a 0–100% slider so listeners on one level share an Opus encode, Opus at 40 kbps while music plays, and the title in `ROSTER_SYNC`. §4.3 was dropped.

### 4.1 Primary Source: Host Offline MP3 Player
- Host picks local `.mp3` files / playlist folder via Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`, 100% offline, zero storage permissions needed, zero DRM blocks).
- `Mp3StreamDecoder` uses `MediaExtractor` + `MediaCodec` + linear resampler to emit 20 ms `16 kHz` frames into `GroupAudioMixer` (pre-allocated ring buffer).
- Host `RideActivity` gains compact **Play / Pause / Next Track** controls.

### 4.2 Auto-Ducking & Per-Rider Local Music Control
- **Auto-Ducking:** Whenever any speaker (Host or peer) has active voice energy above the gate threshold, `GroupAudioMixer` attenuates the music bed smoothly to **`~20%` (`-14 dB`)** and ramps back up over **`1000 ms`** after speech stops.
- **Per-Rider Controls (Zero Extra Wi-Fi Traffic):** Each rider has a **Music Mute** button and **Music Volume** slider (`0–100%`) in `RideActivity`. Changes send a tiny `MUSIC_PREF` control packet to the Host, which scales music in that rider's personalized mix-minus stream.

### 4.3 ~~Optional Experimental "Share App Audio" (Spotify / YouTube Music)~~ (dropped)
- Uses Android 10+ `AudioPlaybackCaptureConfiguration` (`MediaProjection` consent once per ride start).
- Marked explicitly as **Experimental** in UI because streaming apps with `ALLOW_CAPTURE_BY_NONE` or DRM output silence; offline MP3 remains the guaranteed primary source.

---

## 5. Verification & Hardware Test Plan (Phase 2)
- **Unit Tests (`testDebugUnitTest`):** `GroupAudioMixer` mix-minus isolation (peer `k` never hears self) & soft-clipping, `OpusCodec` round-trip & FEC packet loss recovery, `RL2` QR parsing, `ROSTER_SYNC` serialization, deterministic Backup Host election, and `Mp3StreamDecoder` / auto-ducking envelope math.
- **Field Tests (3–5 Scooters):**
  1. 6+ phones linked on one Host hotspot with Bluetooth earbuds active — verify zero BT earbud stutter and `<150 ms` latency.
  2. Intentional 100 m separation and catch-up — verify **zero-tap silent auto-rejoin**.
  3. Host phone turned off mid-ride — verify Backup Host failover & recovery.
  4. Offline MP3 playback with 3 riders talking — verify smooth ~20% ducking and per-rider music mute/volume.
