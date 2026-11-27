# RiderLink

Offline phone-to-phone voice intercom for riders sharing a bike or riding in a small group, linked over local Wi-Fi with no internet, servers or accounts.

## Riding

**Ride**:
The period during which phones are linked for voice, from choosing to host or join until it ends. Every linked phone takes part in the same **Ride**.
_Avoid_: Session (except in **Session token**), call, connection

**Rider**:
Any person taking part in a **Ride**, whatever their role.
_Avoid_: User, member

**Host**:
The role of the phone that forms the local network for a **Ride** and accepts **Peers**.
_Avoid_: Host rider, server, master

**Peer**:
The role of any phone or **Safari peer** that joins a **Host**'s **Ride**.
_Avoid_: Client, guest, joiner, OBR (as a role)

**Safari peer**:
A **Peer** that takes part through the iPhone Safari web page instead of the app.
_Avoid_: Web bridge (that names the mechanism, not the participant), iOS client

**OBR**:
The pillion passenger riding on the back of the **Host**'s bike. A word used in UI copy only; in the model they are a **Rider** whose phone is a **Peer**.
_Avoid_: Using it as a role name

**Hotspot**:
The private local network a **Host** forms for a **Ride**, with credentials carried in the **Pairing invitation**.
_Avoid_: LocalOnlyHotspot (that is the Android mechanism), SoftAP, personal hotspot

**Shared Wi-Fi**:
An existing network (a phone's personal hotspot or a LAN) that the **Host** and **Peers** are already on, used instead of a **Hotspot**.
_Avoid_: Manual LAN, LAN fallback

## Pairing

**Pairing invitation**:
What a **Host** offers so a **Peer** can join: network credentials (when the **Host** formed the network), host address, port and **Session token**. It is shown as a QR code, an iPhone Wi-Fi QR, a Safari URL, or typed in as manual fields.
_Avoid_: QR payload (one rendering of it), pairing code

**Session token**:
The secret number that identifies one **Ride** on the wire, so packets from other rides are ignored.
_Avoid_: Ride ID, key

**Session code**:
The 4-digit form of the **Session token** that a **Rider** types for a manual join.
_Avoid_: PIN, password

## Group rides

**Mix-minus**:
What one **Rider** hears in a group **Ride**: every other **Rider**, never their own voice. The **Host** builds one for each **Peer**.
_Avoid_: Mix (alone), conference audio

**Rider name**:
The name a **Rider** chose for themselves, shown to the rest of the group.
_Avoid_: Username, nickname, display name

**Roster**:
The list of every **Rider** in a group **Ride** and what each is doing: speaking, reconnecting, on a call, or muted by the **Host**. The **Host** keeps it and shares it with every **Peer**.
_Avoid_: Member list, participants

**Host mute**:
The **Host** stops relaying one **Rider**'s voice to the group. That **Rider** still hears everyone.
_Avoid_: Mute (alone, which is a **Rider** muting their own mic)

**Legacy Peer**:
A **Peer** still on the Phase 1 app. It joins a group **Ride** without introducing itself, and is shown simply as "Rider".
_Avoid_: Old client, v1 peer

## Link health

**Link**:
The voice connection between this phone and one other phone or **Safari peer** in the same **Ride**. A **Host** has one **Link** per **Peer**; a **Peer** has one **Link**, to its **Host**.
_Avoid_: Connection, transport, session

**Reconnecting**:
A **Peer**'s heartbeat has stopped while the local network is still up; it recovers by itself once packets flow again.
_Avoid_: Disconnected, dropped

**Network lost**:
The local network itself is gone (the **Host**'s hotspot was stopped, or a **Peer** left the Wi-Fi), so the **Ride**'s links cannot recover without rejoining the network.
_Avoid_: Reconnecting, link lost

**On call**:
A **Rider** whose phone is in a cellular call, so their voice is paused for the **Ride** until the call ends.
_Avoid_: Busy, paused

**Ending**:
The short phase in which a **Ride** says goodbye to its **Peers** and releases the network, before it is over.
_Avoid_: Stopping, disconnecting
