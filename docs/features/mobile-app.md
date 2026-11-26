# Feature: Mobile app (Flutter)

- **Status:** In progress — slice 15
- **Owner:** repository owner
- **Last updated:** 2026-11-26
- **Related:** ADR-0006 (money), ADR-0016 (self-hosted), ADR-0017 (households and roles),
  ADR-0018 (bearer tokens), ADR-0019 (Flutter), ADR-0022 (currency), ADR-0023 (i18n),
  ADR-0042 (how the app reaches a server and what it keeps), every other feature doc

## Purpose

Budget Owl on the phone in your pocket, talking to **your** server. Everything the web app does,
the app does — log a coffee at the till, file the week's charges on the bus, do Sunday's check-in
on the sofa — with the same household, the same figures and the same rules, because it is the
same API. When there is no signal, the app still shows what it last knew, clearly labelled as
such, and says plainly that changes have to wait.

## User stories

- As a member, I want to point the app at my household's server once, sign in, and stay signed in
  until I sign out or revoke the phone from another device.
- As a member, I want every screen I have on the web — transactions, accounts, bills, debt plan,
  goals, envelopes, categories, insights, check-in, import, household, devices, preferences, and
  the admin console for an administrator — laid out for a phone.
- As a member on a train, I want to read my balances and this month's bills from the last sync,
  told how old they are, and be told — not silently queued — that an edit needs a connection.
- As a member whose phone was stolen, I want revoking it from the web to end its access at once,
  and the data it cached to be unreadable without the phone's unlock.
- As a `VIEWER`, I want to read everything and see no controls I cannot use.

## Decisions (owner, 2026-11-26)

1. **Full parity in this slice**, not a subset: every web screen has a mobile screen, built from
   the design's 390-wide artboards.
2. **Both platforms.** Android is built and tested here, including a debug APK. iOS shares the
   code and is built on the owner's Mac; nothing in the app may depend on Android-only behaviour.
3. **Online-only writes.** Reads come from an encrypted local cache refreshed in the background;
   a write needs a connection. Offline, a write control says *"You're offline — this needs a
   connection"* instead of queueing. A write queue is a later slice, if ever.

## Rules and behavior

### Connecting to a server (ADR-0042)

- The first screen asks for **the server address**. There is no default and no suggestion: the
  app ships knowing no server (ADR-0016, flutter-style "There is no ours").
- The address is normalised (`https://` assumed when no scheme is typed; trailing slash and path
  removed; a path other than `/` refused, since the API lives at `/api`) and checked with
  `GET /api/meta` before the sign-in screen is shown.
