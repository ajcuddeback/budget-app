# Feature: Mobile app (Flutter)

- **Status:** Done — slice 15. Every web screen is built on the phone; see the end of this doc. Still to check on a real device: the items under "Known gaps" marked as needing a device, and a first iOS build in Xcode (the Swift in `AppDelegate.swift` has not been compiled).
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
- **HTTPS only.** An `http://` address is refused with an explanation that names the user
  guide's "Give your server an HTTPS address" page in a sentence; there is no link to it. The only exception is a **debug build**
  talking to `localhost`, `127.0.0.1` or `10.0.2.2` (the Android emulator's host), for
  development. A release build has no such exception. What enforces it is the app's own code:
  the address parser, the origin guard, refused redirects, and a release-only
  `flutter.io.allow_http: false` zone around the app. The platform configs (Android
  `network_security_config`, iOS ATS defaults) declare the same policy but do not govern Dart's
  sockets.
- **Certificates are verified against the system's trusted CAs and, on Android, for a server
  that needs one, the one CA the person installed for it.** There is no "accept this certificate
  anyway" button. On iOS, a self-hosted server with its own certificate authority works once that
  CA profile is installed and given full trust; **there is no per-server pin on iOS**: `dart:io`
  uses the platform's default trust there, so any CA the person has given full trust — an
  MDM-installed one included — can vouch for the server, and the "Trusted through" and "no longer
  installed" notes below never appear (Known gaps). On Android it works once the CA is installed (Settings →
  Security → Encryption & credentials → Install a certificate → CA certificate) **before the
  server is connected to**: Dart's TLS reads only the system store by itself, so `MainActivity`
  hands it the user-installed CAs through a read-only platform channel, and the app **pins** the
  server when it connects. If the system roots verify it, no user CA is ever trusted for it. If
  only a user CA does, that CA's SHA-256 fingerprint is stored with the address, and from then on
  the server is trusted through the system roots and that CA alone — never through another user
  CA, such as one an employer's device management pushed to inspect TLS. The phone's CAs are read
  again whenever the app comes back to the foreground: a pinned CA removed in Settings is no
  longer trusted for any new connection, and the server fails as "certificate not trusted". A
  sign-out keeps the pin with the address; changing server clears both. Everything in this
  bullet from here on is **Android only**. **The pin is shown**:
  the Connect screen's choices and More → Server say *"Trusted through a certificate authority
  installed on this phone:"*, and then, on its own line, the CA's name in bold with the first 8
  hex digits of its fingerprint beside it (with no name, *"…It has no name; its fingerprint
  begins:"* and 16 digits). The name is the CA certificate's subject CN, else its O, so a CA the
  person does not recognise can be noticed. It is the CA maker's own text, so it is never put
  inside a sentence or quotes: it is its own span, inside a first-strong isolate (U+2068…U+2069),
  and it is cleaned by an allow-list (letters, digits, spaces, punctuation that is not a quote,
  at most two combining marks per character; every format, private-use, unassigned and
  default-ignorable code point dropped; at most 64 characters), again when it is read back from
  storage. **When the pinned CA is no longer on the phone** (removed in Settings, or the store
  could not be read), the first line says so instead — *"The certificate authority this server
  was trusted through is no longer installed on this phone:"* — and a third says connections
  will fail until it is installed again or the person uses the screen's change-server control.
  Before the phone's store has been read, nothing is claimed either way. Nothing extra is said
  for a server the system roots verify. The chain and the host
  name are always verified, and a store that cannot be read means the system roots only (fails
  closed). This **needs checking on a device** (Known gaps). The error screen distinguishes, in its
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
  household, what the app or a plugin left in the temporary directory (an exported CSV in
  `budget-owl-export/`, share_plus's copies of our exports, file_selector_android's
  `<uuid>/<name>` copy of a picked statement, file_selector_ios's `tmp/<bundle id>-Inbox/`:
  `core/storage/temp_files.dart`), and any in-memory state. The temporary-directory sweep also
  runs once at every start-up, for an app that was killed mid-share; it never fails a wipe (a
  failure is logged in debug builds and swept again next start). Only the server address
  survives a sign-out (so signing back in is one screen), and it is removed when the user
  changes server.
- **A wipe that reports a failure still ends the session** (round 5, U1; round 6, V1). Ending
  the token (taken out of secure storage, then revoked) and the wipe each run in their own try,
  so neither stops the other, and every wipe step is attempted — the wipe's token step retries a
  delete that failed. A token whose delete failed is still revoked on the server. Then the token
  is checked to be gone from secure storage. If it is, sign-out, a `401` and a change of server
  reach sign-in or the connect screen. **If it is not** — the next launch would resume with it —
  the phone says *"This phone couldn't finish signing out. Try again, or remove Budget Owl from
  the phone."* with *Try again* (`SessionSignOutIncomplete`), never the plain signed-out screen.
  A failed sign-in's clean-up and a failed start-up's recovery do the same (round 7, W1).
  Nothing of the session is in memory either way. **The unfinished state survives a relaunch**
  (round 7, W2): every path that ends a session first writes a marker file in Application
  Support (`budget_owl.sign_out_pending`, beside the install marker; it records whether the
  sign-out was a change of server), and removes it only once the token is confirmed gone. A
  launch that finds it ends the session again — the wipe, no token loaded, the delete tried
  again — before reading anything else, and shows the unfinished state again if the token is
  still there. A new sign-in removes a marker still left (its removal had failed) **before**
  saving its token, so a kill in between cannot make the next launch wipe the new session (round
  8, X2). A marker that cannot be written is logged (debug builds) and the sign-out goes on. *Try
  again* that finishes, keeping the server, **starts the session over** (start-up applies the
  server's CA pin and makes it current, in that order), because a launch that found the marker
  stopped before doing either; without it the sign-in that followed had no client to send with
  (round 8, X1). `connect()` turns a failed wipe into its own failure, *"This phone could
  not clear what it held for the previous server, so nothing was connected"*: no server is saved
  or current, so a half-wiped phone is never connected to a new origin.

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

- A write is sent only when the last request reached the server **and `GET /api/meta` has
  confirmed the server's version this launch**; otherwise the control is disabled with the
  offline message, or *"Changes are off until the server confirms it runs a version this app works
  with. Refresh to check again."* A failed check (busy, timed out, anything) keeps writes off and
  reads on, from the cache and the server, and is retried on the next request that reaches the
  server. A write that fails for lack of connection shows the offline message and is **not
  retried automatically**.
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
secure storage   server_url, server_ca_pin and server_ca_name (Android, when the server needed
                 a user CA), token (with the origin that issued it), cache_key
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
   segments redacted, and the status: never a body, a header or a query string. Beside it,
   `debugLogFailure` (also debug-only, same file) names a local step that failed and the error's
   type, never its message. A pinned CA's name is never logged: `CaPin.toString` leaves it out.
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
| Certificates verified; a private CA trusted only once installed on the phone, and only for the server it was connected through | No `badCertificateCallback` (guards); Android: `MainActivity`'s read-only `app.budgetowl/trust` channel (`AndroidCAStore`, `user:` aliases); `PhoneTrustAdapter.checkAndPin` at connect (system roots alone first, then each user CA alone), the fingerprint stored by `ServerStore` with the address and applied by `resume()` before the first request; the transport trusts `SecurityContext(withTrustedRoots: true)` plus that one CA while it is on the phone, re-read on every return to the foreground (`reloadTrustOnResume`), the inner client swapped, never the shared adapter closed; system roots only on any failure; `platform_config_test` pins `<certificates src="user" />` and the channel; `user_trust_test` (real sockets, three test CAs), `pin_session_test` |
| No write before this launch's version check | `Reachability.unconfirmed` (`canWriteNow`, every writer's `reachabilityProvider.canWrite`); `ApiClient` refuses any non-`GET` but the revocation until `/api/meta` has passed; `signIn` re-checks first; `version_gate_test`, `api_client_test` |
| A start-up never leaves a session behind it, or hangs | `SessionController.resume`: no token or no server → full wipe; a malformed stored origin is another origin's (`TokenStore.originOf`); any error → best-effort wipe and sign-in with a notice; `start_up_test` |
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

- The server's address changes (new domain): More › Server › Change server, which signs out.
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
| Unfinished sign-out | — | A second marker file in Application Support (`budget_owl.sign_out_pending`), written before a session's wipe and removed once the token is confirmed gone; a launch that finds it ends the session again before reading anything | Round 7, W2: an app closed on "couldn't finish signing out" would otherwise resume with the surviving token |
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

### Phase 2, core security fixes

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| R1 no token, no cache | Start-up wipes the cache file and its key whenever there is no token (and no readable server); once on a fresh install. `LocalState.wipe` attempts every step even when one fails | The no-token wipe narrowed back to a foreign token only; separately, the no-server wipe removed; separately, the token step no longer best effort | `start_up_test` R1: a sign-out killed between `take()` and its wipe left the cache file and key (and the relaunch idempotence test); the unreadable-server test; "storage that cannot delete the token still loses the cache file and its key" |
| R2 version gate fails closed | `Reachability.unconfirmed` until `/api/meta` passes this launch; `ApiClient` refuses non-`GET` before sending; a failure keeps writes off and reads on; retried, one at a time, on the next request that reaches the server; `signIn` re-checks; `OfflineNote` says why | `_publish` ignored the confirmation; separately, the transport refusal removed; a failed check confirmed (the old fail-open); the retry removed; the sign-in re-check removed | `version_gate_test` (8 of 11); `version_gate_test` (5) and `api_client_test` R2; 6; 4; `version_gate_test` sign-in ×2 **and** two existing `session_test` sign-ins, whose `POST` the transport then refused because they sign in before the launch check has answered |
| R3 malformed origin, any start-up error | `TokenStore.originOf` (scheme and host checked): a bad origin is a foreign token, full wipe. `resume()` catches everything: best-effort wipe, then sign-in with "Your session ended", or Connect with no server | `Uri.parse(stored).origin` put back; separately, the `try`/`catch` in `resume()` removed | `start_up_test` R3 origin ×3 (the session-level pair because the notice showed the catch-all, not the foreign-token path, had cleaned up); the four catch-all tests, including the widget test that saw the spinner |
| AND1 user CAs on Android | `MainActivity` channel `app.budgetowl/trust` → `user_trust.dart` → `platformHttpAdapter` on Android only | See the AND1 rows below | |

| AND1 control | Broken how | Failed |
| ------------ | ---------- | ------ |
| The user CAs are added | `setTrustedCertificatesBytes` call removed | `user_trust_test`: the test-CA server trusted (direct and through the mocked channel), the read-once test, the host-name and redirect tests (which need the CA to get that far), the fallback test |
| The transport uses that context | `createHttpClient` built with `null` | the same five real-socket tests |
| A failed store read is the system roots | `phoneTrustContext`'s `try`/`catch` removed | `user_trust_test`: the fallback tests (direct and through the channel). The real-socket "fails closed, without a crash" test stayed green: the adapter's own `onError` is a second guard |
| No "trust anyway" | `badCertificateCallback = (…) => true` added to the client | `guards_test`; `real_transport_test` untrusted and wrong-name; `user_trust_test` untrusted, store-failure and host-name tests |
| System roots kept | `withTrustedRoots: false` | `user_trust_test` source check only: no test here can reach a public CA |
| The config declares user CAs | `<certificates src="user" />` removed from the release config | `platform_config_test` ×2 |
| Only `user:` aliases | The Kotlin filter removed (system aliases too) | `platform_config_test` AND1 |
| One channel on both ends | The Dart channel renamed | `platform_config_test` AND1 |

After the round-2 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| P1 public writes wait for the check | `SessionController.ensureVersionConfirmed()` waits for the check in flight or runs one, and asks once more if the one it waited on failed; sign-in, setup and join call it before writing | The call removed from join; separately from setup; separately, a failed in-flight check made final | `version_gate_public_writes_test`: join, then setup, on the first tap once meta answers; `version_gate_test` P1 (added after this sabotage first passed) |
| P3 a failed start-up revokes; a refresh's cache write ends nothing | `_recoverFromFailedStart` calls `_endToken()` first; `_refreshMe` shows the fetched member, then caches best effort; `debugLogFailure` logs the step and the error's type | The `_endToken()` call removed; separately, the refresh's cache write made fatal again | `start_up_test` P3: no `POST /api/auth/logout` with the token; the session wiped by a sealed cache |
| PIN one user CA per server (Android only) | `PhoneTrustAdapter.checkAndPin`, `pinTo`; `ServerStore` keeps `server_ca_pin` with the address; `resume()` applies it first; a wipe that forgets the server clears it (`LocalState`, `forgetTrust`) | The system roots never accepted (a user CA always pinned); any user CA counting as the pinned one; a CA that verifies the chain but fails otherwise skipped; connect not storing the pin; start-up not applying it; a server change leaving it stored; leaving the transport pinned | `user_trust_test`: system-roots server pinned to nothing; the MDM test (pinned to CA1 with CA2 first in the store); wrong name; `pin_session_test` ×1, ×2, ×1, ×2 |
| P2 a removed CA stops being trusted | The store is re-read on every return to the foreground (`reloadTrustOnResume`, `AppLifecycleListener`), and the inner client swapped when the trust changes | A re-read not applied; the inner client not swapped; the listener doing nothing on resume; the provider not wiring it | `user_trust_test`: CA removed (twice: direct, and with a keep-alive connection open), the redirect test that pins before the first read; the same plus the shared-transport tests (the old client was closed under them); the foreground test; the source check |
| AND1, again | — | The pinned CA not added; a trust-anyway callback; a store read failure propagating | 7 `user_trust_test` cases; `guards_test`, `real_transport_test` ×2 and 11 `user_trust_test` cases; the store-failure test |

After the round-3 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| S1 the pin is shown | `checkAndPin` returns a `CaPin` (fingerprint and the CA's subject CN or O, from a small DER walk, cleaned: no control or direction-override characters, at most 64); `ServerStore` keeps the name beside the pin, cleaned again on read; `pinnedCaProvider`; `TrustedThroughNote` on Connect's choices and under More → Server; goldens `connect-choices-pinned` (light, dark, RTL, text 2×) and `more-pinned` (light, dark, text 2×), read | The Connect note removed; the More note removed; the O preferred to the CN; direction overrides kept; the name in `toString`; a server change leaving it on screen; the name not stored | 2 widget tests and 4 goldens; 1 widget test and 3 goldens; the names test; the hostile-name test and the stored-name re-clean; the log test; `pin_session_test` change-of-server; connect |
| S2 a late /me belongs to its sign-in | `_refreshMe` carries the epoch it started under and drops an answer for any other | The epoch comparison removed | `session_test` S2. Its first version passed with the fix removed: Dio runs interceptors asynchronously, so the refresh went out after the sign-out had taken the token, with none. It now waits until the owner's request is on the wire |
| S3 wipes waited for, not timed | `settleUntil`, `untilSignedOut`, `untilSession`; `pumpApp` waits for start-up (which wipes when there is no token) | The wipe slowed by 1 s | With the waits every feature, journey and golden test passed; the no-household test on the old fixed settle failed with the cache key still stored |
| Guard the shared transport | `httpAdapterProvider` hands out a `SharedTransport` whose `close()` throws in a debug build; the grep guard is an allow-list of file and receiver | `close()` quiet in debug; a `ref.read(httpAdapterProvider).close()` in `session.dart` (a receiver the old name list never matched) | `real_transport_test` H1; `guards_test` |

After the round-4 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| T1 a CA's name cannot speak for the app | `cleanCaName` is an allow-list (see Rules); the note is our sentence, then the name as its own bold span in a first-strong isolate with 8 hex digits of the fingerprint; the DER reader refuses a multi-byte tag and a UniversalString beyond U+10FFFF; goldens `connect-choices-pinned` and `more-pinned` re-rendered and read | Quote look-alikes kept; initial/final quotes kept; default-ignorables kept; combining marks unlimited; the isolate removed; the multi-byte-tag check removed | The quote test, the "nothing left" test and the widget's own-span test; the quote test; the invisible-letters test; the marks test; 5 widget tests; the multi-byte-tag test. Removing the odd-BMPString or the beyond-Unicode check fails nothing: Dart's own `RangeError` already makes each `null`, so those checks are a second layer |
| DER bounds | Tests: indefinite length, a length field over 4 bytes, a child past its parent, an odd BMPString, a UniversalString above U+10FFFF, a multi-byte tag — each falls back to the fingerprint | A child bounded by the buffer, not its parent; length fields of up to 8 bytes | The child-past-parent test; the over-4-bytes test |
| T2 nothing closes the transport | `PhoneTrustAdapter.close()` refuses as `SharedTransport.close()` does; the guard's regex sees `?.close`, `..close` and tear-offs, with a self-test; `_inner` left the allow-list; `Icons` is allowed anywhere (`Icons.close` is an icon) | `close()` closing `_inner` again; `ref.onDispose(adapter.close)` in `httpAdapterProvider`; the regex back to called closes only | The T2 unit test and `guards_test`; `guards_test`; the regex self-test |
| T3 a lost CA is said | `ServerTrust.pinnedCaInstalled` (null until the store has been read since the pin was set); `pinnedCaInstalledProvider`; the note's missing-CA lines; goldens `connect-choices-pin-missing` (light, dark, RTL, text 2×) and `more-pin-missing` (light, dark, text 2×), read | Always "installed"; claimed before the store is read; the note ignoring it; the provider not following changes | The T3 unit test; the same; 3 widget tests and 7 goldens; the "removed while the screen is open" test |
| M1 exports swept | `core/storage/temp_files.dart`: `sweepExports` (all of `budget-owl-export/`, our names in `share_plus/`), run by every wipe and once at start-up | The wipe step removed; the start-up sweep removed; `budget-owl-export/` not swept; any file in `share_plus/` swept | The wipe tests and the insights share-then-401 test; the killed-mid-share start-up test; the sweep, iOS and start-up tests; the sweep test and insights' "only ours" |
| L1 picker copies swept | `sweepPickerCopies` (Android): a real UUID-named directory under temp with exactly one regular file; `isInsideDirectory` shared with the picker | Any folder name; any number of files; links followed (listing and type check both); on iOS too; the inside check always true | The picker-copies test (and start-up); the picker-copies test; the link test; the iOS test; the temp_files and import_test outside-file tests. Following links in only one of the two places fails nothing: each alone still refuses a link |
| L2 the invitation link on the clipboard | `SensitiveClipboard` over `ClipboardPlatform`; MainActivity's `setSensitive` and `clear`; the sheet takes the link back on revoke and close, if still held | Android through the plain clipboard; cleared whatever it holds; close not clearing; revoke not clearing; the Kotlin flag removed | 3 channel tests and `platform_config_test`; 3 tests; the household copy test; the revoke test; `platform_config_test` |

The `insights_test` CSV tests waited a fixed `settle` after the tap; under the gate's load the
export's real file work outlasted it once (a dismissed share's `forget` not yet recorded). They now
wait until the button no longer says *"Preparing the file…"*.

After the round-5 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| U1 a failed wipe still ends the session | The sweep is not fatal inside the wipe; `signOut` and `sessionEnded` change state in a `finally` and log a wipe failure; `connect()` turns one into `ConnectProblem.phoneNotCleared`, connected to nothing; a failed sign-in's clean-up cannot replace its error | The sweep fatal again; `connect()` letting the error escape; sign-out and `401` changing state only after the wipe returns (the round-4 code) | The sweep-failure and no-temporary-directory tests; the connect test and its screen test; sign-out, change of server and the screen test; the `401` test. The no-temporary-directory test first proved nothing: the `FutureProvider` had cached the directory at start-up; it now invalidates it |
| U2 iOS picker copies | `sweepPickerInbox`: everything in `tmp/<bundle id>-Inbox/` (AppDelegate names it from the bundle id), which must be a real directory directly in `tmp/`, compared before links are resolved (`/var` is a link on iOS); `pickerCopyRootProvider` gives the picker the real `tmp/` on iOS | Not swept on iOS; any folder name; an Inbox anywhere; an Inbox that is a link followed; compared after resolving; the wipe not told where it is; the picker deleting against Caches again | Each its own test. "An Inbox anywhere" first proved nothing — the sweep only ever rebuilds `tmp/<name>` — until the test put a real Inbox beside the misnamed request |
| U3 the clipboard is never read | `ClipboardPlatform` has no read: `setSensitive` returns a receipt, `clearIfUnchanged(receipt)` is decided natively (Android: description timestamp, a digest below 8, deferred to focus when not focused; iOS: `changeCount`, `localOnly`, ten-minute expiry) | Dart reading the clipboard; the receipt not sent; Android comparing the clip's text; Android checking while not focused; iOS not `localOnly`; no expiry; iOS reading the pasteboard | The four channel tests; the two receipt tests; `platform_config_test` (Android and iOS) for each native one |

Restoring the Kotlin for U3 first removed `userCaCertificates()`, the user-CA reader the trust
channel calls: a text replacement between two markers took it with the old clipboard code.
`platform_config_test`'s AND1 caught it before any commit; it was restored from `HEAD`, and the
diff against `HEAD` checked to remove only the old clipboard handler.

After the round-6 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| V1 a token that cannot be deleted | `_endToken` revokes the copy it took even when the delete fails; `_endLocally` runs it and the wipe in separate tries, then `TokenStore.isStored` (an unreadable store cannot confirm); if the token survives, `SessionSignOutIncomplete` with *Try again*, for sign-out, a `401` and a failed sign-in | One try for both again (round 5's regression); the survival not checked; no revoke when the delete fails; a failed sign-in ignoring it; a `401` ignoring it; an unreadable store taken as no token | 6 tests; 5; 2; the sign-in test; the `401` test; the `isStored` test |
| V2 the receipt kept in Dart | `setSensitive` returns the timestamp (Android 8+), a digest (below 8 only) or `changeCount` (iOS); Dart passes it back to `clearIfUnchanged`; MainActivity and AppDelegate keep nothing about a copy (only Android's deferred check waits on the activity) | Dart not handing it back; Android keeping the copy again; the digest computed on 8+; iOS keeping the copy again | The receipt tests and `platform_config_test`; `platform_config_test` (×3) |
| V3 the picker's delete across a linked root | `forgetPlatformCopy` resolves the copy's folder and the root before comparing; the inside check after resolving; a link as the copy deleted as a link | The root not resolved; the folder not resolved; the inside check dropped | The linked-root test; it and the link-outside test; the outside tests in `temp_files_test` and `import_test` and the link-outside test |

After the round-7 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| W1 the failed-start recovery checks too | `_recoverFromFailedStart` runs `_endLocally(forgetServer: server == null)` and shows `SessionSignOutIncomplete` when the token survives | The round-6 recovery restored (wipe errors swallowed, "your session ended" unchecked) | The failed-start test whose token cannot be deleted |
| W2 the unfinished state survives a relaunch | `SignOutMarker`: placed first in `_endLocally`, removed only when `isStored()` says the token is gone, and by a new sign-in; start-up finishes a pending sign-out before reading anything; a change of server recorded in it; writing it is best effort | No marker placed; start-up ignoring it; removed whether or not the token went; a change of server not recorded; a sign-in leaving it; a write failure stopping the sign-out | The relaunch test (and W1's); the relaunch test; both; the change-of-server relaunch test; the sign-in test; the unwritable-marker test |

The first gate run failed four tests. The marker used asynchronous file I/O, which a widget test's
pumps never complete, so start-up and a household sign-out stalled: its file work is synchronous
now (one word, on disk before the wipe begins). And `start_up_test`'s token that could not be
deleted had expected *"Your session ended"* — exactly what W1 refuses; it now expects the
unfinished state. The test store's `unreadable` also threw for a key already deleted, which no
Keystore does; it now throws only while a value is stored.

After the round-8 audit:

| Item | What changed | Broken how | Failed |
| ---- | ------------ | ---------- | ------ |
| X1 Try again after a relaunch | A retry from `SessionSignOutIncomplete` that finishes and keeps the server calls `ref.invalidateSelf()`: start-up runs again, applying the CA pin and setting the current server before sign-in | Landing on `SessionSignedOut` without starting over (the round-7 code) | The relaunch-then-retry-then-sign-in test: no current server, so the sign-in had no client |
| X2 the marker goes before the new token | `signIn` removes a stale marker before `TokenStore.save` | The two swapped back | The ordering test (the store's `onWrite` sees whether the marker is there when the token lands) |
| A store that can never confirm | `MemorySecureStore.alwaysUnreadable`: a read throws even with nothing stored, so a sign-out ends at *"couldn't finish"* on purpose | — | Kept as a test of that path: the token is deleted but cannot be confirmed gone, and the marker stays |


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

- **No per-server pin on iOS.** `dart:io` on iOS evaluates the server's certificate against the
  system trust store, which includes every profile the person has given full trust — an
  MDM-installed inspection CA too. Pinning (`PhoneTrustAdapter`) and the "Trusted through" and
  "no longer installed" notes are Android-only. Closing it needs a platform channel that
  evaluates `SecTrust` with a pinned anchor.
- **Invitation links made in the web at `http://localhost:8080` name that origin**, so the phone
  correctly refuses them (*"That invitation is for localhost:8080…"*). Owners must create
  invitations from the server's `https://` address. A future fix is a configured public URL for
  invitation links on the server.
- **Android user-installed CAs need checking on a device.** The channel (`MainActivity`,
  `AndroidCAStore` `user:` aliases) compiles into the debug APK and its Dart side is proved on real
  sockets with test CAs, but no test here runs Android, so no test shows `AndroidCAStore` itself
  answering on a phone, nor the foreground re-read. Check on Android 7+ and 14+ (where the system
  store moved into an APEX): install a private CA, connect, see the server trusted; install a
  second CA and serve a certificate it issued at the same address, see it refused; remove the
  first in Settings, switch back to the app, see the server refused.
- **A CA already intercepting when the person connects is the one pinned.** Pinning protects a
  server from a user CA added *after* it was connected to (the device-management case). If an
  interception CA is in place at the moment of connecting, it is the CA that verifies the server
  then, and it is pinned. Changing server and connecting again re-pins. The mitigation is that
  the pin is **named on screen** (Connect, More → Server): an unfamiliar CA name is the person's
  cue. The name is whatever the CA's maker wrote; an inspection CA can call itself anything.
- **A system CA the person disabled in Settings is still trusted.** Dart reads the system
  certificate directory itself, not Android's list of enabled system CAs.
- **A connection already open when the pinned CA is removed** finishes the request it is carrying;
  every connection after uses the new trust (the inner client is swapped, the old one closed once
  idle).
- **Two places still say "offline" when the real reason is the unconfirmed version**, both in
  feature code this change did not touch: the Transactions screen's message after a tapped write
  (`transactions_screen.dart`, `l.offline_write`) and every writer's `AppError.offline()`. The
  write is refused either way; `writesOffText(l, reachability)` in `shared/widgets/status.dart`
  gives the right sentence for a screen to show. After a failed start-up with no readable server
  the Connect screen shows no message of its own (it has none to show); with one, sign-in shows
  "Your session ended".
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

### Phase 2 — Accounts, categories, envelopes

Built on the Transactions pattern (`mobile/README.md`, "Adding a feature"): cache-first reads,
writers that refuse unless `reachabilityProvider.canWrite` and never queue, `version` in every
`PATCH` with only the changed fields, sheets on the root navigator, and a `VIEWER` offered no write
control. Every amount is the server's string held as `Money`; **the phone adds nothing**.

- **Accounts** (tab): active accounts grouped by currency (a heading only from the second currency
  on), each balance the server's; archived ones in their own section behind a toggle, muted; "N
  more accounts are not shown" when the page holds back rows. A row opens its actions: Edit and
  Archive, Bring back when archived, and **Delete only when the server said `empty`**. The add/edit
  sheet has the web's five fields; the currency is shown disabled with its reason once the account
  has history; a field the server refused is marked until edited (`forceErrorText`); a
  `409 stale-version` re-reads the account, names each field that moved with its new value,
  disables Save, and offers *Use theirs* (reload the form) or *Keep mine and save* (the same form
  at the version just shown).
- **Categories** (More): income then spending, each parent followed by its children drawn with a
  rule at the start; a child whose parent is archived is promoted. Archived categories behind a
  disclosure with their count. Row actions: Add a sub-category (top-level rows only), Edit,
  Archive, Restore, Delete — whose message says the transactions are kept and, with children, how
  many go too. The sheet's parent picker offers unarchived top-level categories of the kind; a
  category with children is told why it cannot move; a locked kind is a disabled field with its
  reason. **Payee rules** follow: listed with "files under …", the archived-category note, and
  Remove (none for a `VIEWER`).
- **Envelopes** (More): `GET /api/envelopes/periods` decides the month (this one, or the latest
  that has envelopes); arrows move a month either way. The month's totals are the server's. Each
  card has the remaining figure as its headline (negative and in the accent text colour when
  overspent, with "over"), a meter, "Spent so far x / y", **Log spend**, the log toggle (absent with
  nothing logged), and an actions button (Edit, Archive, Delete). The log marks every row *Typed*
  or *From a transaction*; a row opens Edit and Delete. Archived envelopes sit behind a toggle.
  Quick add opens on the amount with the decimal keypad; payee, date and note are optional.
- **Shared**: `shared/widgets/grouped_row.dart` (the ledger row's card strip for any row, and
  `showRowActions`), `shared/widgets/write_failure.dart` (below).
- **Refused writes say why** (core 5e0fd95): every write failure on these screens, and on the
  Transactions screen and its three sheets, is said through `writesOffText()` at the moment it
  fails — *offline* or *the version is not confirmed yet* — never a fixed "offline" sentence.
- **Fixtures**: `MobileAccountsFixtureIT` writes `accounts`, `categories` and `envelopes
  .generated.json`, one story each against the real API (viewer reads and a refused viewer write in
  each). `DemoFixtureIT` changed only nine modifiers, private to package-private.

#### Departures from the web

| Where | The web | The phone | Why |
| ----- | ------- | --------- | --- |
| Per-currency subtotal | Summed in the browser | **None**; rows grouped by currency only | The phone never adds amounts, and the API sends no subtotal. An API gap if one is wanted |
| Currency group heading | "US Dollar (USD)" | "USD" | `intl` has no currency display names |
| Currency field | A select of ISO codes | A three-letter field, upper-cased, validated (as Setup) | No list of codes without a new dependency; the server validates the code and its refusal marks the field |
| Archive confirmation | Mentions the subtotal changing | Mobile key `accounts.archiveMessagePhone`, without that sentence | There is no subtotal to change |
| Import link on a row | Opens Import with the account chosen | Not offered | Import is another area's screen, with no "preselect an account" entry yet |
| Untouched account edit | Sends `{version}` | Sends nothing | README rule; the web's own categories and envelopes pages do the same |
| Category trail | "Bills → Phone" | "Bills › Phone" (`transactions.categoryTrail`) | The bundled font has no arrow (phase 1) |
| Add a sub-category | Also on a promoted orphan | Only on a true top-level row | The server would refuse a third level |
| Envelope periods | Read through the client | Read **uncached**; offline the screen opens on the current month | `CachedResource` decodes objects only and the body is an array; no core change for it |
| Envelope meter | `Number(spent) / Number(amount)` | A `Decimal` ratio rounded to a whole percent | No `double` is made from an amount |
| Envelope card | Five buttons | Log spend, the log toggle, and an actions sheet | Width |
| Envelope month on create | `<input type="month">` | Previous / next arrows inside the field | Material has no month picker |
| A malformed amount from the server | — | `UnexpectedJson`, the generic error | `Money.fromWire` throws `FormatException`, which `CachedResource` does not catch; these models convert it (`wireMoney`, now in `core/money`). Phase 1's ledger models do too since the follow-up below |

#### Proving each control can fail

Each was broken, its tests run and seen to fail, then restored with `git checkout` and `git diff`
confirmed empty.

| Control | Broken how | Failed |
| ------- | ---------- | ------ |
| `VIEWER` offered no write | `canWrite = true` on all three screens | The three VIEWER widget tests (the forced-write 403 tests still passed, as they should) |
| Offline refusal before sending | The `reachabilityProvider` check removed from all three writers, the server still answering | The three offline tests: accounts and envelopes wrote for real, categories sent a `DELETE` the fixture did not record. First run hung on the fake clock instead of failing, so direct writer calls in tests now run under `tester.runAsync` |
| The refusal says why | `writeFailureText` / `writesOffNow` always say "offline" | The four version-unconfirmed tests (accounts, categories, envelopes, transactions) |
| No household id in a path | The id added to the account create query and the envelope entry path | "adding sends the web's body" and "quick add" (fixture miss, and the household-id assertion) |
| Malformed ledger figure is the error state | Ledger follow-up: a bare `Money.fromWire` for the row amount; then `pageTotal` and the date reader unwrapped; then `TransactionsWriter.transfer`'s `UnexpectedJson` mapping removed | `malformed_body_test.dart`: the three row-amount cases (an uncaught `FormatException`), then `pageTotal` and `date`, then the three transfer cases |

#### Follow-up: the ledger's malformed figures (2026-11-27)

Phase 1's `Transaction`, `Ledger` and `Transfer` models called `Money.fromWire` and
`CalendarDate.parse` directly. Both throw `FormatException`, and `CachedResource.refresh` maps only
`UnexpectedJson`, so one amount the server's `NUMERIC(19,4)` could never produce (`"1e9"`, five
decimals, sixteen integer digits) or an impossible date escaped `refresh` as an **uncaught async
error**. The screen kept its spinner, and nothing told the person the answer was unreadable.
Separately, `TransactionsWriter.transfer` mapped nothing: `ApiClient.getJson` is only
`decodeObject(getText(...))`, so an `UnexpectedJson` from it or from `Transfer.fromJson` (a body that
is not JSON, such as a proxy's 200 sign-in page, or a missing field) escaped the screen's
`on AppError` catch.

- **The fix is at the boundary, not in `CachedResource`.** `wireMoney` moved from
  `features/accounts/data/accounts.dart` to `core/money/money.dart`, so a feature no longer imports
  another feature to read an amount. The ledger models use it, and a local `_date` reader for their
  dates. `transfer()` maps `UnexpectedJson`, from the decode or the `fromJson`, to
  `AppError.unexpected()`.
- **Why not widen `refresh`'s catch to `FormatException`:** it would not reach the transfer read,
  which does not go through `CachedResource`. It would also loosen the `decode` contract ("throw
  `UnexpectedJson`") that every other repository and `ApiClient.getJson` share. The only core change
  is where `wireMoney` lives, and its behaviour is unchanged.
- **A refused body is never cached:** `decode` runs before the cache write, and the test asserts the
  cache holds no entry for the key.
- Tests: `test/features/transactions/malformed_body_test.dart` (recorded demo answers with one field
  changed: the ledger's generic error panel, no exception, nothing cached; the transfer read is
  `AppError.unexpected`, and so is one that is not JSON at all; the untampered bodies still read). `test/core/money_test.dart` checks that
  `wireMoney` names the field and never the value.

#### What reading the screenshots changed

At text 2.0 the form hints were cut off with an ellipsis (now up to ten lines) and the dropdowns cut
the chosen value mid-word (`isDense: false`, which lets it wrap). A server-refused field was not
marked: a validator re-run after the round trip did not stick, so the mark is `forceErrorText`. A
locked kind drawn as a disabled segmented button looked enabled in light mode: it is now a disabled
field with its reason. The archived and payee-rule goldens scroll to what they are about, and a
`VIEWER` payee-rules golden was added.

#### Known gaps

- **No per-currency subtotal** (above). It needs a server figure.
- **These fixtures record the Transactions tab's launch reads** (`/api/auth/me`, accounts,
  categories, the ledger) in every state, because every scenario starts there. If another area
  changes what that tab reads at launch, `MobileAccountsFixtureIT` must be regenerated.
- **Payee rules are seeded as stored state** in the fixture (SQL, as `CanvasWeek` seeds a charge
  rule); the API creates them only by completing a check-in.
- An envelope's log shows its **first 50 entries**, as the web's does; there is no paging.
- More's child screens have **no back arrow** (as Devices in phase 1): the More tab and the system
  back gesture return.

### Phase 2 — Household, preferences, admin

**Built.** Three More entries replace their placeholders in `destinations.dart`; no child route
was needed (the invite sheet is a full-screen dialog on the root navigator).

- **Household** (`features/household/`): the household's name and base currency, editable by an
  `OWNER` (`PUT /api/households/current`, both fields as the web sends them; then every live read
  re-reads and the member is re-read for the name) and shown as disabled fields to anybody else
  with *"Only an owner can change these."* The member list: name, *You*, email, joined date. An
  owner gets a role picker and **Remove** on every other row; the only owner is told why they
  cannot leave; anybody else gets **Leave** on their own row and role labels elsewhere. A role is
  changed by choosing it (`PATCH …/members/{id}` with `{role}`), as on the web; **Remove** and
  **Leave** are confirmed in the web's words. Leaving re-reads the member at once: the server
  revoked every token the member had, so that `401` wipes the token, the cache and its key and
  lands on sign-in with *"Your session ended"*, as the web routes to `/login?reason=expired`.
- **Invite sheet**: email and role (with the web's role descriptions) → `POST …/invitations`. The
  link is built from **the server this phone is connected to** and the response's relative
  `acceptPath`, refused unless it stays on that origin and is exactly `/join/<token>`
  (`buildInviteLink`). It is shown once, selectable, left to right in every layout, with the web's
  *"treat this link like a password"* warning, role and expiry; **Copy link** is the only thing
  that puts it on the clipboard, through the native `app.budgetowl/clipboard` channel: on
  Android 13+ **marked sensitive** (`ClipDescription.EXTRA_IS_SENSITIVE`; below 13 nothing can
  mark it, so it is a plain clip); on iOS **`localOnly`** (never carried to the person's other
  devices by Universal Clipboard) and **expiring after ten minutes**. **Revoke link**
  (`DELETE …/invitations/{id}`) takes it off the screen too; closing the sheet drops it. On
  revoke and on close the sheet hands back the copy's receipt — what the native side returned
  at the copy, **kept in Dart** so an activity recreated in between can still compare (round 6,
  V2) — and the native side clears the
  clipboard **only if it still holds that copy** — decided **without reading the clipboard**:
  Android compares the clip's `ClipDescription` timestamp (below Android 8, where there is no
  "pasted from" notice, a SHA-256 of the clip), iOS compares `UIPasteboard.changeCount`. So
  Android 12+'s *"pasted from your clipboard"* notice and iOS 16+'s *"Allow Paste?"* prompt are
  never shown, and whatever the person copied since is left alone. Android 10+ lets no app that
  is not focused see the clipboard, so a take-back asked for then is checked when the window next
  has focus (`SensitiveClipboard`, MainActivity, AppDelegate). The token never reaches the cache, secure storage, a
  provider or a log: the response goes through `ApiClient.write`, and `Invitation` keeps only
  `acceptPath` and prints only its id.
- **Preferences** (`features/preferences/`): display currency and language for the member, saved
  with `PATCH …/members/me` (both fields always; *follow the household / this device* is `null`),
  allowed for a `VIEWER`; then the member is re-read and every live read re-reads. **Appearance**:
  Automatic / Light / Dark, applied at once, offline too, kept on the phone only.
- **Admin** (`features/admin/`): the web console's three blocks. *This instance* (version, set-up
  date, administrator, account count); *Signing in* (registration with its consequence in the
  flow and *"Saving now will open registration"*; password sign-in as a stated rule while locked,
  as a switch otherwise, with `deploy/restore-password-login.sh` named before it is turned off;
  single sign-on with issuer, client id, secret, provisioning and whether an owner has proved
  it); *Accounts*, read-only. The `PATCH /api/admin/settings` body is the web's
  `buildSettingsPatch` rule for rule (only what changed, `version` always, the secret only when
  typed and exactly as typed, *provider off* says only that). Turning the provider off is
  confirmed; cancelling puts only that switch back. A `409 stale-version` re-reads, names the
  settings that moved (names only), keeps what was typed and offers *Take the latest* / *Save my
  changes anyway*; a `409 password-login-locked` is shown and re-read. Shown only when
  `/api/auth/me` says `instanceAdmin`; a deep link from anybody else lands on More (phase 1's
  `redirectFor`, unchanged) and the API's `403 not-instance-admin` is shown translated if met.
- Every write is offered only when `canWriteNow`, refused again by its writer, and a refused or
  failed write is shown with `writesOffText` (offline, or the version not yet confirmed) or the
  server's translated code (`writeErrorText`). Nothing is queued.

**Fixtures**: `MobileHouseholdFixtureIT` (its own class) records `household.generated.json` from
the real API: 18 states, from each role's reads and forced writes to the console's conflict and
lockout paths. Bearer and invitation tokens are registered as secrets, so the file holds
`fixture-token-<who>` and `fixture-invitation-edsger` (in `token` and in `acceptPath`); the OIDC
client secret is a value the class makes up and names `placeholder-…`; `setupCompletedAt` and
`oidcOwnerLoginAt` are pinned like the other timestamps. `grace-demoted` deliberately pairs what
Grace's phone read while an owner with the server's answer after her demotion, which is the only
way to record "offered, then refused". `DemoFixtureIT` changed **only** in six modifiers
(`DEMO_TODAY`, `MOBILE_FIXTURES`, `Caller`, `MobileRecorder`, `compareOrWrite`,
`canonicaliseMobile`: `private` → package-private).

**Departures**

| Where | The web / the spec | Built | Why |
| ----- | ------------------ | ----- | --- |
| Theme override | Web: a masthead light/dark toggle in `localStorage`; Data model: shared prefs | Automatic / Light / Dark under Preferences, in a one-word file in Application Support (`budget_owl.theme`) | `guards_test` bans `SharedPreferences` from the app; secure storage is for credentials and is wiped on sign-out, while a theme outlives a sign-out as on the web. The file holds only `light`, `dark` or `system`; anything else reads as `system` |
| Language preference | Web applies it to text and to date and number formatting | **Saved, not applied** on the phone; the hint says so (`preferences.localeHintPhone`) instead of the web's *"Dates and numbers always follow your choice"* | Only English ships and `context.localeName` comes from `Localizations` in core; applying a member's locale is a cross-cutting core change. Known gap below |
| Currency names | Web: `Intl` currency names in a select | ISO codes with intl's symbol (`EUR · €`), from a list in `household/domain/currencies.dart` | Dart's `intl` has no currency names or code list. The server re-validates every code |
| Locale names | Web: `Intl.DisplayNames` | A table of endonyms beside the web's 21 tags | No display-names API in Dart; endonyms read the same in every language, so they are data, not ARB keys |
| Dropdown value at large text | — | One line with an ellipsis; the open menu shows every choice in full | A wrapped value was clipped to its first line |
| Recovery command | Web: its own line, wrapping | One line that never wraps and scrolls sideways with a visible bar | A hyphen a line break adds cannot be told from one in the filename |
| OIDC secret field | Web: `maxlength` | No character counter | A counter would show the length of the secret typed |
| Household switching | Asked for if the web has it | **None** | An instance holds exactly one household (ADR-0026) and the web has no switcher. `CachedResource` keys by household and session epoch; leaving ends the session and the wipe removes every cached copy |
| Role change | — | No confirmation; a picker, as the web's `<select>` | Parity: the web confirms removal and leaving, not a role change. The server refuses what it must |

**Sabotage proofs** (each broken, tests run and seen to fail, restored with `git checkout`, `git
diff` empty):

| Control | Broken how | Failed |
| ------- | ---------- | ------ |
| Link only on this server | `buildInviteLink`'s origin and `//` checks removed | `requests_test`: refused when the path would take it to another host |
| Copy only when asked | `_copy()` called as soon as the link was made | `household_test`: the clipboard held the link before the tap |
| Token never cached | The invitation body written to the cache in `HouseholdWriter.invite` | `household_test`: 7 cache entries, expected 6. (The first version of this test read the cache file's bytes, which SQLCipher encrypts, so it could never have failed; replaced) |
| Secret sent only when typed | The secret always sent | `requests_test` (blank not mentioned); two `admin_test` cases (bodies the fixture does not record) |
| Issuer https | `isIssuerUrl` allowed any `http` | `requests_test`. The widget test **did not fail at first** (an empty secret stopped the save for its own reason); it now types the secret first and failed: the secret was sent to `http://id.example.org` |
| Admin deep link | The `/more/admin` line of `redirectFor` removed | `admin_test`: `AdminScreen` was built |
| Admin hidden in More | The `adminOnly` filter removed | `admin_test`: a non-admin `OWNER` saw Admin |
| Leave leaves nothing | The re-read after leaving removed | `household_test`: no "session ended"; token and cache left |
| Writer refuses offline itself | The writers' `canWrite` check removed | **Nothing failed at first**: the tests also took the server offline, so the socket refused instead. The tests now keep the server reachable while the phone records "offline"; then `household_test` (a write was served: a fixture miss) and `admin_test` (not refused) failed. A hang under the sabotage now fails on a 10 s bound in the test helper |
| Role gating (UX) | `controlsFor` gave every row an owner's controls | `household_test`: a `VIEWER` saw two role pickers |
| Secret obscured | `obscureText: false` | `admin_test`: obscured expected |

**What reading the screenshots changed**: in RTL the currency read "$ · USD" (codes now draw left
to right); at 2× helper text was cut with an ellipsis (it now wraps) and a dropdown value was
clipped (one line, ellipsis); a viewer's read-only fields looked editable (now disabled fields);
offline showed two stale labels (now one) and the error state a redundant offline banner;
"Follow this phone" wrapped inside its segment (now "Automatic", explained in the hint); the
recovery command was cut at 2× with no sign it continued (scrollbar); the secret field showed a
length counter (removed); three scenarios scrolled past what they were meant to show. 58 PNGs in
`household_golden_test.dart`, each read.

**Known gaps**

- **The member's language is not applied on the phone** (above). Needs core: a formatting locale
  in `core/l10n/l10n.dart` from the membership, and date symbols initialised.
- **At text 2.0 the shared `Screen` header breaks "Administration" mid-word** (one word wider than
  the phone). A shared-widget fix (scale a title down to fit) for every area; not done here.
- **The invite link wraps at a hyphen**, as the web's text box does; *Copy link* copies it exactly.
- **A start-up retry does not revoke the old token** (round 8, X3; accepted). When a sign-out's
  revocation did not reach the server and its delete failed, a later launch ends the session
  again without loading the token, so it cannot revoke it. That token stays live on the server
  until it is revoked under *Devices* on the web — the device list there shows it.
- **An unfinished sign-out is remembered only if Application Support can be written.** The
  marker is best effort: on a phone where that write also fails, closing the app on *"couldn't
  finish signing out"* and opening it again resumes with the surviving token, as before round 7.
  The wipe still ran (cache, key and the rest are gone), and the screen said it had not finished.
- **Below Android 13 a copied link cannot be marked sensitive** (round 5, U4): the platform has
  no such flag, and nothing more is possible. It is a plain clip — shown in a keyboard's
  clipboard suggestions — until the sheet takes it back on revoke or close. If the app is killed
  with the link on the clipboard, it stays there, and so does a take-back Android deferred
  because the app was not focused when the process ended. On iOS the copy is `localOnly` and
  expires after ten minutes by itself, killed app or not.
- **The clipboard needs checking on a device.** Tests prove what the Dart side asks the native
  side for, and `platform_config_test` pins the Kotlin and Swift, but no test here runs a real
  clipboard, and the Swift is not compiled on this Linux machine. Check on Android 13+ that the
  copy preview hides the link; on Android 12+ that taking it back shows no "pasted from" notice;
  that copying something else after the link is never cleared; and that closing the sheet while
  the app is in the background clears the link when it comes back. Check on iOS 16+ that no
  "Allow Paste?" prompt appears, that the link does not reach a Mac through Universal Clipboard,
  and that it is gone after ten minutes. **`AppDelegate.swift` needs an Xcode build.**
- **An administrator with no household membership cannot reach the console on the phone**: core's
  `redirectFor` sends every signed-in route to *not in a household* first. The web allows it.
- **The invite-link golden has no 2× variant**: the test driver does not land the create tap at 2×
  (the state was rendered at 2× before the second core merge and read). `invite-form` keeps its
  2× golden.
- **No `version` on household, membership or invitation writes**: the API has none and the web
  sends none, so two owners renaming at once is last-write-wins on the server.
- No API gap was found: every web behaviour in this area has its endpoint.

### Phase 2 — Bills & income, debt plan, goals

Built to the web's behaviour, screen by screen, from `bills-and-income.md`, `debt-plan.md` and
`goals.md`, following the phase-2 recipe in `mobile/README.md` (Transactions is the reference).

**What was built**

- **Bills & income** (bottom-nav tab, replaces its placeholder). A month bar (previous/next
  month, each a read of that month). Cards: *Left this month* (the server's summary: income,
  bills, envelopes, debt minimums, goals, short/left), *Income* (sources, each arrival, retired
  sources with Restore), *Your bills* (each occurrence: budgeted/paid, overdue, surprising, not
  counted; *Mark paid* one tap; *Correct this* opens Mark paid / Paid a different amount… / Skip /
  Mark as due / Forget; mappings with *Unmap*; retired bills with Restore and Delete), *Owed*
  (links to the debt plan) and *Envelopes*. Row actions (Edit, Record a payment for an irregular
  bill, Retire, Delete) sit behind a ⋮ sheet. Sheets: add/edit bill (the schedule editor shows
  only the kind's own parameters), add/edit income source, record a payment.
- **Debt plan** (More). Monthly amount with an inline edit, Avalanche / Snowball / *Your own
  order*, the *Balance to zero* chart, the two strategy cards side by side with the comparison
  sentence and the what-if (*"Adding $50.00 a month…"*), the ladder (rung cards with Open, Edit
  terms, and Move up/down under your own order), debts outside the ladder, the empty state, and
  the *extra exceeds what is left* and *below the minimums* states. Sheets: add a debt, edit terms
  (the three-way rate: *Work the rate out for me* releases it with an explicit `null`).
- **Debt detail** (`/more/debt-plan/:id`, the id, never a name). Previous/Next stepper, Log a
  payment, Edit terms, Confirm the balance; the terms grid with each figure's source (Solved,
  From your plan); the balance card (confirmed, estimated or stale); *Balance over time* (what you
  are paying against the minimum, with what the extra buys); payment history with each split.
  Sheets: log a payment (scheduled and +50 quick amounts, a server preview of the split, debounced
  350 ms, late answers dropped, a failed preview never blocks the save), confirm an estimate
  (confirm, correct it, stop estimating, log a payment instead).
- **Goals** (More). Goals grouped by currency, each with its progress bar and the projection in
  words; archived goals behind a toggle; the empty and read-only states. Sheet: add a goal, with
  a server preview of the projection as it is typed.
- **Goal detail** (`/more/goals/:id`). Set money aside (opens with this month's still-to-come),
  Take money out (its own sheet, unsigned field), Edit (with preview), the plan (*Change*: this
  month or next, with preview), the history with Remove and **Show more** (the web's paging,
  50 a page, read live and not cached), Archive / Restore and Delete.

Charts are `CustomPaint` over the server's series (`shared/widgets/months_line_chart.dart`): no
charting dependency, no figure computed on the phone, mirrored in RTL, with a semantics
description. **No dependency was added.**

**Fixtures.** `MobilePlansFixtureIT` (its own class; `DemoFixtureIT` only had ten members made
package-private, nothing restructured) seeds a plans household through the API and records three
linear state chains, so each write's re-reads reflect only the writes before it:
`bills.generated.json`, `debt.generated.json` and `goals.generated.json`. Every write the screens
send is recorded in the state it is sent from, with the 403 a VIEWER gets and a 409 for an edit
that lost a race.

### Phase 2 (plans) — departures

| Where | The web | What was built | Why |
| ----- | ------- | -------------- | --- |
| Debt ladder | A table on desktop, cards on narrow screens | **Cards only** | The phone is the narrow layout |
| Row actions | Buttons on each row | **A ⋮ sheet** per bill / source; occurrence corrections behind *Correct this* | Five buttons per weekly bill do not fit a phone row; *Mark paid* stays one tap |
| Currency field | A list of currencies | A **typed three-letter code**, optional, offered only when no account sets it | `intl` has no list of currency names to offer; the server validates the code |
| Debt edit, balance | The form shows balance and date, and the `PATCH` ignores them | **Read-only on edit**, with *"the balance is never worked out…"* | A field that silently does nothing is a lie; the balance moves only through *Confirm the balance* |
| Goal links | *"Open →"*, *"← All goals"* | Mobile strings **`goals.openTrail`** *"Open ›"*, **`goals.openNamed`** *"Open {name}"* (its accessible name), **`goals.backToAll`** | The bundled Figtree has no U+2192/U+2190 (it drew as a box), as in phase 1 |
| Record-payment amount | Prefilled with the expected figure | Prefilled **trimmed** (`45`, not `45.0000`) | The wire's scale is not what anyone types |
| Charts in RTL | — | **Mirrored**: time runs right to left | The reading direction of the page |
| Deleting a goal | Navigates to the list | The page **stops reading the goal, then leaves, then** every screen re-reads | Otherwise the page, still mounted while it animates away, re-read a goal that no longer exists (three 404s) |
| Phase-1 tests | — | The `placeholder` golden scenario, its two PNGs and the *"a screen not built yet"* test are **deleted** (by the coordinator's decision) | They tapped Bills and Goals, now built; after phase 2 no placeholder screen remains. `PlaceholderScreen` itself stays until every area is merged |
| Charge-rule fixture | — | The one mapping is a **direct SQL row** in `MobilePlansFixtureIT`, against a payee the API made | Rules are written only by completing a check-in (ADR-0041); no route creates one. The fixture records the API reading it back |

### Phase 2 (plans) — proving each control can fail

Each was broken, its tests run and seen to fail, then restored with `git checkout` and the tree
confirmed clean.

| Control | Broken how | Failed |
| ------- | ---------- | ------ |
| VIEWER sees no write control | `canWrite` true for anyone signed in, in each of the five screens in turn | The VIEWER test of that screen, each time (*"Add a bill"*, *"Add a debt"*, *"Log a payment"*, *"Add a goal"*, *"Set money aside"* found) |
| Offline refusal before a sheet or confirmation | Each screen's `_offline()` never refusing (bills, debt plan, debt detail, goal detail, goals list) | The *"with writes off"* test of that screen: a sheet opened, or the wrong sentence |
| The writer's own refusal | `if (!canWrite) throw` disabled in each of the three writers | *"the writer itself refuses"*: a request reached the server while the phone believed it was offline. (The first version of this test ran with the network down too, and passed with the check removed. It now proves the writer's own check, not the transport's) |
| No household id in a request | A `householdId` in the bills query; `/api/households/…` in the goal paths; a `household` in the debts query | The *"none naming a household"* reading test of each area (a fixture miss and the assertion) |
| Goals routed by id, never by name | The list opening `/more/goals/<name>` | *"by id and never by name"* |
| Every `PATCH` carries its version | The version dropped from the bill, income, debt and goal patch builders | 12 request tests across the three areas, against the bodies the real API accepted |
| The refusal says why | `writeErrorText` always saying *"offline"* | The three *"with the version unconfirmed"* tests |

### Phase 2 (plans) — what reading the screenshots changed

The goal row's *"Open →"* drew its arrow as a box (now *"Open ›"*). The goal sheet's empty month
field floated its label over nothing (now `isEmpty`). Sentences that begin with a figure
reordered in RTL (the `Sentence` widget keeps language direction and layout-start alignment).
An error state rendered against the wrong fixture state. Accepted: in the RTL variant an English
sentence's final full stop sits at its left end, as an English string laid out RTL does; real RTL
translations do not have this.

### Phase 2 (plans) — known gaps

- **Debt payment history shows its first 50 payments** and then the web's own note (*"Showing the
  most recent 50…"*), as the web does; only the goal history pages.
- **No multi-currency debt or goals fixture.** The screens group by currency as the server does,
  but every recorded household is in USD, so no golden shows two ladders.
- **Charts have no touch read-out.** They state their figures in the sentences beneath them and
  in a semantics description; there is no tap-for-value.
- **A malformed date escapes the plans models the way the ledger's did.** `debt.dart`, `bill.dart`
  and `goal.dart` call `CalendarDate.parse` directly in their `fromJson`s, and their repositories
  map only `UnexpectedJson`. Their amounts are safe: `json.amount` lexes them at the boundary. The
  fix is the ledger's (see "Follow-up: the ledger's malformed figures"), ideally as a `wireDate`
  next to `CalendarDate` in core, replacing the private copies in `envelope.dart`, `check_in.dart`,
  `import.dart` and `transaction.dart`.
- **`AccountsWriter.latest` has the transfer read's old gap** (`accounts.dart`): `getJson` and
  `Account.fromJson` run outside any mapping, and the account sheet catches `AppError` only, so a
  non-JSON or malformed body after a `409` escapes as an uncaught error. The fix is the same
  `try … on UnexpectedJson` that `TransactionsWriter.transfer` now has.
- **The month bar reads any month** the user steps to; only July, August and September 2026 are
  recorded, so tests do not step further.

### Phase 2 — Check-in, insights, import

**Built.** Three areas, at parity with the web, reading through `CachedResource` and writing
online-only through writers that are never queued:

- **Check-in** (bottom-nav tab, `features/check_in/`). One step per screen: What's new,
  Categorize, Bills, Next week. Each screen names the next ("Next: categorize"), Back and forward
  keep every answer (the answers live on the server), and the step header shows progress. Not
  started: the computed week, read-only, with **Start check-in** (`PUT /api/check-ins/{week}`, no
  body). Draft: every answer control (prepaid, leave it, skip, mark paid with the debt-plan prompt,
  a category from a sheet with "Always file this payee here", attach to a bill from a sheet, "Yes,
  map it" to the server's suggestion, not a bill, and the allocation radio group of goal, debt and
  roll over). A replacement answer carries the answer's `version`. Completion sends
  `{version, basis}`. Discard asks first. The completed week shows its summary lines, each linking
  where the web's does. Refusals: `stale-version` (someone else answered first: said, and their
  answer shown), `check-in-stale` (finishing is refused whole: every listed item marked, the first
  one's step opened and scrolled to), `check-in-draft-open` (another week's draft is offered with
  **Open it**, read with `GET /api/check-ins/{week}`, and **Back to this week**), and completed or
  not-found (read again). A `VIEWER` sees every answer and figure and is told why there is no
  control. Offline, or with the version unconfirmed, the controls are disabled and `OfflineNote`
  gives `writesOffText()`.
- **Insights** (`features/insights/`). Report 1, income and spending: 12, 6 and 3 month chips
  ending with the phone's own month, a currency switch when there are two, the chart (income solid,
  spending dashed; a point's height is a ratio the phone gets from `Decimal`, never a `double`),
  the legend, the negative and coincident notes, the uncategorised line with its link, and the
  figures table (stacked per month). Report 2, where it went: the last complete month, stepping
  back and forward (never past the current month), share bars, show all, children. Both reports
  export CSV. Empty is a sentence, not a flat line. A `VIEWER` sees what an owner sees, export
  included: export is a read.
- **Import** (under More, `features/import/`). Account and file (the system picker), format from
  the file name, the >5 MB courtesy refusal before anything is sent, preview (counts, the balance
  check, rows that need attention, the suspected duplicate ticked to include), the CSV mapping
  (roles, date format, delimiter, decimal and grouping separators, header row, direction values,
  with the server's suggestion and a saved mapping read first), commit, the done screen with
  **Undo** (`DELETE`), and recent imports (page 0, size 10). `import-preview-stale` (someone
  imported first) says so and previews again. A `VIEWER` gets a sentence, not a form. Offline or
  unconfirmed: preview and commit are disabled with `writesOffText()`.

Fixtures come from the real API through `MobileWeeklyFixtureIT` (`check-in`, `insights`,
`insights-states`, `import`). Goldens: `check_in_`, `insights_`, `import_golden_test.dart`, with
light and dark throughout, RTL and text 2.0 on each area's main screens, and VIEWER, offline-stale
and error for each.

**Departures, and why.**

- **Import sends JSON with base64, not multipart.** That is the API's contract (multipart is off
  server-wide), so `FixtureServer` needed no multipart support.
- **The CSV's byte-order mark is restored when writing the file.** Dio's UTF-8 decoding drops the
  server's BOM, and spreadsheet apps need it to read UTF-8. The test asserts the first three
  bytes.
- **Another week's open draft is screen state, not a route.** **Back to this week** returns to it.
  `router.dart` is unchanged.
- **Answers, start and discard re-read the check-in only.** Completion bumps `dataRevision` (it
  changes the budget); a draft answer changes nothing elsewhere until then, as on the web.
- **Two strings are phone-only** because Figtree has no glyph for the web's "→" and "≈":
  `checkIn.next.perDayPhone` and `checkIn.choice.goalMovesPhone`. Also phone-only:
  `checkIn.backToCurrent`, `insights.monthRange`, `insights.shareFailed` (an export that could not
  be shared is the phone's failure, not the server's), `import.chooseFile`,
  `import.readFailedPhone` and `import.kilobytes`.
- **The CSV mapping's sample records are stacked cards**, since a wide table does not fit a phone.
  The figures table in Insights is stacked per month for the same reason.
- **The chart is not mirrored in RTL.** Time runs left to right in both, as the web draws it.
- **"See them in the ledger" opens Transactions unfiltered.** Transactions has no route filter
  yet.
- **Transactions' pager is now a `Wrap`.** It overflowed at text 2.0 with more than 50 rows, a
  phase-1 bug found while reading goldens.
- **Core changes, the minimum:** `ApiClient.getText` takes `headers` (for `Accept: text/csv`);
  `destinations.dart` points the three placeholders at the new screens. Test support:
  `FixtureServer` serves a `String` body as is (the CSV), and `pumpApp` takes `overrides` (the fake
  picker and sharer). `DemoFixtureIT`: `DEMO_LEDGER` and `DEMO_TRANSFERS` made package-private,
  nothing else.

**Plugins and permissions.** Both are pinned in `pubspec.lock`, and neither has analytics or
network calls of its own.

- `file_selector` 1.1.0 (flutter.dev; `file_selector_android` 0.5.2+11, `_ios` 0.5.3+7,
  `_platform_interface` 2.7.0, `cross_file` 0.3.5+5). It opens the system document picker, which
  needs no permission on either platform, and adds nothing to the merged manifest. It was chosen
  over `file_picker`, whose federation is wider and whose no-copy mode cannot return bytes. Both
  platforms copy the picked file into the app's cache (Android `cacheDir/<uuid>/`, iOS `tmp/` via
  `asCopy`). `SystemStatementPicker` reads the bytes into memory, then deletes that copy in
  `finally`, but only a file inside the temporary directory, together with its emptied folder. A
  copy whose path never came back (the pick failed, or the app died first) is swept at the next
  start-up and every wipe: a real directory directly under the temporary directory, named as a
  UUID, holding exactly one regular file (Android only; links are never followed). On iOS the
  copy is in `tmp/<bundle id>-Inbox/` — the real `tmp/`, not Dart's temporary directory, which on
  iOS is `Library/Caches` — so the picker deletes it against that root, and the start-up and wipe
  sweep empties the Inbox (AppDelegate's `app.budgetowl/files` channel says where both are; round
  5, U2). Before round 5 an iOS pick's copy was never deleted. The copy's folder and the root
  are compared with their links resolved (`/var` is a link to `/private/var` on iOS), and the
  inside-the-temporary-directory check is made after resolving, so a folder that links outside
  is refused (round 6, V3). The app never writes the
  statement itself.
- `share_plus` 13.3.1 (fluttercommunity.dev; `share_plus_platform_interface` 7.2.0, and
  `url_launcher_*` on desktop and web only). It adds a non-exported `FileProvider` (authority
  `${applicationId}.flutter.share_provider`, `cache/share_plus/`) and a non-exported receiver.
  Android copies the shared file into `cache/share_plus/` and clears it only on the next share. A
  dismissed or failed share's copy is deleted at once. After a successful share the copy is left
  for the receiving app and swept, only files named like our exports, on the next export, when
  Insights opens, at the next start-up and at every wipe (sign-out, `401`, change of server).
  `budget-owl-export/` is swept whole at the same times, so an export the app was killed holding
  does not outlive the session. iOS shares the original.
- **Permissions.** The merged manifest holds only `INTERNET` and AndroidX's app-private
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `AndroidManifest.xml` strips every storage and media
  permission with `tools:node="remove"`, so a future plugin cannot add one silently, and
  `test/architecture/storage_permission_test.dart` pins that and an iOS `Info.plist` with no photo
  or file-sharing keys.

**What stays off disk.** Statement bytes live in `ChosenFile`, in memory, and are sent as base64.
`import_test` finishes an import, then scans the cache, support and system temporary directories
for text from the statement, raw and base64, and finds none. CSV text is written only to
`<temp>/budget-owl-export/<name>` and deleted in `finally`, whatever the share's outcome.
Import previews, CSV text and the bodies of answers sent never pass through `CachedResource`.
Only the normal read responses are cached: the week (which shows its answers, as the web's read
does), the two reports and import history. Nothing in these areas logs anything.

### Proving the phase-2 controls can fail

Each was broken, its tests seen to fail, and the file restored with `git checkout` (`lib/` diff
empty).

| Control                              | Broken how                                            | Failed                                                                                                                                                                                                                                                                                              |
| ------------------------------------ | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Export file deleted                  | The `finally` delete removed                          | `insights_test`: shared, dismissed and failed (3)                                                                                                                                                                                                                                                   |
| BOM kept                             | The BOM restoration removed                           | `insights_test`: the server's own file, by bytes                                                                                                                                                                                                                                                    |
| Picker copy deleted                  | `forgetPlatformCopy` not called                       | `import_test`: picker deletes on success, and when reading fails                                                                                                                                                                                                                                    |
| Delete only inside temp              | `_isInside` returned `true`                           | `import_test`: never deletes a file outside the temporary directory                                                                                                                                                                                                                                 |
| Statement never on disk              | The picked bytes written to the system temp directory | `import_test`: not a file, not the cache                                                                                                                                                                                                                                                            |
| Check-in writer refuses offline      | The writer's `canWrite` check removed                 | **Nothing failed at first**: the test also took the fixture server offline, which refused instead. The test now keeps the network up. Then `check_in_test` offline failed. The unconfirmed-version variant stays green, because core `ApiClient` refuses a non-`GET` before sending: a second guard |
| Import writer refuses offline        | The same in `ImportsWriter`                           | The same story; then `import_test` offline failed                                                                                                                                                                                                                                                   |
| Check-in `VIEWER` gets no control    | `_canWrite` true for anyone signed in                 | `check_in_test`: viewer not started; viewer draft                                                                                                                                                                                                                                                   |
| Import `VIEWER` gets no form         | The role branch skipped                               | `import_test`: viewer told plainly; the viewer's preview                                                                                                                                                                                                                                            |
| Replacement answer carries `version` | `withVersion` returned the body unchanged             | `requests_test`; `check_in_test`: always file at its version; re-answering a stale item                                                                                                                                                                                                             |
| No storage permission                | One `tools:node="remove"` line deleted                | `storage_permission_test`                                                                                                                                                                                                                                                                           |

### Phase 2 known gaps (check-in, insights, import)

- **Cross-area pieces not built here:** the check-in prompt on Transactions, payee rules on
  Categories, and Unmap of a charge rule on Bills. The check-in summary links to those tabs and
  entries as they stand.
- **The platform copies need checking on a device.** No test here runs the real picker or share
  sheet. Check on Android that `cache/<uuid>/` is empty after picking (and after a pick that
  failed, once the app restarts), and that `cache/share_plus/` is emptied on a dismissed share,
  the next Insights visit, a sign-out and the next start. Check on iOS that `tmp/` (its
  `<bundle id>-Inbox/`) holds no
  statement copy.
- **A shared export stays in `cache/share_plus/` until the next export, Insights visit, start-up
  or wipe** (Android): the receiving app may still be reading it.
- **An import history screen** beyond the recent-imports list is not built. The web has none
  either.
- No backend API gaps were found for these three areas.
