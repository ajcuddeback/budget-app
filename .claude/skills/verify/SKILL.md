---
name: verify
description: Run the full local gate — build, lint, tests, migrations, security checks across both stacks — and report honestly. Use before claiming any work is done. Invoked as /verify.
---

# Verify

`tools/verify.sh` is the definition of done. Not "should pass" — run it.

## Run it

```bash
tools/verify.sh              # everything
tools/verify.sh backend      # backend only
tools/verify.sh frontend     # frontend only
```

Backend integration tests need Docker (Testcontainers, ADR-0009). If Docker isn't available the
script says so and skips them — a skip is **not** a pass, and reporting it as one is the exact
failure this skill exists to prevent.

## Then check what the script can't

- **Feature doc updated** in this same change, if behavior changed.
- **Mandatory tests present** for any new endpoint — `401` unauthenticated, `404` for another
  user's resource, `400` on invalid input, `403` on missing CSRF for state-changing routes.
  See `docs/guides/testing-style.md`.
- **Nothing disabled to get green.** A passing suite with a skipped test, a widened query, or a
  disabled security control is a worse outcome than a red one.
- **Decisions recorded** — `/adr` or `/remember`.

For anything touching auth, money movement, or data boundaries, run the `security-auditor`
agent as well. The script checks that code compiles and tests pass; it does not check that the
authorization logic is correct.

## Reporting

State what actually happened.

- Passed → say so plainly, and name what ran.
- Failed → show the real output and either fix it or say precisely what's broken and why.
- Skipped → say it was skipped and why. Never fold a skip into "passed".

Never report a suite as green without having run it in this session.
