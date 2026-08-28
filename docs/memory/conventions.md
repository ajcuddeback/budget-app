# Conventions

Small agreed choices. Add with `/remember`. Newest at the bottom of each section.

## Naming

- Services are `<Noun>Service`. Never `Manager`, `Helper`, `Util`, `Handler`, `Processor`.
- Request DTOs are `<Verb><Noun>Request`; responses are `<Noun>Response`. Never `<Noun>Dto`.
- Integration tests end in `IT`; unit and slice tests end in `Test`. The Maven config keys off
  this, so the suffix is load-bearing, not cosmetic.
- Boolean accessors are positive assertions: `isArchived()`, not `isNotArchived()`.
- Angular feature folders are singular: `features/transaction/`, not `transactions/`.
  The API resource is plural (`/api/transactions`); the folder is not. Yes, they differ.

## Code organization

- Backend packages are organized **by feature first, layer second**:
  `com.budgetapp.transaction.web`, not `com.budgetapp.web.transaction`.
- Anything shared by three or more features moves to `common/`. Two is a coincidence.
- One public type per file. Nested records used only by their enclosing type may live inline.

## Structure

- `@Transactional` lives on service methods only.
- Controllers are thin: validate, delegate, map. No branching on business rules.
- Angular: routed page components are smart, everything under `components/` is presentational.

## Angular

- The official `angular-developer` skill is the source of truth for **framework** questions.
  `docs/guides/angular-style.md` is the source of truth for **project** decisions, and its
  override table names every point where we deliberately differ.
- Never pass `--ai-config` to `ng new`. It writes a competing agent config that fights the
  harness's routing; ours is `CLAUDE.md` at the repo root.

## Documentation

- Feature docs are `kebab-case.md`, named as a user would name the feature.
- ADR filenames are `NNNN-imperative-title.md`, zero-padded to four digits.
- Every doc that states a rule links to the ADR or guide that established it, so the reader can
  find the reasoning rather than re-deriving it.
