# Feature: Mobile app (Flutter)

- **Status:** In progress — slice 15 (phase 1 built; see the end of this doc)
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
  development. A release build has no such exception. What enforces it is the app's own code:
  the address parser, the origin guard, refused redirects, and a release-only
  `flutter.io.allow_http: false` zone around the app. The platform configs (Android
  `network_security_config`, iOS ATS defaults) declare the same policy but do not govern Dart's
  sockets.
- **Certificates are verified against the system's trusted CAs.** There is no "accept this
  certificate anyway" button. On iOS, a self-hosted server with its own certificate authority
  works once that CA profile is installed and enabled. **On Android it does not yet**: Dart's TLS
  reads only the system store, so a user-installed CA fails closed as "certificate not trusted"
  (see Known gaps). Until that is closed, Android needs a publicly trusted certificate. The error screen distinguishes, in its
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
  `double` never holds an amount: an architecture test bans `double.parse`/`num.parse`/`toDouble()`
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
secure storage   server_url, token (with the origin that issued it), cache_key
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
3. **Cleartext on hostile Wi-Fi.** HTTPS only. `ServerAddress.parse` refuses an `http` address
   before anything is sent, the API client's origin guard sends only to the chosen origin,
   redirects are not followed, and a release build runs inside the zone value
   `#flutter.io.allow_http: false`, so Flutter's engine refuses an `http://` connection at the
   socket. The platform network configs declare the same policy but do not govern Dart's sockets.
   No certificate bypass button.
4. **A server that is not ours to trust.** The app sends the password only to the address the
   user typed, only after `/api/meta` answered over verified TLS. Redirects are not followed for
   API calls (a `3xx` is an error), so a server cannot bounce the bearer token to another host.
5. **A revoked or removed member keeps using the phone.** Server-side revocation is immediate;
   the next request's `401` wipes the phone. Offline, the cache stays readable only until then;
   that window is the accepted risk below.
6. **Crash and log disclosure.** No third-party crash reporting (ADR-0016). `debugPrint` is **not**
   stripped from release builds, so the app's only call to it, the API client's one-line request
   log, is installed only in a debug build (`logRequests: kDebugMode`), and an architecture test
   forbids `print` and `debugPrint` anywhere else. That line is the method, the path with long
   segments redacted, and the status: never a body, a header or a query string.
