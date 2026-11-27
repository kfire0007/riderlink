# Group rides are a Host-mixed star: each Peer sends one stream up and gets its own mix-minus back

For group rides (Phase 2 M1) every **Peer** keeps exactly one **Link**, to the **Host**, and sends its voice only there. The Host keeps one jitter buffer per Peer in `GroupAudioMixer`. Every 20 ms it adds its own mic to every Peer's latest frame, plays the Peers to its own Rider, and sends each Peer that Peer's **Mix-minus** (everyone except them). A Peer's app doesn't change: it still plays one incoming stream.

We chose this over a full mesh, where every phone sends to every other phone. A mesh multiplies Wi-Fi airtime by the group size, which matters on a phone SoftAP with 6–10 phones. It also needs per-pair sockets and echo handling on every phone. The star costs one extra jitter-buffer hop for Peer-to-Peer speech (roughly 40–60 ms) and makes the Host's phone do the mixing. That load is small: idle Riders all hear the same mix, so with Opus there is one encode for them plus one per active speaker.

## Consequences

- **Identity:** each Peer introduces itself with `JOIN_REQ` (Rider name, flags, random per-install rider key) and gets a sender id (1..9) in `JOIN_ACK`. A rejoin with the same rider key from a new address is the same Link recovering, not a new Rider.
- **Legacy Peers:** Peers on the Phase 1 app never send `JOIN_REQ`. They're accepted as Legacy Peers named "Rider", using PCM, and an old Host simply ignores `JOIN_REQ`. Mixed versions keep working during the upgrade.
- **Group size:** the Host takes at most 9 Android Peers plus the Safari peer. A tenth Android Peer gets a refusing `JOIN_ACK` and sees "The group is full".
- **Everyone hears everyone:** Android and Safari Peers now hear each other through the Host, which wasn't possible before.
- **Opus is per Link,** agreed in `JOIN_ACK` when a Peer offers it (`FLAG_OPUS`). The mixer works on decoded PCM, so the Safari peer and Legacy Peers stay on PCM16.
- **Opus is Concentus (pure Java), not native libopus.** On a realme 5 an encode costs about 2.1 ms (complexity barely matters) and a decode about 0.3 ms. A Host therefore encodes the shared listener mix once per tick and gives only talking riders their own encoder (`IntercomAudioEngine.sendMix`). That's about 7 ms with two talkers and about 12 ms with four talking at once, inside the 20 ms frame. If a slower phone runs out of budget, swap native libopus in behind `AudioCodec`; the diagnostics drawer shows the Host's mix cost per tick.
- **The Host owns the Roster.** It sends `ROSTER_SYNC` to every Peer once a second and immediately on any change. Speaking badges come from the mixer: a Rider counts as speaking for 300 ms after their last frame.
- **Host mute lives in the mixer.** A muted Rider's frames are drained but added to nobody's mix, and the muted Rider learns about it from its own Roster entry. There's no separate `HOST_MUTE_PEER` message, because every Rider already gets the Roster.
- **Remove is `HOST_KICK_PEER`,** and the removed Rider stays out until the Ride ends. Its rider key is refused with a `REMOVED` `JOIN_ACK`, its address is ignored, and a removed Safari peer's token is rotated so its page can't reconnect.
- **Switching encoders isn't seamless:** a rider who starts or stops talking moves between the shared encoder and their own, so their decoder may glitch for a frame or two. In-band FEC is switched on in the encoder but not yet used on decode.
