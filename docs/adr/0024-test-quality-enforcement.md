# ADR-0024: Measure test quality, not just test coverage

- **Status:** Accepted
- **Date:** 2026-09-07
- **Deciders:** Repository owner

## Context

ADR-adjacent guidance in `../guides/testing-style.md` now gates coverage in CI, and coverage is a
useful floor: it catches "nobody wrote tests". The owner's position — correct, and the reason for
this ADR — is that the number must be met **and** must not become a substitute for tests that
logically catch edge cases.

The problem with stopping at coverage is that it answers the wrong question.

> Coverage answers **"was this line executed?"**
> It cannot answer **"would any test notice if this line were wrong?"**

A test that calls a method and asserts nothing produces identical coverage to one that asserts
everything. So a coverage gate can be satisfied — entirely honestly, by someone trying to do the
right thing under time pressure — by tests that would not fail if the code broke. In an
application about people's money, that is the failure mode worth spending effort on.

Meanwhile, several rules this project cares about most are enforced only by prose: "every
repository method for financial data takes a household id", "controllers never call repositories",
"no `double` for money". A reviewer catches those on a good day.

## Decision

Four layers, each answering a question the one below it cannot.

| Layer | Question it answers | Tool | Where it runs |
|---|---|---|---|
| **Coverage** | Was this code executed by any test? | JaCoCo · Vitest · `flutter test --coverage` | Every gate run |
| **Mutation testing** | Would a test *fail* if this code were wrong? | PIT (Java) · Stryker (TS) | Changed code on PRs; full run scheduled |
| **Architecture tests** | Can the dangerous shape even be written? | ArchUnit (Java) | Every gate run — fast |
| **Property-based tests** | Does this hold for inputs nobody thought to enumerate? | jqwik (Java) | Every gate run, for money and date logic |

### Mutation testing is the answer to the coverage critique

PIT changes the code — flips a conditional, replaces a return value, removes a call — and re-runs
the tests. If no test fails, the mutant "survived": that line is covered but unguarded. Mutation
score is therefore **not gameable by assertion-free tests**, which is precisely the property
coverage lacks.

It is slow, and pretending otherwise would guarantee it gets disabled. So:

- **On PRs: changed classes only**, with a mutation-score threshold. Fast enough to keep.
- **Full run on a schedule**, not blocking, reported as a trend.
- Thresholds start achievable and ratchet up. A threshold nobody can hit gets deleted, and then
  there is no signal at all.

### Architecture tests make the rules unwritable rather than reviewable

ArchUnit turns the prose in `../guides/` into failing builds. At minimum:

- No class in `web` may call a class in `persistence` — the layering rule in ADR-0002.
- Every repository method returning a financial entity **must** take a `householdId` parameter
  (ADR-0008/0017). This is the single highest-value structural rule in the codebase.
- No `double` or `float` in any type touching money (ADR-0006).
- Entities never appear in controller signatures — the mass-assignment rule.
- `@Transactional` appears in `service`, never in `web` or `persistence`.

These run in milliseconds and fail with a message naming the violating class.

### Property-based tests for the things examples miss

Money and dates are where example-based tests give false confidence, because the interesting
inputs are the ones nobody thought of. jqwik generates them:

- A three-way split of any amount reconciles **exactly** to the original.
- Converting and rounding never creates or destroys value.
- Mixed-currency arithmetic always throws, for any pair.
- A budget period's boundaries behave across month lengths, leap years and DST transitions.

### And the part no tool covers

The mandatory test list in `../guides/testing-style.md` — unauthenticated `401`, another
household's resource `404`, `VIEWER` gets `403`, both transports identical — is still a
checklist a human or an agent works through. Tooling narrows the gap; it does not close it. The
`test-author` and `security-auditor` agents exist for the part that requires judgement.

## Alternatives considered

| Option | Why not |
|---|---|
| Coverage alone | The status quo this ADR exists to fix. Satisfiable by tests that assert nothing |
| Raise the coverage threshold instead | Makes the wrong metric stricter. Pushes people toward tests written to move a number, which is worse than fewer honest tests |
| Mutation testing on everything, every PR | Correct in principle, unusably slow in practice, and a slow gate is a disabled gate |
| Review discipline only | Depends on the reviewer's attention on the day. This project has one maintainer and a lot of AI-generated code |
| Nothing beyond the mandatory checklist | The checklist is good and would still be entirely unenforced |

## Consequences

**Good:** test quality gets a number that cannot be faked by writing empty tests. The structural
rules that protect against cross-household leakage and float-money become build failures rather
than review comments. Money edge cases get explored rather than enumerated.

**Bad / costs:** four tools instead of one, each with configuration to maintain. Mutation testing
adds real CI time even when scoped to changed classes, and surviving mutants take genuine thought
to interpret — some are equivalent mutants that cannot be killed, which is a known and irritating
false-positive class. ArchUnit rules need updating when the architecture legitimately changes, and
a stale rule blocking correct code is how these get deleted wholesale. Property tests fail with
generated inputs that take effort to read.

The honest summary: this buys a real answer to "are these tests any good", at the cost of more
machinery than a project this size would normally carry. It is justified here because the
codebase is largely AI-generated and the subject is other people's money — the two conditions
that make assertion-free tests both likely and expensive.

**Follow-ups:** wire PIT, ArchUnit and jqwik with the backend skeleton (slice 1) so the rules
exist before there is code to violate them. Stryker with the frontend. Start thresholds low and
ratchet.