- **HTTPS only.** An `http://` address is refused with an explanation and a link to the user
  guide's "Give your server an HTTPS address" page. The only exception is a **debug build**
  talking to `localhost`, `127.0.0.1` or `10.0.2.2` (the Android emulator's host), for
  development; a release build has no such exception and the platform configs enforce it too
  (Android `network_security_config` with `cleartextTrafficPermitted="false"`, iOS ATS defaults).
- **Certificates are verified by the platform.** There is no "accept this certificate anyway"
  button. A self-hosted server with its own certificate authority works when that CA is installed
  on the phone (Android trusts user-installed CAs for this app through its network security
  config; iOS trusts a CA profile the user has enabled). The error screen distinguishes, in its
  own words: host not found, connection refused or timed out, certificate not trusted,
  certificate for a different name, "this is not a Budget Owl server", and "this server's API is
  version N, this app needs M".
- Changing the server signs the user out first (revokes the token on the old server if it is
  reachable, then wipes local state — below).

### `GET /api/meta` (new, public)

The one new API route. Public, so the app can identify a server before anyone signs in.

```json
{ "app": "budget-owl", "apiVersion": 1, "minimumClientApiVersion": 1 }
```

- No build number, commit, hostname, Java version or user count — nothing that fingerprints an
  instance beyond "this is Budget Owl, API 1".
- Rate-limited like the other public routes; `Cache-Control: no-store`.
- `apiVersion` is a single integer bumped when a change would break an older client.

### Signing in, staying in, signing out

- Sign-in is `POST /api/auth/token` with email, password and a **device label** the app derives
  from the platform (*"Pixel 8 · Android"*, *"iPhone · iOS"*) and the user may edit. The token is
  written to **`flutter_secure_storage` only** (Keychain with `first_unlock_this_device`,
  Android Keystore-backed encrypted preferences). It is never logged, never in a crash report,
  never in a URL, never in Drift.
- First-run instance setup (`/setup`) and accepting an invitation (`/join/:token`) are available
  on the connect screen: *"Set up a new server"* (only when `GET /api/setup/status` says the
  instance has no user yet) and *"I have an invitation link"* (the user pastes the link; the app
  takes only the token from it and refuses a link for a different host than the one entered).
- Every request carries `Authorization: Bearer`. **Any `401` clears the token, wipes local
  state and routes to sign-in once**, with *"Your session ended. Sign in again."* — no retry, no
  refresh loop. A revoked phone therefore loses its cached data the next time it talks to the
  server.
- **Sign out** revokes the token server-side (`POST /api/auth/logout`, which ends the token the
  request carries) and wipes local state whether or not the server answered.
- `GET /api/meta` joins the short list of public routes in `authentication-and-households.md`;
  that list and `AuthorizationIT`'s public-route set are updated with it.
- *Wipe local state* means: the token, the Drift database file and its key, the cached user and
  household, and any in-memory state. Only the server address survives a sign-out (so signing
  back in is one screen), and it is removed when the user changes server.

### The cache

- Every screen reads through a repository that returns the **last cached copy first**, then
  refreshes from the API; the cached copy is replaced only by a successful response.
- The cache is a Drift (SQLite) database **encrypted with SQLCipher**, keyed by 32 random bytes
  generated on first sign-in and held in secure storage beside the token. It is excluded from
  backups (Android `allowBackup="false"` and `dataExtractionRules`; iOS
  `isExcludedFromBackup`).
- The cache stores **API response bodies as received**, keyed by route and household, with the
  time fetched. It is not a second data model: it never computes a figure the server did not
  send.
- **Stale data is labelled.** When the shown copy is older than one minute and the refresh
  failed, the screen shows *"Last updated 2 hours ago — you're offline"* (or *"— the server
  didn't answer"*). A screen never shows a cached figure without that label once a refresh has
  failed.
- Cached data is bounded: list endpoints are cached for their first page only, and the database
  is capped at 50 MB, oldest entries evicted first.

### Writes

- A write is sent only when the last request reached the server; otherwise the control is
  disabled with the offline message. A write that fails for lack of connection shows the same
  message and is **not retried automatically**.
- Every write carries the same body the web client sends, including `version` for optimistic
  locking. A `409`'s problem code is translated and shown; the screen then re-reads.
- After a successful write, the screens that depend on it refresh (the app invalidates the
  affected repositories; it never patches cached figures itself).

### Screens (parity with the web)

Each screen follows its feature doc and the design's 390-wide artboards. Bottom navigation holds
the five most-used destinations — **Transactions, Check-in, Bills, Accounts, More** — and *More*
lists the rest in the web's order: Debt plan, Goals, Envelopes, Categories, Insights, Import,
Household, Devices, Preferences, and Admin for an instance administrator.

| Area | Feature doc | Notes for mobile |
|---|---|---|
| Connect, sign in, setup, join | authentication-and-households | Connect screen is mobile-only |
| Transactions and transfers | transactions, transfers | Add sheet reachable from a floating button on Transactions |
| Check-in | weekly-check-in | Already designed one step per screen |
| Bills & income | bills-and-income | |
| Accounts | accounts | |
| Debt plan, debt detail | debt-plan | |
| Goals, goal detail | goals | |
| Envelopes | envelopes | |
| Categories, payee rules | categories, weekly-check-in | |
| Insights, CSV export | reporting | Export uses the platform share sheet; the file is written to the app's temporary directory and deleted after sharing |
| Import | file-import | File picked with the platform picker; bytes read into memory and sent, never copied into app storage |
| Household, Devices, Preferences | authentication-and-households | Devices marks *this phone* |
| Admin | admin-console | Shown only when the user is an instance administrator |

### Language, money and dates

- Every string comes from an ARB file; English is the source (ADR-0023). Error messages come
  from the problem `code` and `params`, never the server's `title` or `detail`. The ARB keys
  mirror the web's `en.json` keys where the text is the same, so the two stay in step.
- Amounts are parsed from their wire strings into `Decimal` (package `decimal`) and formatted
  with `intl`'s `NumberFormat.currency` for the user's locale and the amount's own currency.
  `double` never holds an amount: an analyzer rule bans `double.parse`/`num.parse`/`toDouble()`
  in `lib/`, and the money formatter has its own tests (scale, rounding, negative, very large).
- Dates are formatted with `intl` and the user's locale; a date-only field is never converted
  through a time zone.
- The layout works right-to-left: `EdgeInsetsDirectional`, `AlignmentDirectional`, and a widget
  test of every screen under `TextDirection.rtl`.

### Design

- The design tokens in `design/` (colours, both themes, spacing, radii, type scale, fonts) are
  exported once into `lib/core/theme/tokens.dart` by a script, `tools/mobile-tokens.sh`, so a
  token change is a re-run, never a hand edit. Widgets never use a literal colour or size a token
  carries.
- Light and dark follow the system by default, with the same override the web's Preferences
  offer, kept on the device.
- Text scales with the platform setting; rows grow rather than clip; tap targets are at least
  48 dp.

## Data model

No server-side change beyond `GET /api/meta` (no migration). On the device:

```
secure storage   server_url, token, cache_key
drift (sqlcipher) cache_entries(key TEXT PK, household_id TEXT, body TEXT, fetched_at INT, bytes INT)
shared prefs     theme override, text-only UI preferences — never anything financial
```

## API

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET` | `/api/meta` | Identify a Budget Owl server and its API version | public, rate-limited |

Every other route is the existing API, called exactly as the web client calls it, over the bearer
transport.

## UI

Screens are listed above. Every screen has, as the web does: a loading state, an empty state, an
error state with a true message, an offline/stale state, a `VIEWER` state without write controls,
dark mode, large text, and right-to-left.

## Security considerations

### Assets

The bearer token (full access to the household's finances until revoked); the cached financial
data on the phone; the server address (reveals where a household's server lives); the user's
password at entry.

### Actors

A thief with the phone, locked or unlocked; another app on the same phone; a network attacker on
café Wi-Fi; a malicious or misconfigured server at the address typed; a `VIEWER`; a member who has
been removed; someone reading a bug report or crash log.

### Attacks considered

1. **Token theft from storage.** Token only in Keychain/Keystore; never `SharedPreferences`, a
   file, Drift, a log line or a URL. A test greps `lib/` for the token key outside the storage
   class; an architecture test forbids `SharedPreferences` from touching auth.
2. **Cached data read off a stolen or backed-up phone.** SQLCipher with a Keystore/Keychain key;
   excluded from backups; wiped on sign-out and on any `401`.
3. **Cleartext on hostile Wi-Fi.** HTTPS only; release builds refuse `http` in code and in the
   platform network config. No certificate bypass button.
4. **A server that is not ours to trust.** The app sends the password only to the address the
   user typed, only after `/api/meta` answered over verified TLS. Redirects are not followed for
   API calls (a `3xx` is an error), so a server cannot bounce the bearer token to another host.
5. **A revoked or removed member keeps using the phone.** Server-side revocation is immediate;
   the next request's `401` wipes the phone. Offline, the cache stays readable only until then;
   that window is the accepted risk below.
6. **Crash and log disclosure.** No third-party crash reporting (ADR-0016). `debugPrint` is
   stripped from release; the HTTP client never logs bodies or headers; a Dio interceptor that
   logs is debug-only and redacts `Authorization`, `password` and every amount field.
7. **Screenshots in the app switcher.** Android `FLAG_SECURE` is **not** set (it also blocks the
   user's own screenshots); instead, the app blurs its content when it goes to the background
   (iOS and Android), so the task switcher shows no balances. This is the privacy option with the
   smaller cost; see out of scope.
8. **Invitation links for another host.** A pasted link whose host differs from the server
   entered is refused rather than switching servers silently.
9. **Exported CSV left on the phone.** Written to the temporary directory, shared, then deleted;
   the CSV is the server's (formula-escaped by `CsvWriter`).
10. **A `VIEWER` reaching a write.** Hidden controls are UX only; the API refuses. Tests prove the
    app shows the translated `403` rather than crashing if a write is attempted anyway.
11. **Money mangled by floating point.** `Decimal` only; analyzer ban on `double` parsing in
    `lib/`; tests of formatting and of the check-in's figures shown to the cent.
12. **Supply chain.** Every package from pub.dev is pinned in `pubspec.lock`, which is committed;
    no package that phones home (analytics, crash reporting, ads) is permitted.

### Controls, and where each is enforced

| Control | Where |
|---|---|
| Token only in secure storage | `lib/core/auth/token_store.dart`; test + architecture test |
| HTTPS only, debug-only local exception | `ServerAddress.parse`; Android `network_security_config.xml`; iOS `Info.plist` |
| No redirects on API calls | Dio `followRedirects: false`; test |
| 401 → wipe → sign-in, once | `AuthInterceptor`; test |
| Encrypted cache, no backup | `lib/core/cache/`; `AndroidManifest.xml`; iOS exclusion; test that the file is unreadable without the key |
| No amount in a `double` | `analysis_options.yaml` custom lint / grep test; money tests |
| App-switcher privacy | `PrivacyCover` widget on `AppLifecycleState.inactive` |
| `/api/meta` reveals nothing extra | `MetaIT` asserts the exact body |

### Accepted risks

- **An unlocked, stolen phone shows the household's data until it is revoked.** Same as the web
  in an unlocked browser. An optional app lock (biometric or PIN) is out of scope for this slice.
- **Offline, a revoked phone keeps showing its cache** until it next reaches the server. The
  cache is encrypted at rest, so this is the unlocked-phone case above.

## Edge cases

- The server's address changes (new domain): Settings → Server → change, which signs out.
- The server is upgraded to an API the app does not know: the app says which version each side
  is and offers to retry; it does not try to talk to it.
- The user is a member of no household (an invited user who has not yet joined): the same
  "not part of a household yet" screen as the web.
- The clock on the phone is wrong: nothing depends on it except the *"Last updated"* label; the
  check-in's week comes from `GET /api/check-ins/current`, as on the web.
- Two members edit the same row: the second write's `409` is translated and the screen re-reads.
- Very large text: every list row and form grows; a golden test at text scale 2.0 per screen.

## Out of scope

- **Offline writes / a write queue** — decided against for this slice (decision 3).
- **OIDC sign-in on mobile** — the web's optional OIDC has its own slice; the app shows only
  email and password, and says so if the server has password login disabled.
- **App lock (biometrics/PIN)** — the accepted risk above; a later slice.
- **Push notifications** — would need a service we operate (ADR-0016) or one the self-hoster
  runs; not now.
- **Home-screen widgets, share-sheet import, background sync** — platform channels, later.
- **Store publishing** — this slice produces builds the owner installs (a debug APK, and iOS from
  the Mac). App Store and Play Store listings are paperwork, not code.

## Testing notes

- Unit and widget tests for every screen, run by `tools/verify.sh mobile`, with coverage.
- **Golden screenshots** of every screen at 390×844 in light, dark, `VIEWER`, offline/stale,
  error and RTL, rendered from fixtures **generated by the real API** (`DemoFixtureIT` writes
  JSON for mobile alongside the TypeScript for the web), and read by eye before the slice lands —
  the mobile equivalent of `tools/ui-check.sh` (CLAUDE.md rule 8).
- An integration test of the critical journey against a fake HTTP server: connect, sign in, add a
  transaction, see the balance change, go offline, see the stale label, sign out, cache gone.
- The security tests named in the controls table, each with a sabotage proof.
- A debug APK built (`flutter build apk --debug`) as part of the slice's final check.
