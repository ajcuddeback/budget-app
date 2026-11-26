# Budget Owl for phones

The Flutter app for iOS and Android (ADR-0019). It talks only to the server its user types in,
over HTTPS, with a bearer token, and keeps an encrypted cache of what it last read (ADR-0042).

**The spec is [`docs/features/mobile-app.md`](../docs/features/mobile-app.md)**; its last section
says what is built, what departs from the spec and what is still a gap. House rules are in
[`docs/guides/flutter-style.md`](../docs/guides/flutter-style.md). This file is how to add to the
app without breaking the patterns phase 1 set. **Transactions is the reference feature**: when in
doubt, do what `lib/features/transactions/` does.

## Running things

```bash
tools/verify.sh mobile        # the gate: format, generators, analyze, guards, tests, coverage, debug APK
tools/mobile-check.sh         # re-render every golden and list the PNGs — then READ them
tools/mobile-tokens.sh        # after a token or font change in design/ or frontend/src/styles
tools/mobile-l10n.sh          # after a change to frontend/src/i18n/en.json or l10n/mobile.en.json
cd mobile && flutter test test/features/screens_test.dart   # one file
```

Fixtures come from the real API, never from a hand:

```bash
mvn -f backend test -Dtest='DemoFixtureIT#the_committed_mobile*' -Dfixtures.write=true
```

