# Gotchas

Things that cost someone real time. Add with `/remember` the moment you hit one — the trigger
is "huh, that's not what I expected."

Format: what happened, why, what to do.

---

### `BigDecimal.equals` compares scale, `compareTo` does not

`new BigDecimal("1.10").equals(new BigDecimal("1.1"))` is **false**. Scale is part of equality.

`Money.equals` must normalize scale (`setScale(4, HALF_EVEN)`) before comparing, and comparisons
in business logic use `compareTo(...) == 0`. Assertions in tests should use
`isEqualByComparingTo`, not `isEqualTo`, or they'll fail for reasons that have nothing to do
with the behavior being tested.

*Added 2026-08-27 — with ADR-0006.*

---

### JSON numbers are doubles

Most JSON parsers, including JavaScript's `JSON.parse`, deserialize numbers as IEEE-754 doubles.
Sending `{"amount": 1234.56}` means the client may receive something that is not exactly
`1234.56`, no matter how exact the server's `BigDecimal` was.

Serialize money as a **string**. This is why ADR-0006 says so.

*Added 2026-08-27.*

---

### Legacy `server/models/User.js` re-hashed the password on every update

Its `beforeUpdate` hook ran `bcrypt.hash` unconditionally, so any user update — changing a
display name — re-hashed the already-hashed password and destroyed the credential.

If you migrate legacy data, some hashes may be double-hashed and unrecoverable. Affected users
need a password reset. Do not assume every legacy hash is valid.

*Added 2026-08-27 — found while reading the legacy app for `docs/domain/legacy-app.md`.*

---

### Spring `@Transactional` doesn't apply to self-invocation

Calling `this.someTransactionalMethod()` from inside the same class bypasses the proxy, so the
annotation silently does nothing. No error, no warning — just no transaction.

Split the method into a different bean, or inject self. Splitting is usually the better design.

*Added 2026-08-27.*

---

### `.claude/settings.json` hooks only load if the directory was watched at session start

If you add `.claude/settings.json` to a repo that didn't have one when the session started, the
hooks won't fire until the config is reloaded — open `/hooks` once, or restart the session.
The file can be perfectly valid and still appear to do nothing.

*Added 2026-08-27 — while building this harness.*

### Playwright's pinned version and a container's pre-baked Chromium drift apart

`@playwright/test` resolves a browser by *revision*, not "whatever Chromium is installed".
Pinning 1.56.1 made it look for `chromium-1234` while the container shipped `chromium-1194` —
so it reported a missing browser despite a perfectly good Chromium being present, and
`npx playwright install` in that environment is either blocked or downloads a second copy.

`tools/ui/helpers/browser.ts` resolves an existing browser under `PLAYWRIGHT_BROWSERS_PATH`
first and passes it as `executablePath`. A mismatched-but-close revision drives fine over CDP.
Override with `UI_CHROMIUM_PATH`.

*Added 2026-08-28 — while building the UI validation harness.*

---

### `@axe-core/playwright` can pull a second copy of `playwright-core`

Its peer range is `>= 1.0.0`, so npm happily hoisted `playwright-core@1.62.1` alongside
`@playwright/test`'s own `1.56.1`. Two copies means two incompatible `Page` types, and
`new AxeBuilder({ page })` fails to typecheck with a confusing "Type 'Page' is missing the
following properties from type 'Page'".

Fixed with an npm `overrides` entry pinning `playwright-core` to one version. Prefer that over
casting the argument — a cast hides the skew rather than resolving it, and the skew can be real
at runtime too.

*Added 2026-08-28.*

---
