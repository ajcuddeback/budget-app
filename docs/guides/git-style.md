# Git and Review Style

## Branches

`claude/<topic>` for agent work, `feat/<topic>` / `fix/<topic>` otherwise. Never commit to `main`.

## Commits

Conventional Commits:

```
feat(transactions): add transfer between accounts

Transfers are a linked pair of transactions sharing a transferGroupId,
so reporting can exclude them from income and expense totals.

Refs: docs/features/transfers.md
```

Types: `feat` `fix` `refactor` `test` `docs` `chore` `perf` `build` `ci`.

- Subject in the imperative, under 72 characters, no trailing period.
- The body explains **why**, not what — the diff already shows what.
- One logical change per commit. Formatting-only changes go in their own commit so review diffs
  stay readable.
- Reference the feature doc or ADR when there is one.

## What never gets committed

- Secrets, keys, tokens, `.env` files, real connection strings.
- `node_modules/`, `target/`, build output, IDE files.
- Commented-out code. Git remembers; the file shouldn't.
- A migration that edits a previously merged migration.

## Pull requests

A PR includes:

1. What changed and why, in a sentence or two.
2. A link to the feature doc or ADR.
3. **Security notes** — what this touches in the security model, and what you verified.
   "None" is a valid answer when it's genuinely none.
4. Evidence: `tools/verify.sh` output, or the specific tests that prove the behavior.

## Definition of done

- [ ] `tools/verify.sh` passes — actually run, not assumed
- [ ] Tests cover the new behavior, including the mandatory auth tests in `testing-style.md`
- [ ] Feature doc created or updated **in this change**
- [ ] UI change? `tools/ui-check.sh` run and its screenshots read
- [ ] User-visible? Guide written or updated in `userguide/`, from the running app
- [ ] Decisions worth keeping are in an ADR or `docs/memory/`
- [ ] No secrets, no new `any`, no disabled security controls
- [ ] Migration added if the schema changed, and it is append-only