A debug build may talk to `http://localhost`, `127.0.0.1` or `10.0.2.2` (the emulator's host);
nothing else, and a release build to none of them. There is no default server address anywhere.

## Layout

```
lib/
  app.dart, main.dart        the app widget; ProviderScope with retries off (see Gotchas)
  core/                      shared by every feature — changes here need a security review
    server/                  ServerAddress rules, the /api/meta check
    http/                    ApiClient (bearer, no redirects, 401 → wipe), reachability
    auth/ storage/           TokenStore, the one SecureStore
    session/                 SessionController: connect, sign in, sign out, wipe
    cache/                   SQLCipher cache, CachedResource (cache-first reads)
    money/ dates/ errors/    Money and Decimal, CalendarDate, AppError → ARB sentence
    routing/                 Routes, redirectFor, destinations.dart (tabs and More)
    theme/                   tokens.dart (GENERATED), app_theme.dart (Palette)
    l10n/ privacy/ platform/ config/
  features/<feature>/
    data/                    resources (reads) and writers (writes)
    domain/                  plain classes and request bodies, no Flutter
    presentation/            screens and sheets
  shared/widgets/            Screen, Gutter, Lede, banners, SnapshotView, confirmAction, Rules
  l10n/                      app_en.arb and gen/ (GENERATED — never edit)
l10n/mobile.en.json          the phone's own strings (keys the web does not have)
test/
  support/                   FixtureServer, TestPhone, pumpApp, settle, tapVisible
  fixtures/                  *.generated.json from DemoFixtureIT (never edit)
  architecture/              guards: grep-level rules and the platform configs
  core/ features/ journey/ goldens/ tooling/
tool/                        the token and string generators
```

## Adding a feature (phase 2)

Say the feature is Accounts. Read `docs/features/accounts.md` and the artboard description it
points to first.

### 1. Route

Open `lib/core/routing/destinations.dart`, find the Accounts entry and replace its
`PlaceholderScreen` with `const AccountsScreen()`. That is the whole routing change: the tab, the
More order and the admin-only flag already match the web. A detail screen is a child route in
`router.dart` under the tab's branch. `redirectFor` already keeps a signed-out phone out of it.

### 2. Repository: reads

A read is a `CachedResource<T>` subclass plus an `autoDispose` provider
(`lib/features/accounts/data/accounts.dart` already has one):

```dart
final class AccountsResource extends CachedResource<List<Account>> {
  @override String get path => '/api/accounts';
  @override Map<String, String> get query => const {'includeArchived': 'true', 'page': '0', 'size': '200'};
  @override List<Account> decode(JsonMap json) => [for (final a in json.objects('content')) Account.fromJson(a)];
}
final accountsProvider = NotifierProvider.autoDispose<AccountsResource, Snapshot<List<Account>>>(AccountsResource.new);
```

- You get cache-first reads, the refresh, the stale label and the household scoping for free.
  **Never put a household id in `path` or `query`**: the server resolves it (ADR-0017).
- **A list asks for one page with a fixed size.** Only that page is cached. Never loop over pages.
- Decode with `JsonRead` (`json.string`, `json.integer`, `json.objects`…). A missing or mistyped
  field throws `UnexpectedJson`, which the screen shows as the generic error. Never `as dynamic`.
- **Money is `Money.fromWire(string, currency)`.** Never `double`, `num.parse` or `toDouble()`;
  the guards fail the build. Never add two amounts on the phone: show the server's figure.

### 3. Repository: writes

Copy `TransactionsWriter`: refuse with `AppError.offline()` unless `reachabilityProvider` says the
last request reached the server, send through `ApiClient.write`, then bump `dataRevisionProvider`
so every screen re-reads. **Never queue, retry or patch a cached figure.** Send exactly the body the
web sends, `version` included; build it in `domain/requests.dart` with a unit test of the body,
and send a `PATCH` only with the fields that changed.

### 4. Screen

- Wrap it in `Screen(title:, actions:, onRefresh:, body:)`; use `SnapshotView` for the
  loading / error / stale states, and `Gutter`, `Lede` and the banners from `shared/widgets`.
- **Roles decide what is offered**: `ref.watch(signedInProvider)?.membership?.role?.canWrite ??
  false`, and do not build a write control for a `VIEWER`. The API refuses anyway; that is why a viewer's forced write
  must still show the translated `403`.
- Disable writes offline with `canWriteNow(ref)` and show `OfflineNote` / the offline message.
- Sheets and dialogs open on the **root** navigator (`Navigator.of(context, rootNavigator: true)`,
  `useRootNavigator: true`), above the tab bar.
- Colours from `context.colors` (the `Palette`), sizes from `SpaceTokens` / `RadiusTokens` /
  `TypeScaleTokens`. Insets are `EdgeInsetsDirectional`, alignment `AlignmentDirectional`. The
  guards refuse a literal colour and a left/right-only inset.
- Text grows: no fixed heights on text, and test at text 2.0 (the ledger row stacks past 1.5×).

### 5. Strings (ARB keys)

Every string is a key. **If the web has the text, use the web's key**: every key of
`frontend/src/i18n/en.json` is already in `context.l10n` with dots and dashes turned into
underscores (`accounts.empty.title` → `l.accounts_empty_title`). Only text the web does not have
goes in `l10n/mobile.en.json`; the generator refuses a mobile key that forks a web key or repeats
a web text in the same namespace. Run `tools/mobile-l10n.sh` and commit `lib/l10n/`.

Errors are never the server's words: `context.errorMessage(error)` turns a problem `code` and
`params` into the web's sentence.

### 6. Fixtures

Extend `DemoFixtureIT`'s mobile section with the exchanges the screen makes: `record.in(state)`,
`record.send(caller, method, path, body)` for a read, `record.sendThen(next, …)` for a write that
moves the server to another state. Paths must be byte-for-byte what the app sends (query order
does not matter). Regenerate with the command above. `FixtureServer` answers only recorded
exchanges, fails the test on anything else, and refuses a file that answers one request two ways.

### 7. Tests and goldens

- Widget tests go in `test/features/…`, against `FixtureServer.load('demo', state: …)` and
  `TestPhone(server: …)..signedIn('owner' | 'viewer' | 'member')`, through `pumpApp`. Assert what
  was sent (`server.served`), the state the server moved to, and what is on screen. A typed
  password or name comes from `fixtureRequest(…)`, never a literal.
- Add a `Scenario` to `test/goldens/screens_golden_test.dart`: light and dark always; RTL and
  text 2.0 for a main screen; `VIEWER`, offline-stale and error where the screen has them. Run
  `tools/mobile-check.sh` and **open every PNG it lists as changed**. A golden nobody looked at is
  not a check.
- A security control gets a test and a sabotage proof (break it, see the test fail, restore from
  git). Record it in the feature doc.

## Gotchas

- **Never close a Dio or an adapter you were handed.** `Dio.close()` closes its adapter, and the
  app has one shared transport (`httpAdapterProvider`): closing it broke every later request,
  sign-out's logout included (audit H1). The fakes now fail any request after a close.
- **A stored token is bound to its origin.** `TokenStore.save(token, origin:)` and
  `load(origin:)`; the interceptor also refuses to attach it to a request for any other origin. A
  test phone that is "signed in" carries both (`TestPhone.signedIn`).
- **Ending a token takes it out of storage first.** `TokenStore.take()`, then
  `ApiClient.revoke(token)`, then the wipe. Never send the logout while the token is still stored.
- **A release build refuses `http://` at the socket** (`cleartext_policy.dart` around `runApp`).
  The Android and iOS network configs do not govern Dart's sockets on their own.

- **Riverpod 3 retries a failed provider by default** (up to ten times with backoff). The app's
  `ProviderScope` passes `retry: noRetry`; a test container must too, or a 401 becomes a burst.
- **`dart:io` on Android ignores user-installed CAs**, whatever the network security config says.
  A server with a private CA fails as "not trusted" (fails closed). Known gap.
- **Public writes need the CSRF pair.** Setup and accepting an invitation are public but CSRF-
  protected; `csrfForPublicWrite` reads `XSRF-TOKEN` from a fresh `GET /api/setup/status`.
- **The ledger's order among same-date rows is random** (by UUID) on the server. Do not assert
  the order of two rows on the same date.
- **Generated files are not formatted by hand**: `lib/l10n/` and `lib/core/theme/tokens.dart` are
  left as their generators write them, and `--check` compares byte for byte.
- `flutter_test` disables shadows by default (`debugDisableShadows`), which draws black rings;
  `pumpApp` turns them back on so goldens match a phone.
