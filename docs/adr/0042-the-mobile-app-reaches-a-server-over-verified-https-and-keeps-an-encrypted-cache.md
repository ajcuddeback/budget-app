# ADR-0042: The mobile app reaches a server only over verified HTTPS, and keeps an encrypted, read-only cache

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** Repository owner (online-only writes, full parity, both platforms); the security
  choices below follow CLAUDE.md's "take the secure option and note it"

## Context

Slice 15 builds the Flutter app (ADR-0019). Every household runs its own server (ADR-0016), so
the app cannot ship knowing an address, and the servers it meets are configured by people who may
not run TLS at all: the Compose stack listens on plain `http://localhost:8080` and leaves TLS to
whoever exposes it. The app also has to be useful on a train, which means keeping a copy of a
household's finances on a device that can be lost, stolen or backed up to a cloud.

Three questions follow. How does the app reach a server it was told about at runtime? What does
it keep on the phone, and how? What happens to an edit made with no signal?

## Decision

1. **HTTPS only, verified by the platform.** A release build refuses an `http://` address, in
   code and in the platform network configuration. There is no "trust this certificate anyway"
   button. A self-hoster with a private certificate authority installs that CA on the phone,
   which both platforms support. A debug build may use plain HTTP to `localhost`, `127.0.0.1` and
   the Android emulator's `10.0.2.2`, for development only.
2. **A public `GET /api/meta`** identifies a Budget Owl server and its API version before any
   credential is sent, and reveals nothing else (no build, host, runtime or counts).
3. **API calls do not follow redirects.** A `3xx` is an error, so a server cannot send the bearer
   token on to another host.
4. **The cache is encrypted and disposable.** Response bodies are cached in a SQLCipher-encrypted
   Drift database whose key lives in Keychain/Keystore beside the token. It is excluded from
   backups and wiped on sign-out, on a server change, and on any `401`. It holds what the server
   sent and never computes a figure of its own.
5. **Writes are online-only** (owner's decision). Offline, a write control says so; nothing is
   queued, so nothing can later conflict with a household member's edit or apply against figures
   that have moved.

## Alternatives considered

| Option | Why not |
|---|---|
| Allow `http://` on private LAN addresses | The bearer token and every balance would cross the Wi-Fi in clear text, and "LAN" includes the café the phone joins next. Both platforms already default against cleartext. |
| Trust-on-first-use certificate pinning, with an "accept" button | The button is clicked without reading, which makes it the attacker's button on hostile Wi-Fi. Installing a CA is one more setup step, done once, deliberately. |
| A plain SQLite cache, relying on the phone's disk encryption | Disk encryption protects a powered-off phone. It does not cover a backup, a rooted phone, or another app with a file-access bug. SQLCipher costs one native library. |
| No cache at all | A blank screen on every train journey. The flutter style guide already rules this out. |
| A write queue | Owner's decision. A queued payment that replays after a partner has changed the same bill, against a balance that has since moved, is exactly the class of bug a money app cannot have. It can be its own slice if wanted. |

## Consequences

- **A self-hoster must give their server an HTTPS address** before the app will connect. That is
  a real setup step, and the user guide gets a page for it ("Give your server an HTTPS address":
  a reverse proxy with a public certificate, or a private CA installed on the phone, or a
  tailnet's HTTPS name).
- **What enforces decision 1** (corrected 2026-11-26, after the phase-1 security audit). The
  "platform network configuration" governs the platform's own networking, not the Dart sockets
  the app uses: Android's `network_security_config.xml` and iOS's ATS do not, on their own, stop
  a `dart:io` connection. HTTPS is enforced by `ServerAddress.parse` (an `http` address is refused
  before anything is sent), the API client's origin guard (requests go only to the chosen origin),
  no redirects (decision 3), and, in a release build, the zone value `#flutter.io.allow_http:
  false`, which makes Flutter's engine refuse an `http://` connection at the socket unless the host
  is loopback (`lib/core/http/cleartext_policy.dart`, wrapped around `runApp`). The platform files
  stay, as declarations of the same policy.
- **A private CA on Android** (updated 2026-11-26, slice 15 phase 2). Android's user-installed
  CAs are trusted by `network_security_config.xml` but not, on their own, by Dart's TLS, which
  reads only the system store; for phase 1 a private CA worked on iOS and failed closed on
  Android. Now `MainActivity` reads the user-installed CAs (`AndroidCAStore`, aliases `user:…`)
  through a read-only platform channel (`lib/core/http/user_trust.dart`).
- **On Android, a server is pinned to the one user CA it was connected through** (2026-11-26,
  slice 15 phase 2, round 2; owner-facing, the secure option taken). **Android only**: on iOS
  `dart:io` uses the platform's default trust, so any CA the person has given full trust — an
  MDM-installed one included — can vouch for the server, and the notes below are not shown there.
  Closing that needs a platform channel that evaluates `SecTrust` with a pinned anchor (a known
  gap in mobile-app.md). Trusting every user CA would also
  trust a CA an employer's device management pushed, which can read the token and the balances
  through TLS inspection. So at connect time (`ServerCheck`, through `PhoneTrustAdapter.checkAndPin`)
  the server is checked against the system roots alone: if they verify it, nothing is stored and
  no user CA is ever added for it. If only a user CA does, each one is tried alone on top of the
  system roots, and the SHA-256 fingerprint of the one that verifies it is stored with the server
  address in secure storage. From then on the app's one transport trusts the system roots plus
  **that CA only**, while it is on the phone; the store is read again whenever the app returns
  to the foreground, so a CA removed in Settings stops being trusted for every connection made
  after, and the server then fails as "certificate not trusted". The pin lives and dies with the
  address: a sign-out keeps both (signing back in never re-pins), a change of server, a fresh
  install or an unreadable address clears both. No `badCertificateCallback`; the chain and the
  host name are verified as for any server; a store that cannot be read means the system roots
  alone. **What it does not cover:** a CA that is already intercepting at the moment the person
  connects is the one that verifies the server then, and is pinned; and a system CA the person
  disabled in Settings is still trusted, because Dart reads the system directory itself. Because
  the pin is trust-on-first-use, **it is shown** (round 3, S1; round 4, T1 and T3): when a user
  CA is pinned, the Connect screen's choices and More → Server say *"Trusted through a
  certificate authority installed on this phone:"* and then, on its own line, its subject CN (or
  O) with the start of its fingerprint — so a person who connected behind an inspection proxy
  can see it. The name is the CA maker's own text: display only, never inside our sentence or
  quotes, shown as a first-strong isolate, cleaned by an allow-list, never logged. When that CA
  is no longer on the phone, the screens say so and that connections will fail, instead of
  claiming trust. A server the system roots verify gets no sentence.
  Real-socket tests prove it with three per-run test CAs; **the channel itself needs checking on
  a device**, since no test here runs Android.
- **Writes wait for this launch's version check** (2026-11-26, slice 15 phase 2). Decision 2's
  `/api/meta` is checked at every launch, and until it has passed, the app sends no write — sign-in
  included — whatever else has reached the server; a check that fails (busy, timed out) keeps
  writes off and reads on, and is retried on the next request that reaches the server. A write
  the person has just asked for — sign-in, setup, accepting an invitation — waits for that
  check, or runs it, before anything is sent (`ensureVersionConfirmed`).
- `/api/meta` becomes the sixth public route, rate-limited and pinned to an exact body by a test.
- The cache adds a native dependency (SQLCipher) to both platforms' builds.
- Because the cache is wiped on any `401`, a revoked phone loses its data the next time it is
  online. Until then it is encrypted at rest, and readable only on the unlocked phone.
