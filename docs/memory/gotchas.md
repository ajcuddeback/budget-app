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

### `new Function()` inside `page.evaluate` breaks under our own CSP

The doc-capture overlay first built its highlight ring via `new Function(...)` inside the page.
Playwright's `page.evaluate` itself is fine under a strict CSP — it goes through the debugger
protocol, not `eval` — but `new Function()` *inside* the page is ordinary dynamic evaluation and
`script-src` without `unsafe-eval` blocks it. Our security model mandates exactly that CSP, so
the annotation would have silently failed on our own app while working on a data: URL fixture.

Pass a plain inline callback to `page.evaluate` instead.

*Added 2026-08-28 — caught while building the user-guide capture pipeline.*

### Playwright route precedence: the LAST registered handler wins

A spec registered `page.route('**/accounts', ...)` to serve an app shell, after the doc fixture
had already registered `page.route('**/api/**', ...)`. The broad pattern matched
`/api/accounts` too, and because it was registered later it took precedence — so the page's own
`fetch('/api/accounts')` was answered with HTML. The symptom was a bare timeout waiting for
content, with nothing in the console pointing at routing.

Register the narrowest pattern you can, and prefer an exact URL for a page shell. When a mock
seems not to be applied, check whether a later route is shadowing it.

*Added 2026-08-28 — while building the fixture-backed capture pipeline.*

### Vendored skills are symlinks, and Windows checkouts can break them

`npx skills add` puts real files in `.agents/skills/<name>/` and symlinks
`.claude/skills/<name>` to them. Git stores the symlink correctly (mode 120000) and it resolves
on macOS and Linux.

On Windows without symlink support enabled (`core.symlinks`, which needs Developer Mode or an
elevated clone), git checks the symlink out as a **plain text file containing the target path**.
The skill then silently does not load — no error, it just is not there.

If Angular guidance seems missing on Windows, check whether `.claude/skills/angular-developer`
is a directory or a one-line text file.

*Added 2026-08-28 — ADR-0014.*

### A substring-matching Bash guard will block prose about itself — and lock you out of fixing it

A `PreToolUse` Bash hook enforcing "scaffold only into frontend/" matched any command
*containing* `ng new`. It then blocked the heredoc writing the documentation for that very rule,
and later a test script whose payload contained a chained command. Worse, once installed, the
buggy hook blocked every `cat > .claude/hooks/guard-bash.sh <<EOF` attempt to fix it — the guard
prevented its own repair. The way out was the Write tool, which the Bash matcher does not gate.

Two rules for command guards:

1. **Match invocations, not mentions.** Only a line that *begins* with the command counts.
   Splitting on `;`, `&&` and `|` sounds more thorough but re-introduces the bug, because prose
   and JSON payloads contain those characters too.
2. **Keep an edit path that does not go through the guard**, and remember it exists. A guard on
   `Bash` that you can only fix with `Bash` is a trap you set for yourself.

Also: `while IFS= read -r line; do ... done < <(printf '%s' "$x")` never executes the body for a
single-line input, because `read` returns non-zero at EOF without a trailing newline. Use
`printf '%s\n'`. This silently disables a guard rather than erroring — the tests looked like
they passed because nothing was output at all.

*Added 2026-08-28 — ADR-0014.*

### `codeql-action/init` silently ignores an unknown input — `language` vs `languages`

Our CodeQL step passed `language: ${{ matrix.language }}`. The correct input is **`languages`**
(plural). An unknown input is only a warning, buried at the end of the job log:

    ##[warning]Unexpected input(s) 'language', valid inputs are ['tools', 'languages', ...]

So the matrix was silently ignored and CodeQL auto-detected everything it could find — which is
why `actions.sarif` (workflow analysis) appeared alongside `javascript.sarif` even though the
matrix named only `javascript-typescript`. The job still reported success.

Two lessons beyond the typo. **CodeQL analyses your workflow files**, not just application code —
`.github/workflows/*.yml` is scanned by the `actions` query pack for unpinned third-party
actions, excessive secret exposure, and missing permissions. And **a job-level `permissions:`
block replaces the workflow-level one rather than merging**, so a job declaring only
`security-events: write` silently loses `contents: read`.

*Added 2026-08-29 — while chasing CodeQL alerts on PR #1.*

---

### A green workflow run and a green PR check are different things

The CI workflow run for PR #1 reported `conclusion: success` with all four jobs green, while the
**CodeQL check on the PR failed**. They are separate objects: the workflow job runs the analysis
(and succeeded at doing so), while the code-scanning check reports whether any *alerts* are open.

When a check fails but every job succeeded, stop reading job logs for an error — there isn't
one. Read the alerts instead.

*Added 2026-08-29.*

---
