# ADR-0019: Flutter for the mobile app

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** Repository owner

## Context

Budget Owl differentiates on mobile (ADR-0016) — it is the weak point of every incumbent. So the
mobile app is a first-class client, not a companion.

The constraints are unusual and they narrow the field:

- One developer, who has never shipped a mobile app.
- Comfortable in Java; Kotlin is a short hop, Swift is a new language.
- A Mac is available, so iOS is genuinely possible.
- Both platforms matter — half a self-hosted audience on iOS is not a market worth half-serving.

Native Kotlin plus native SwiftUI would be the highest-fidelity answer and is roughly two
projects. For a solo developer, "two codebases" usually resolves to "one codebase and a neglected
one", and the neglected one would be the platform we are trying to win on.

## Decision

**Flutter**, one codebase for iOS and Android.

The strongest evidence is that Immich — the reference point for this whole product direction — is
a self-hosted app with a well-regarded mobile client, and its mobile client is Flutter. That is
close to a controlled experiment for our exact situation: same audience, same self-hosted server
shape, same solo-to-small team scale.

Dart is a small language and unremarkable coming from Java. A budgeting app is forms, lists,
charts and offline sync — squarely what Flutter is good at, and well short of the platform-
integration depth where native pulls ahead.

Starting stack, to be confirmed against current versions when the app is scaffolded:

| Concern | Choice |
|---|---|
| State management | Riverpod |
| Navigation | go_router |
| HTTP | Dio, with an interceptor attaching the bearer token |
| Serialisation | `json_serializable` / `freezed` for immutable models |
| Local database | Drift (SQLite) for offline cache |
| Secure storage | `flutter_secure_storage` → Keychain / Keystore (ADR-0018) |
| Money | `decimal` package. **Never `double`** — ADR-0006 applies to every client |
| Testing | `flutter_test`, `mocktail`, `integration_test` |

**ADR-0006 is not negotiable on mobile.** Dart's `double` has exactly the IEEE-754 problem the
ADR exists to prevent, and amounts arrive from the API as strings specifically so no client is
tempted to parse them as numbers.

## Alternatives considered

| Option | Why not |
|---|---|
| Native Kotlin + native SwiftUI | Best platform fidelity, and about twice the work in two ecosystems for one developer who has shipped neither. iOS would lag, on the platform we most need to be good |
| Kotlin Multiplatform | Shares logic in a language the developer nearly knows, which is attractive. Rejected as a first mobile project: the build system and iOS interop are real complexity to absorb alongside learning mobile at all. Reconsider if Flutter disappoints |
| Capacitor around the Angular app | Cheapest by far and reuses everything. Rejected because "the mobile app is not very good" is the incumbents' weakness — shipping a wrapped web view concedes the one axis we are competing on |
| React Native | No React anywhere in this project, and we deliberately removed the last of it (ADR-0015) |

## Consequences

**Good:** one codebase, both platforms, from one developer. A large ecosystem for the boring parts
(charts, date pickers, biometrics). Proven at exactly this shape by Immich.

**Bad / costs:** a third language and toolchain alongside Angular and Spring. Flutter's own
idioms are not Angular's, so `docs/guides/` gains a fourth style guide rather than reusing one.
Deep platform integration (widgets, share sheets, background sync) is harder than native and some
of it needs platform channels. App Store and Play Store release processes are new ground, and are
not a build problem — they are a paperwork problem with review latency.

**Follow-ups:** `mobile/` joins the monorepo layout (ADR-0005 amended by implication — the
directory list grows, the decision does not change). A `flutter-ui` agent and a Flutter style
guide when the app is scaffolded. Mobile does not start until the API is real: slices 1–5 first.
