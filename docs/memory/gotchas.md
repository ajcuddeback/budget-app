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

### CodeQL does not parse JS inside a template literal — so my first guess was unfalsifiable

Chasing three CodeQL alerts, I assumed the cause was an `innerHTML` assignment in a fixture's
inline `<script>`. It was not: that script lives inside a TypeScript **template literal**, so
CodeQL sees an opaque string, not analysable JavaScript. It was never flagged, which is why the
alert counts did not move after "fixing" it.

The real three were `js/file-system-race` (existsSync-then-readFileSync), `js/incomplete-
sanitization` (escaping `|` without first escaping `\`), and `actions/unpinned-tag`.

Two lessons. **Don't infer an alert's location from the code you find suspicious** — the bot
posts the file and line as a PR review comment, so read those (`pull_request_read` with
`get_review_comments`) instead of guessing. And **HTML-in-a-template-literal is a static-analysis
blind spot**: real application code in `.html`/`.ts` files gets analysed, fixture strings do not,
so a fixture cannot be relied on to surface a pattern that would be caught in production code.

*Added 2026-08-29 — PR #1.*

---

### Escaping one metacharacter without escaping the escape character is incomplete

`v.replace(/\|/g, '\\|')` looked fine for markdown table cells and is what CodeQL flags as
`js/incomplete-sanitization`. A value ending in a backslash escapes our own escape: `"C:\\"` +
`"|"` becomes `C:\\ \|`, which markdown renders as a literal backslash followed by a **live**
pipe — splitting the cell.

Always escape the escape character first: backslashes, then the metacharacter. Same rule for
newlines in a table row.

*Added 2026-08-29.*

### My own push-retry loop hid a failed push — pipes mask exit codes

The retry loop used throughout this project was `if git push 2>&1 | tail -2; then echo "PUSH OK"`.
A pipeline's exit status is the **last** command's, so it reported success from `tail`, not from
`git push`. A rejected push printed its rejection and was announced as OK in the same breath.

Capture the status explicitly:

```bash
out=$(git push 2>&1); rc=$?
printf '%s\n' "$out" | tail -2
[ "$rc" -eq 0 ] || { echo "failed (exit $rc)"; }
```

This is the same class as the `while read` bug already recorded here: a shell construct that
silently reports success. Both were found only because the *visible output* disagreed with the
*claimed result* — which is the argument for always printing the real output next to the verdict
rather than just the verdict.

Related: `--force-with-lease` fails with "stale info" when the remote branch no longer exists —
GitHub deletes the branch on merge, so a stale `origin/<branch>` tracking ref makes the lease
reference something gone. `git remote prune origin`, then a plain push.

*Added 2026-09-05.*

### `hashFiles()` at GitHub Actions *job* level always returns empty

Guarding a whole job with `if: hashFiles('backend/pom.xml') != ''` looks right and never runs.
Job-level `if` is evaluated **before** `actions/checkout`, when the workspace is empty, so
`hashFiles` finds nothing and the condition is always false — silently, with the job showing as
skipped rather than failed.

Put the guard on the **steps**, after checkout. (Step-level guards inside the `gate` job already
worked for this reason; the mutation job was written with a job-level guard first and would have
never executed.)

*Added 2026-09-07 — while wiring mutation testing.*

---

## Spring Boot 4 split autoconfiguration into per-technology modules

`flyway-core` on the classpath is no longer enough. Without
`org.springframework.boot:spring-boot-flyway`, the application **starts perfectly happily and
simply never migrates** — no warning, no error, just no tables.

This is the dangerous shape: a silent failure, not a loud one. It was caught only because the
slice-1 integration test asserts a row exists in `schema_metadata`, not merely that the context
loads. A context-loads test would have been green.

The same split applies elsewhere — `@AutoConfigureMockMvc` and `TestRestTemplate` are no longer on
`spring-boot-starter-test`'s classpath either. When something that "should just be there" is
missing under Boot 4, look for a `spring-boot-<technology>` module rather than assuming a version
problem.

**Lesson worth keeping:** assert the *effect*, not the *wiring*. "The context loaded" proves
almost nothing; "the migration ran" proves the thing you cared about.

## Spring Security answers an unauthenticated API request with 403, not 401

With `httpBasic` and `formLogin` both disabled there is no authentication entry point, so Spring
Security has nowhere to send the caller and falls back to `403`. Our own mandatory test list says
unauthenticated must be `401`.

Fix is one line — an explicit `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` — but nothing tells
you it is missing until a test asserts the status, and `403` looks close enough to correct to slip
through a review.

## ArchUnit fails a rule that matched nothing, and the obvious fix disables it forever

`failOnEmptyShould` is on by default, so a layering rule written before the packages exist fails
the build. `allowEmptyShould(true)` makes it pass — and keeps passing silently if a later feature
names its packages differently (`..controller..` instead of `..web..`), at which point every
layering rule is checking zero classes and protecting nothing.

`ArchitectureTest.every_feature_class_is_in_a_known_layer` is the guard: it fails the moment a
class appears outside the documented layer packages, so emptiness can only ever be genuine. Same
failure mode as the `while read` bug in `guard-bash.sh` — a control that quietly stops applying is
worse than no control, because the green tick is still there.

## Angular 22 needs Node >= 22.22.3

The dev container ships 22.22.2 and `ng new` refuses outright. CI pins Node 24. If the CLI
complains about the Node version, that is the reason — not a corrupt install.

## A renamed CI job silently stops being a required check

GitHub branch rulesets require status checks by literal job name. Rename a job in `ci.yml` and
the ruleset keeps requiring the old name — which never reports — so the pull request blocks
forever showing "Expected — waiting for status" rather than failing with a reason. The new job
runs, passes, and is required by nobody.

Rename in both places in the same change. The required set is listed in `docs/roadmap.md`.

Two related settings that make a ruleset decorative if you get them wrong: enforcement status
defaults to **Disabled**, and the repository owner is bypassable unless the bypass list is empty.
A rule that does not apply to the only person who pushes is not a rule.
