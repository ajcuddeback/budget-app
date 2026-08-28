# tools/

| Script | Purpose |
|---|---|
| `verify.sh` | **The gate.** Build, lint, test, scan, doc checks, both stacks. Run before claiming anything is done. |
| `dev-up.sh` | Start local PostgreSQL in Docker for development. |
| `ui-check.sh` | Drive the running UI in a real browser, screenshot it for agent review, and check accessibility, console errors, and layout. See [`ui/README.md`](ui/README.md). |
| `userguide-capture.sh` | Capture annotated screenshots for the customer-facing guide in `userguide/`. |
| `userguide-check.sh` | Find user-guide screenshots that are missing, orphaned, or older than the UI they show. |

## verify.sh

```bash
tools/verify.sh            # everything
tools/verify.sh backend    # backend only
tools/verify.sh frontend   # frontend only
```

It degrades gracefully: stacks that don't exist yet are reported as **skipped**, not passed,
and integration tests skip with a warning when Docker isn't available (ADR-0009).

**A skip is not a pass.** The summary says so, and so should you when reporting results.

Exit code is `0` only if nothing failed.

## Local database credentials

`dev-up.sh` uses fixed, non-secret local credentials (`budget`/`localdev`). They exist only on a
developer machine. Real environments read credentials from the environment with no fallback
default — see `docs/architecture/security-model.md`.