7. **Screenshots in the app switcher.** Android `FLAG_SECURE` is **not** set (it also blocks the
   user's own screenshots); instead, the app covers its content when it goes to the background
   (iOS and Android), so the task switcher shows no balances. This is the privacy option with the
   smaller cost; see out of scope. On Android 13 (API 33) and later, `MainActivity` also calls
   `setRecentsScreenshotEnabled(false)`, so the system takes no recents screenshot at all; the
   cover stays for older versions and for iOS.
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
| HTTPS only, debug-only local exception | `ServerAddress.parse`; the API client's origin guard; no redirects; release zone `#flutter.io.allow_http: false` (`cleartext_policy.dart`, around `runApp`); tests. Android `network_security_config.xml` and iOS `Info.plist` declare the same policy but do not govern Dart's sockets |
| No redirects on API calls | Dio `followRedirects: false`; test |
| 401 → wipe → sign-in, once | `AuthInterceptor`; test |
| Encrypted cache, no backup | `lib/core/cache/`; `AndroidManifest.xml`; iOS exclusion; test that the file is unreadable without the key |
| No amount in a `double` | `analysis_options.yaml` custom lint / grep test; money tests |
| App-switcher privacy | `PrivacyCover` widget on `AppLifecycleState.inactive`; Android 13+ `setRecentsScreenshotEnabled(false)` in `MainActivity` |
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

## What phase 1 of slice 15 has built

Phase 1 is the app's skeleton and its reference feature; phase 2 fills in the screens that are
placeholders today. Everything above applies, with the departures below. None was taken silently.

**Backend**: `GET /api/meta` (`instance/web/MetaController`, `InstanceMetaService`,
`MetaRateLimiter`: 60 requests a minute per client address, then `429` with `Retry-After`),
public in `SecurityConfig`, `AuthorizationIT` and `TransportIT`, and listed in
`authentication-and-households.md`. `MetaIT` pins the exact body and headers. `DemoFixtureIT`
drives the real API as the phone does and writes
`mobile/test/fixtures/{demo,first-run}.generated.json`.

**The app** (`mobile/`; `mobile/README.md` says how to add to it):

- `lib/core/`: server address rules and the `/api/meta` check with a distinct error for each
  failure; the Dio client (no redirects, bearer interceptor, any `401` wipes once and routes to
  sign-in, problem bodies translated from ARB, a debug-only log line that never carries a body, a
  header or a query); the token, server address and cache key in `flutter_secure_storage` only;
  the SQLCipher cache (32 random bytes of key, cache-first reads, stale labels, first page only,
  50 MB cap); reachability from the last request; `Decimal`-only money; tokens and fonts generated
  from the web's; ARB generated from the web's `en.json` plus `mobile/l10n/mobile.en.json`;
  go_router with the five tabs and More in the web's order; `PrivacyCover`.
- **Built screens**: Connect, Sign in (device label editable), Setup (only on a fresh server),
  Join (other hosts refused, disclosure above the button), Not in a household (sign out),
  Transactions in full (list, account chips, filters, search, add, edit, delete, categorise,
  move money, transfer edit and delete, split, pair), Devices (this phone marked; signing it out
  is `POST /api/auth/logout` then the wipe, whatever the server said), More.
- **Placeholders**: Check-in, Bills, Accounts, and every More entry except Devices. Each says
  plainly that it is coming in the next update.
- **Platform**: Android `network_security_config` (no cleartext; system and user CAs; a debug-only
  file adds `localhost`, `127.0.0.1`, `10.0.2.2`), `allowBackup="false"`, `dataExtractionRules`
  and `fullBackupContent` excluding every domain. iOS: no ATS key (defaults), Podfile
  `platform :ios, '15.0'` matching the project. `test/architecture/platform_config_test.dart`
  reads each file the build uses.
- **Gate**: `tools/verify.sh mobile` (part of `all`) runs `pub get --enforce-lockfile`, format,
  the token and string generators' `--check`, analyze, the architecture guards, the suite with
  coverage, a **lib/core line floor of 80%** (`tools/mobile-coverage.sh`, which counts a
  never-loaded file as uncovered; 94.9% at the end of phase 1), and **`flutter build apk
  --debug`** in the same stage, not a separate target, when `ANDROID_HOME` points at an SDK
  (without one it is `missing`, which fails in CI).
- **Goldens**: `tools/mobile-check.sh` renders 80 PNGs (28 scenarios in light and dark, and the
  main ones in RTL and at text 2.0) at 390×844 with the bundled fonts, from the generated
  fixtures, and lists them. Each was read; what reading them changed is below.

### Departures from the text above

| Where | The text says | What was built | Why |
| ----- | ------------- | -------------- | --- |
| SQLCipher | (implied) `sqlcipher_flutter_libs` | **`sqlite3` 3.x build hooks** (`hooks: user_defines: sqlite3: source: sqlcipher` in `pubspec.yaml`); the cache refuses to open if `PRAGMA cipher_version` is empty | `sqlcipher_flutter_libs` is end-of-life for `sqlite3` 3.x. The refusal means a build that lost SQLCipher fails closed instead of caching in plaintext |
| Drift | Drift tables | **Drift's `GeneratedDatabase` with one table in custom SQL**, no code generation | One key–value table; build_runner would be the only generated code in the app |
| iOS backup | `isExcludedFromBackup` | The cache lives in **`Library/Caches`** (`getApplicationCacheDirectory`) | iOS never backs that directory up, and no platform channel is needed. The key is in the Keychain with `first_unlock_this_device`, which is not migrated either |
| Device label | *"Pixel 8 · Android"* | **"Phone · Android"** / **"iPhone · iOS"**, editable | A model name needs a device-information plugin; not worth a dependency in phase 1 |
| Theme override | Kept on the device; shared prefs in Data model | **Follows the system only**; `shared_preferences` is not a dependency | The override belongs to the Preferences screen (phase 2). One fewer place anything could be written |
| Public writes | — | **Setup and accepting an invitation send the double-submit CSRF pair**, read from the `XSRF-TOKEN` cookie of a fresh `GET /api/setup/status` (`csrfForPublicWrite`) | Those two routes are public but CSRF-protected for the web. The phone has no cookie jar; it sends the pair it was just given and keeps nothing |
| Session-ended notice | *"Your session ended. Sign in again."* | The web's `login.notice.expired`: *"Your session ended. Sign in again to continue."* | A mobile key may not fork a web text (the generator refuses) |
| Signed out | — | A signed-out phone lands on **Connect** (its server shown, sign-in one tap away); only an ended session lands on **Sign in** with the notice | Setup and Join are reached from Connect too |
| Type scale | Tokens from `design/` | **Constants in the token generator** (`TypeScaleTokens`) | The web's token sheet has no type-scale variables; the sizes are written once, in the generator, not in widgets |
| Fixture order | — | Same-date ledger rows keep the committed file's order; a regeneration that differs only by such a flip writes nothing | The API orders same-date rows by random id, so goldens drawn from the file would churn on every regeneration |
| Revoking another device | "the screens that depend on it refresh" | Re-reads **only the devices list** | No household data changed |
| Category path separator | The web's `transactions.categoryPath`, *"{parent} → {name}"* | Mobile strings **`transactions.categoryTrail`** and **`transfers.accountsTrail`** with **"›"** | The bundled Figtree has no U+2192, so the arrow drew as a box. "›" is in the font and mirrors in RTL |
| Offline on Transactions | — | **No standing offline banner**: the stale label says the copy is old; *"You're offline — this needs a connection"* appears when a write is attempted. The record button stays opaque (`surface`/`muted`, field border) and explains on a tap | The design has no disabled-colour token; the web's 45% opacity let the row beneath show through the button |
| Token storage | The token in secure storage | The token **and the origin that issued it**; a token whose origin is not the server about to be called is deleted, not loaded | Audit L3: no stored state can carry one server's token to another |
| First run | — | A marker file in Application Support; when it is missing, secure storage is wiped before anything is read | Audit L5: the iOS Keychain survives an uninstall |
| Sign-out order | "revokes the token server-side … and wipes local state whether or not the server answered" | The token leaves secure storage and memory **first**; the logout is sent with the copy; then the cache and key go | Audit N1: an app killed mid-sign-out while offline resumed signed in |
| Launch version check | — | Runs **in sequence** inside start-up, before `/api/auth/me` (after the cached member is shown, when there is one) | Audit H-b: run beside start-up, the two could overwrite each other's state |

### Proving each control can fail

Each control was broken, its tests run and seen to fail, and the file restored from git and
confirmed clean (`git diff` empty, the sabotage absent).

| Control | Broken how | Failed |
| ------- | ---------- | ------ |
| Token only in secure storage | The token's storage key added as a constant in `api_client.dart` | `guards_test`: the token's storage key appears only in TokenStore |
| HTTPS only | `ServerAddress.parse` let `http` through; the release `network_security_config` set `cleartextTrafficPermitted="true"` | `server_address_test` (release refusal, debug-only local hosts, stored address re-checked); `platform_config_test`: no cleartext |
| No redirects | `followRedirects: true` in the API client and the server check | `real_transport_test`: a redirect is an error and the token never reaches the other host (real local servers); `api_client_test`; `server_check_test`; `guards_test` |
| 401 → wipe → sign-in, once | The interceptor matched `499` instead of `401` | `api_client_test`: a 401 ends the session without a retry; `session_test`: a burst of 401s wipes once |
| Encrypted cache, no backup | `PRAGMA key` removed; `allowBackup="true"` | `cache_test` (no figure in the clear, unreadable without the key, a different key reads nothing); `platform_config_test`: nothing backed up |
| No amount in a `double` | `parseWireAmount` went through `double.parse` | Only `guards_test` at first, so a test was **added**: the largest `NUMERIC(19,4)` amount keeps all 19 digits. It then failed too |
| App-switcher privacy | The cover reacted to `paused` only; separately, `PrivacyCover` removed from `app.dart` | `core_rules_test`: the cover on `inactive`; `screens_test`: the running app is covered (added: the widget test alone would not notice the unwiring) |
| `/api/meta` reveals nothing extra | A fourth field on `MetaResponse`; the limiter removed; `Cache-Control` removed; the permit path changed | `MetaIT` (exact body ×3, rate limit, no-store); `AuthorizationIT` public routes |
| The phone talks only to Budget Owl | The `app` name check removed | **Nothing failed**: "another app's JSON" lacked `minimumClientApiVersion` and was refused for that. Fixed, then it failed. Ignoring `minimumClientApiVersion`: the version-mismatch test failed |
| Invitation for another host | The host comparison removed | `invitation_link_test`; `screens_test`: refused and nothing sent |
| `VIEWER` sees no write control | `Role.canWrite` returned `true` | `core_rules_test` roles; `screens_test`: a VIEWER sees no write control |

After the phase-1 security audit and the coordinator's golden review, each fix was proved the
same way:

| Item | Broken how | Failed |
| ---- | ---------- | ------ |
| H1 shared transport | `dio.close()` put back in `ServerCheck` | `real_transport_test`: the shared transport stays open (two checks and a bearer request through one real `IOHttpClientAdapter`); the journey. With the fakes made to honour `close()`, a dozen screen, journey and session tests had also failed before the fix |
| L1 cleartext at the socket | `runWithCleartextPolicy` returned `body()` without the zone; separately, `main.dart` passed `allowLocalCleartext: true` | `cleartext_policy_test` (release refusal at the socket, zone flag); `guards_test`: the app runs inside the zone |
| L2 401 while starting | `sessionEnded` ignored the starting state | `session_test` L2: a token revoked while the phone was off is wiped at start-up |
| L3 token to a second origin | The wipe in `connect()` removed; separately, `TokenStore.load` ignored the origin; then both | `session_test` L3 connect wipe; `cache_test` + `session_test` origin binding; with both removed, the relaunch scenario sent `GET /api/auth/me` with the old token |
| L4 revoke before wipe | Revocation limited to the signed-in state (the old behaviour) | `session_test` L4: unsupported-version screen, and a failed sign-in follow-up |
| L5 Keychain after reinstall | The wipe on a missing marker removed | `session_test` L5 |
| L6 recents screenshot | `setRecentsScreenshotEnabled(false)` removed | `platform_config_test` |
| L7 rate-limited check | The `429` branch removed | `server_check_test`: busy, not "not Budget Owl" |
| G1 separator | The ledger row back on the web's arrow key | 13 goldens (transactions, viewer, account, offline-stale, row actions) |
| G2 standing banner | The `OfflineNote` put back on Transactions | `screens_test` offline; 4 offline-stale goldens |
| G3 translucent button | The button's background back on the divider token | `screens_test` offline (alpha 0.16, expected 1.0); 4 offline-stale goldens |
| Meta limiter coverage | The prune at the cap removed | `MetaRateLimiterTest`: expired windows are dropped (JaCoCo had failed the gate on this package's branches) |

After the re-audit:

| Item | Broken how | Failed |
| ---- | ---------- | ------ |
| N1 token out first | The token read with `held` and cleared after the logout, as before | `session_test` N1: a token was in storage while the logout was in flight; killed then, the next launch was signed in |
| N1 the revocation's 401 | The revocation marked as a bearer request | `api_client_test`: its 401 ended the session. (The session-level test still passed: `signOut`'s own `_ending` flag also stops a second end, so there are two guards) |
| N2 foreign token | The full wipe on a foreign token removed | `session_test` N2: the cache file and key survived |
| H-a origin per request | The interceptor's origin check removed | `api_client_test`: a foreign token was sent; a revocation went to the wrong origin |
| H-b ordering | The version check put back beside start-up (`unawaited`) | `session_test` H-b (`/api/auth/me` was sent beside the check) and L4, which no longer has a delay in it |
| N3 | Documentation only | — |

### What reading the screenshots changed

Sheets opened inside the tab's navigator, under the tab bar: they now open on the root navigator.
Long titles clipped at text 2.0: app-bar titles take two lines and screen headers wrap. In RTL a
negative amount's sign moved to the wrong end: amounts keep their own direction, and the minus is
U+2212 joined by a word joiner so it never wraps away from its figure. At text 2.0 the ledger row
squeezed the payee to a few letters a line: past 1.5× it stacks the amount under the payee. The
floating button had a black ring (the test binding's disabled shadows): goldens draw real
shadows. Sign-in, Setup and Join had no way back to Connect: each has one. After review: the
category separator drew as a box (now "›"), the offline screen stacked two banners (now only the
stale label until a write is tried), and the offline record button let the row beneath show
through (now opaque).

### Known gaps

- **Android user-installed CAs are not trusted by the app's requests.** The network security
  config trusts them, but Dart's TLS stack reads only the system store, so a server whose CA the
  user installed fails as "certificate not trusted". It fails closed. The remedy (a platform
  channel, or a CA the user pastes in) is a phase-2 decision. iOS honours an enabled CA profile.
- **The recents-screen behaviour on Android 13+ needs checking on a device.** No emulator or test
  here shows what the task switcher draws; the call and its version guard are pinned by a test.
- **Every phone and browser shares one rate limit as shipped.** The API keys limits on the socket
  address, which is the stack's nginx, behind the self-hoster's own TLS proxy. Correct forwarding
  needs the outermost proxy to set `X-Forwarded-For` from the real client, nginx to pass it through
  rather than append, and `docker-compose.yml` to pass `SERVER_FORWARD_HEADERS_STRATEGY` to the API,
  which it does not today (`deploy/README.md`).
- **Pairing offers candidates from the ledger's first page only**, because only the first page is
  read and cached. A transfer's other half older than 50 rows cannot be picked yet.
- **Release signing** still uses Flutter's debug keys (`android/app/build.gradle.kts`). Store
  publishing is out of scope, but a release build must not ship like this.
- **`PlatformSecureStore` is not exercised by tests** (2 of its 8 lines): it is the platform
  channel itself. Its `first_unlock_this_device` option is asserted by `guards_test`.
- Every placeholder screen above, CSV export and import are phase 2.
