# The Ride is app-scoped; the foreground service only keeps it alive

The **Ride** module (pairing, the local network, **Peer** links, ending the ride) is a single app-scoped object created by the Application. Activities call it directly. `IntercomForegroundService` doesn't own it: an app-level watcher starts the service when the ride leaves Idle, and the service stops itself once the ride is Idle again. While running, the service holds only the foreground notification, wake/Wi-Fi locks, and the notification's "End ride" action, which calls the Ride.

We chose this over the usual Android shape (the bound service owns the session) because that shape made every Activity start the service, wait for the bind, and queue commands meanwhile. The queue was only ever added to one of the three join paths (`pendingJoinQr`), and a ride could be silently dropped by a tap that landed before the bind finished. Making the Ride app-scoped removes the bind race and the "start the service first" ordering rule outright, and lets the Ride be tested on the JVM without a service.

The voice side (audio engine, talk control, headset routing, phone-call handling, hardware buttons) is app-scoped for the same reason. Everything is built once by hand in `RiderLinkApp : Application`, with no DI framework. The service watches talk state only to keep its notification current.

## Consequences

- If Android kills the process, the ride is gone. The restarted service finds an Idle ride and stops (`START_NOT_STICKY`). Bringing a ride back belongs to Phase 2's silent auto-rejoin, not to service restart.
- Microphone foreground services must start while the app is visible. That holds because rides only start from an Activity's host or join command.
- Don't move ride state back into the service to "simplify binding". That brings back the race this decision removed.
