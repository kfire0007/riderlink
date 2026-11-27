# RiderLink

**100% offline Android motorcycle intercom** built for everyday **Rider + OBR (pillion)** rides and upcoming **3–5 scooter group rides** — using the phones and ordinary wired or Bluetooth earphones you already own, without spending ₱5k–₱10k per rider on dedicated helmet units.

- **Zero internet / mobile data:** Works in mountain dead zones and provincial roads over local Wi-Fi (`LocalOnlyHotspot` + `WifiNetworkSpecifier` or Manual Wi-Fi).
- **Zero servers / accounts:** Peer-to-peer UDP voice with a 16-byte binary header.
- **Navigation-friendly:** Never steals exclusive audio focus, so **Google Maps / Waze** voice prompts remain audible.
- **iPhone OBR support:** Built-in offline HTTPS/WSS (`:8443`) Safari web client so an iPhone passenger can join with zero app install.

---

## Download & Install

- **Latest Beta APK:** [GitHub Releases](https://github.com/kfire0007/riderlink/releases)
- Download `RiderLink-v*-debug.apk` on your Android phone and allow **Install unknown apps** when prompted.

---

## Current Features (`v0.2.0`)

- **Group Rides (new in v0.2.0):** One rider hosts; up to 9 Android passengers plus an iPhone Safari passenger join. Everyone hears everyone except themselves (Host-mixed *mix-minus*), in **Opus** (about 15× less Wi-Fi airtime than v0.1). Riders on v0.1.x can still join.
- **Rider Names & Live Group Roster (new in v0.2.0):** Pick a rider name once; the ride screen lists everyone with *speaking*, *reconnecting*, *on a call* and *muted* badges, the same on every phone. The host can **mute** or **remove** a rider.
- **1-Scan Offline QR Pairing:** Rider creates a private local Wi-Fi link; passenger scans the QR code offline (with automatic 1st-attempt retry and 1-tap Wi-Fi Settings fallback).
- **Manual Wi-Fi / Hotspot Fallback:** Works on any phone hotspot or shared local Wi-Fi using Host IP, Port, and a 4-digit session code.
- **Hands-Free + Adjustable Wind Gate + Push-to-Talk (PTT):** Full-duplex open mic by default with one-tap Mute, adjustable RMS wind gate slider, or hold-to-talk PTT (screen button, Volume Down, or headset media button).
- **Automatic Headset Routing & In-Ear Chimes:** Priority routing for Bluetooth HFP/SCO → Wired/USB-C earphones → Phone speaker, with live hot-swap and synthesized status chimes (*Connected*, *Link Lost*, *Reconnected*, *Muted/Unmuted*, *PTT*, *Peer on Call*).
- **Cellular Call Coexistence:** Automatically pauses intercom during incoming/active phone calls, alerts the peer with an in-ear chime, and auto-resumes when the call ends.
- **Background Ride Survival:** Foreground service (`microphone|connectedDevice`) + `WakeLock` + low-latency `WifiLock` + OEM battery-exemption checklist (vivo/iQOO, realme/OPPO, Xiaomi, Samsung).
- **iPhone Safari OBR Bridge:** 2-step QR flow (`1. Join Wi-Fi` → `2. Open Safari`) with Pocket Mode screen dimmer.
- **Live Diagnostics Drawer:** Real-time RTT (`ms`), packet loss (`%`), audio route, transport peer, jitter buffer depth, lock state, and battery exemption status.

---

## Quick-Start Guide

### Android + Android
1. Tap the **Background battery** bar on the Home screen once on both phones and allow **Unrestricted** battery usage.
2. **Rider (Host):** Tap **I'm riding** → grant permissions (and turn **Location ON** on Android 10–12) → wait for the QR code.
3. **Passenger / Buddy (Join):** Tap **I'm on the back** → **Scan rider's QR** → tap **Connect** on Android's Wi-Fi prompt.
   - *Tip:* If Android's Wi-Fi popup keeps loading on a specific OEM phone, use the **Copy password & open Wi-Fi** fallback right below the Scan button, then tap **I connected on Wi-Fi, start voice**.

### Android Rider + iPhone Passenger (Safari)
1. **Rider (Host):** Switch to the **iPhone OBR** tab.
2. **iPhone:** Scan **1. Join Wi-Fi** with the iPhone Camera app → tap **Join Network**.
3. **Rider:** Switch to **2. Open Safari** on the Rider screen.
4. **iPhone:** Scan the second QR → tap **Show Details → visit this website** (one-time self-signed local cert) → tap **Join voice** and enable **Pocket mode**.

### Common Questions
- **Why is my phone's Quick Settings "Hotspot" toggle still OFF?**  
  RiderLink uses Android's private `LocalOnlyHotspot` API (zero mobile data sharing). Android intentionally keeps the system Personal Hotspot tile OFF — if RiderLink shows the QR code and `192.168.x.1`, the private link is active.
- **Why does Android 10–12 ask for Location?**  
  Android 10–12 requires the Location toggle to be ON for apps to start or join a `LocalOnlyHotspot`. RiderLink never tracks or transmits GPS location.

---

## Realistic Range

| Riding Setup | Realistic Range | Notes |
|---|---|---|
| **Rider + OBR (same bike)** | **0.5–1.5 m** | Rock-solid even with phones in pockets |
| **Bike-to-bike (handlebar mounts, open road)** | **~25–50 m** | Best with 2.4 GHz Manual Hotspot or clear line of sight |
| **Bike-to-bike (phones in pockets)** | **~10–25 m** | Body shielding reduces Wi-Fi reach |

Brief dropouts auto-recover silently once back in range without re-scanning QR codes.

---

## Roadmap (Phase 2)

See [docs/spec-phase2.md](docs/spec-phase2.md) and [docs/intent/phase2-group-intercom.md](docs/intent/phase2-group-intercom.md):
- **`v0.2.0` — 6–10 Rider Group Intercom + Opus + Live Roster:** ✅ Released. Host-mixed star (mix-minus), Opus, rider names, live roster, Host mute/remove, iPhone Safari slot. (Opus FEC recovery on decode is still to come.)
- **`v0.3.0` — Hands-Free Self-Healing Network:** Exact-BSSID + `CompanionDeviceManager` zero-tap silent auto-rejoin when separated scooters catch back up, plus Bluetooth-earbud-safe Backup Host failover.
- **`v0.4.0` — Group Music Sharing:** Host offline MP3 playback (plus optional experimental Spotify/YouTube Music `AudioPlaybackCapture`), automatic voice ducking (~20%), and per-rider local Music Mute & Volume slider.

---

## Building from Source

Requires JDK 17 and Android SDK (`compileSdk = 35`, `minSdk = 29`).

```powershell
# Unit tests
.\gradlew.bat testDebugUnitTest

# Android Lint
.\gradlew.bat lintDebug

# Build debug & release APKs
.\gradlew.bat assembleDebug assembleRelease
```
