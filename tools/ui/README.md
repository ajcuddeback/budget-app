# UI validation harness

Drives the running app in a real browser, captures screenshots an agent can read back, and runs
the checks that eyes miss. Full workflow: [`docs/guides/ui-validation.md`](../../docs/guides/ui-validation.md).
Rationale: [ADR-0011](../../docs/adr/0011-playwright-ui-validation-harness.md).

## Run

```bash
tools/ui-check.sh --selfcheck        # prove the harness works — no app needed
tools/ui-check.sh                    # against http://localhost:4200
tools/ui-check.sh --serve            # start the dev server, check, stop it
tools/ui-check.sh --url http://...   # any running instance
tools/ui-check.sh --grep login       # one feature
tools/ui-check.sh --headed           # watch it happen
```

## Output

Everything lands in `artifacts/` (gitignored):

| File | What |
|---|---|
| `REVIEW.md` | **Start here.** Findings table + the list of screenshots to read |
| `*.png` | Full-page screenshots, one per label per viewport |
| `results.json` | Machine-readable Playwright results |
| `_html-report/` | Full Playwright HTML report, for a failing run |

The run prints every screenshot's absolute path so an agent can open it with the Read tool.
**That step is the point of the harness** — automated checks confirm nothing detectable is wrong;
only looking tells you whether the UI is any good.

## Layout

```
tools/ui/
├── playwright.config.ts   desktop 1440x900 + mobile Pixel 7
├── helpers/
│   ├── browser.ts         resolves Chromium (handles pre-baked container browsers)
│   ├── artifacts.ts       manifest read/write
│   ├── ui-test.ts         the `ui` fixture: shot / a11y / noErrors / noOverflow
│   └── summarize.ts       builds REVIEW.md
└── specs/
    ├── harness-selfcheck.spec.ts         positive control
    ├── harness-detects-problems.spec.ts  negative control — proves detectors still fire
    ├── _TEMPLATE.spec.ts.example         copy this for a new feature
    └── app/                              real app specs live here
```

## The two control specs

`harness-selfcheck` proves the browser launches, screenshots land, and the checks run clean on a
good page. `harness-detects-problems` loads a deliberately broken page and asserts the problems
are **found**.

The second matters more. A checker that never fails is worthless, and detectors break silently.
If that spec goes red, the harness has gone blind — fix it before trusting any green run.

## Browser resolution

`@playwright/test` is pinned, but pre-baked containers ship whatever Chromium revision they were
built with, and the two drift. `helpers/browser.ts` prefers an existing browser under
`PLAYWRIGHT_BROWSERS_PATH` over Playwright's expected path, so no second copy is downloaded.

On a normal dev machine, `npx playwright install chromium` once and it uses Playwright's own.
Override either with `UI_CHROMIUM_PATH`.

## Data

Validation runs drive whatever you point them at, and their output is gitignored — pointing
`ui-check.sh` at a deployed staging environment is a legitimate thing to do.

**Documentation captures are different**, because their images are committed. They render
against the fixtures in `fixtures/demo-data.ts` via request interception, and
`tools/userguide-capture.sh` refuses any non-local target outright. See ADR-0013.
