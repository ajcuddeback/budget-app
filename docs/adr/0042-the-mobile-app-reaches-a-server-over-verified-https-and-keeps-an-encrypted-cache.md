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
- `/api/meta` becomes the sixth public route, rate-limited and pinned to an exact body by a test.
- The cache adds a native dependency (SQLCipher) to both platforms' builds.
- Because the cache is wiped on any `401`, a revoked phone loses its data the next time it is
  online. Until then it is encrypted at rest, and readable only on the unlocked phone.
