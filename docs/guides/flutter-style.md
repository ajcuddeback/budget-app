# Flutter Style Guide

Formatting is `dart format`; linting is `flutter analyze` with `very_good_analysis` or the
Flutter lints package. Run them, don't debate them.

This guide covers what **this project** does. For Flutter and Dart itself, the official docs are
the source of truth — this file is the layer on top, the same relationship
`angular-style.md` has with the `angular-developer` skill.

## Non-negotiables

- **Money is never a `double`.** ADR-0006 applies to every client. Amounts arrive from the API as
  strings; keep them as strings for display, or use `decimal` for arithmetic. `double.parse` on
  an amount is a defect, not a shortcut.
- **Tokens live only in `flutter_secure_storage`** (Keychain / Keystore) — ADR-0018. Never
  `SharedPreferences`, never a file, never a log.
- **No secrets in the repo**, including no default server URL pointing anywhere real.
- **Null safety on, `dynamic` banned.** An API response becomes a typed model at the boundary.

## Structure

```
lib/
├── core/          config, http client, auth, storage, error handling, theme
├── shared/        reusable widgets, formatters, extensions
├── features/
│   └── <feature>/
│       ├── data/        api client, dtos, local dao
│       ├── domain/      models (freezed), pure logic
│       └── presentation/ screens, widgets, providers
└── main.dart
```

Feature-first, mirroring the backend's feature packages. A developer who knows where
`TransactionService` lives on the server should be able to guess where the mobile equivalent is.

## State

- **Riverpod.** Providers hold state; widgets read it.
- Widgets are as dumb as they can be. A widget calling Dio directly is in the wrong layer.
- Prefer `AsyncValue` for anything loaded — it forces you to handle loading and error states
  rather than forgetting them, which is where mobile apps usually look broken.

## Talking to the server

**The server belongs to the user, not to us** (ADR-0016). That changes the HTTP layer:

- Base URL is **user-supplied configuration**, entered at first run and changeable later.
- Handle, with a real message: unreachable host, self-signed certificate, LAN-only hostname,
  non-standard port, wrong URL, and a server that is running but a different version.
- **Never hard-code a server address**, and never fall back to one of ours. There is no "ours".
- Auth is a bearer token in an interceptor. On `401`, clear the token and route to login once —
  not a retry storm.
- Errors are RFC 7807 problem responses (`docs/guides/api-style.md`). Parse them and show the
  user something true. "Something went wrong" is the failure to do this.

## Offline

Phones lose signal; a budgeting app that is blank on a train is useless.

- Reads come from the Drift cache, refreshed in the background.
- Writes queue locally and reconcile when connectivity returns.
- **Label stale data** rather than hiding it. "Last updated 2 hours ago" beats an empty screen,
  and beats silently showing old numbers as if they were current — this is money.

## Authorization is the server's job

Hide a control a `VIEWER` cannot use, by all means — that is good UX. It is **not** a security
control. The API enforces the role (ADR-0017), and the client assuming otherwise is how a client
becomes the weak link.

## Accessibility

Semantics labels on every interactive widget. Respect the platform text scale — someone running
large type must still be able to read their balance, so no fixed-height rows that clip. Contrast
meets WCAG AA, same as the web client. Tap targets at least 48dp.

## Testing

- `flutter_test` for widgets and providers; `mocktail` for doubles; `integration_test` for the
  critical journeys (sign in, add a transaction, see the balance change).
- Test behaviour through the widget tree — find by semantics label and text, not by widget key
  used as a crutch.
- Money tests are mandatory wherever amounts are formatted or summed: scale, rounding, negative
  values, and very large values.
- Mock the HTTP layer, not your own providers.

## Platform

- iOS and Android from one codebase, but **look at both**. A layout that works on a Pixel can be
  wrong under a Dynamic Island.
- Platform channels only where Flutter genuinely cannot reach — biometrics, secure storage and
  sharing all have maintained packages.
