# Confirmed Statement of Intent — RiderLink Phase 2 (Group, Self-Healing & Music Sharing)

## Confirmed Intent

- **Outcome:** Fix the **1st-scan QR pairing bug** right away, document the project clearly on GitHub (`README.md`, progress & release notes), and expand RiderLink into a **100% offline 3–5 scooter group intercom (up to ~6–10 phones)** built on **Wi-Fi Star (Host mix-minus) + Opus compression**, **zero-tap hands-free auto-rejoin** (exact BSSID matching + Backup Host failover), live **Group Roster** (names, speaking/reconnecting/on-call badges, Host mute/kick), and **Host offline MP3 (+ optional experimental Spotify/YTM) music sharing** with auto-ducking and per-rider music volume.
- **User:** Everyday 2-up rides (Rider + OBR) plus occasional **3–5 scooter planned group rides** (up to 2 riders per scooter) using Android 10+ phones (+ iPhone Safari peer support), as well as riders discovering and installing the APK from GitHub.
- **Why now:** Replace ₱5k–₱10k per-rider dedicated Cardo/Sena helmet units using the phones and wireless/wired earphones everyone already owns, and provide clear GitHub documentation for public sharing.
- **Success:** 1st-attempt QR pairing works seamlessly every time; 6–10 phones talk and share MP3 music clearly over local Wi-Fi without disturbing Bluetooth earbuds; separated scooters **silently auto-rejoin with zero screen taps** when catching back up; Host loss recovers cleanly via Backup Host failover; and GitHub has a clear `README.md` + versioned release notes.
- **Constraint:** Strictly **100% offline** (zero mobile data, cloud servers, or accounts); **Bluetooth stays dedicated to wireless earbuds during active rides** (zero BT audio mesh or background BLE chatter while connected); **hands-free safety while moving** (no mid-ride route-switching Wi-Fi popups); **APK size is not a limit** — optimize for real-world stability and audio clarity (Opus) across Android 10+ devices.
- **Out of scope:** Dynamic STA+AP Wi-Fi multi-hop chaining (causes unsafe mid-ride Android "Connect" popups and requires flagship relay hardware), BLE audio mesh transport (fights wireless earbuds for HFP/SCO radio slots), heavy software ML noise cancellation (earbuds handle mic noise), online/cellular VoIP fallback, and native iOS app builds.

---

## Research & Architecture Notes (Pre-Spec Verification)

1. **Open-Source Benchmarks Reviewed:**
   - **`UstadMobile/Meshrabiya`:** Achieves Wi-Fi multi-hop via STA+AP concurrency and virtual IP routing, but requires `LGPL-3.0` snapshot dependencies and AP+STA hardware concurrency, and switching upstream Wi-Fi targets mid-ride on stock Android triggers user confirmation dialogs.
   - **`denizetkar/walkie-talkie-app` & `MotoIntercom`:** Uses symmetric BLE mesh + Opus, but BLE L2CAP CoC streams contend directly with Classic Bluetooth HFP/SCO wireless earbuds on the shared 2.4 GHz BT radio controller.
   - **`Pavanayi1/intercom`:** Uses Bluetooth RFCOMM + Wi-Fi Direct half-duplex (1 talker at a time), which does not scale to 6–10 full-duplex riders + shared music.
2. **Android Wi-Fi Zero-Tap Reconnect Rules:**
   - `WifiNetworkSpecifier` skips repeat user approval prompts **only** when matching the **exact SSID + exact BSSID (`MacAddress`)** (and/or Android 11+ `CompanionDeviceManager` association). Including the Host's SoftAP BSSID in Phase 2 QR payloads unlocks **true zero-tap hands-free auto-rejoin** when separated bikes return within range.
3. **Why Opus Is Essential for 6–10 Phones:**
   - Uncompressed 16 kHz PCM16 costs `256 kbps` per stream (`~2.5–4.5 Mbps` for 6–10 riders). Opus (`24–32 kbps` voice / `48 kbps` music) cuts Wi-Fi airtime by **5×–10×** and adds in-band Forward Error Correction (FEC) for moving-bike micro-dropouts.
