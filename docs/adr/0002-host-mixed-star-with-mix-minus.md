# Group rides are a Host-mixed star: each Peer sends one stream up and gets its own mix-minus back

For group rides (Phase 2 M1) every **Peer** keeps exactly one **Link**, to the **Host**, and sends its voice only there. The Host keeps one jitter buffer per Peer in `GroupAudioMixer`. Every 20 ms it adds its own mic to every Peer's latest frame, plays the Peers to its own Rider, and sends each Peer that Peer's **Mix-minus** (everyone except them). A Peer's app doesn't change: it still plays one incoming stream.

We chose this over a full mesh, where every phone sends to every other phone. A mesh multiplies Wi-Fi airtime by the group size, which matters on a phone SoftAP with 6–10 phones. It also needs per-pair sockets and echo handling on every phone. The star costs one extra jitter-buffer hop for Peer-to-Peer speech (roughly 40–60 ms) and makes the Host's phone do the mixing. That load is small: idle Riders all hear the same mix, so with Opus there is one encode for them plus one per active speaker.

## Consequences

- **Identity:** each Peer introduces itself with `JOIN_REQ` (Rider name, flags, random per-install rider key) and gets a sender id (1..9) in `JOIN_ACK`. A rejoin with the same rider key from a new address is the same Link recovering, not a new Rider.
- **Legacy Peers:** Peers on the Phase 1 app never send `JOIN_REQ`. They're accepted as Legacy Peers named "Rider", using PCM, and an old Host simply ignores `JOIN_REQ`. Mixed versions keep working during the upgrade.
- **Group size:** the Host takes at most 9 Android Peers plus the Safari peer. A tenth Android Peer gets a refusing `JOIN_ACK` and sees "The group is full".
- **Everyone hears everyone:** Android and Safari Peers now hear each other through the Host, which wasn't possible before.
- **Opus is per Link,** agreed in `JOIN_ACK`. The mixer works on decoded PCM, so the Safari peer stays on PCM16.
