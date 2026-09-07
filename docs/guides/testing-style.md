# Testing Style Guide

A feature without tests is not done. This guide says what "enough" means so nobody has to guess.

## The pyramid

| Level | What | Speed | Where |
|---|---|---|---|
| Unit | Domain logic, `Money`, validators, mappers. No Spring context | ms | `*Test.java` |
| Slice | `@WebMvcTest` (controller + security), `@DataJpaTest` (repository + real Postgres) | fast | `*Test.java` |
| Integration | `@SpringBootTest` end to end against Testcontainers Postgres | seconds | `*IT.java` |
| E2E | Playwright, the critical user journeys only | slow | `frontend/e2e` |

Most tests should be unit tests. Integration tests cover wiring, migrations, and security —
not every branch.

## The tests that are mandatory

For **every endpoint**, without exception:

1. **Unauthenticated request → `401`.**
2. **Another household's resource → `404`.** Not `403`. This is the ADR-0008 test (as amended by
   ADR-0017) and it is the single highest-value test in this codebase — it catches the bug class
   that leaked every user's finances in the legacy app. A cross-household leak is the worst bug
   this app can have.
3. **Invalid input → `400`** with a problem response naming the field.
4. The happy path.

For **every endpoint**, under **both credential transports** (ADR-0018):

5. **Session cookie and bearer token behave identically.** An endpoint that authorizes correctly
   for the web and not for mobile is a real and easy bug. Neither path may skip a check the
   other applies.

For **every state-changing endpoint**, additionally:

6. **Missing or invalid CSRF token → `403`** (session transport; CSRF does not apply to bearer).
7. **A `VIEWER` gets `403`.** Roles are an authorization axis, not a UI hint (ADR-0017), and only
   an `OWNER` may invite, remove members, or delete a household.

For **anything handling money**:

8. Scale and rounding: does a three-way split of `10.00` reconcile to exactly `10.00`?
9. Currency mismatch throws.
10. Negative, zero, and absurdly large amounts behave as specified.

## Naming

Method names are sentences describing behavior:

```java
@Test
void returns404WhenAccountBelongsToAnotherUser() { }

@Test
void rejectsTransferWhenCurrenciesDiffer() { }
```

Not `testGetAccount1`. The name is the failure message a future reader sees at 2 a.m.

## Structure

Given / When / Then, with blank lines separating the three. Comment the sections only when the
test is long enough that it helps.

```java
// given
var account = accounts.save(anAccount().ownedBy(alice).build());

// when
var response = mvc.perform(get("/api/accounts/{id}", account.id()).with(user(bob)));

// then
response.andExpect(status().isNotFound());
```

## Rules

- **One behavior per test.** A test with three unrelated assertions fails ambiguously.
- **Assert on behavior, not implementation.** `verify(repo).save(any())` tests that you wrote
  the code you wrote. Assert on the resulting state or response instead.
- **No shared mutable state between tests.** Each test builds what it needs. Order-dependent
  suites are worse than no suite.
- **Never mock what you own and can use for real.** Mock the clock, external HTTP, and email.
  Don't mock your own repositories in an integration test.
- **Test data builders**, not sprawling `@BeforeEach` setup. `anAccount().withBalance("10.00").build()`
  makes the relevant detail visible and the irrelevant ones invisible.
- **Fixed clock.** Inject `Clock`; never call `LocalDate.now()` in production code. Time-dependent
  tests that pass in August and fail in January are a real and stupid failure mode.
- **A flaky test is a broken test.** Fix it or delete it. Never retry it into green, never
  `@Disabled` it and move on — a disabled test is a lie that CI is green.

## Frontend

- Vitest + Angular Testing Library. Query by role, label, and text — what a user perceives —
  never by CSS class or test-id-as-a-crutch. Vitest is Angular's own default runner; the
  `angular-developer` skill's testing guidance assumes it (ADR-0014).
- Test components through their public surface: inputs in, rendered output and outputs out.
- Mock HTTP at `HttpTestingController`, not by stubbing the service under test.
- Playwright E2E covers: sign up, log in, add a transaction, see the balance update, log out.
  Keep it to journeys — E2E is where suites go to become flaky.

## Fixtures are read as examples

Test fixtures and harness specs get copied. An agent writing a new spec starts from an existing
one, so a fixture that models a bad pattern propagates it into real code.

Concretely: a harness fixture once built a table with `innerHTML` and string concatenation from
a fetched response. Harmless in itself — the data was our own fixture — but CodeQL flagged it as
high-severity XSS, and `docs/guides/angular-style.md` bans exactly that pattern in application
code. A fixture in this repo should not demonstrate what the style guide forbids.

Write fixtures to the same standard as production code, even when the fixture cannot be
attacked. The cost is a few extra lines; the alternative is teaching the pattern.

## Coverage — a floor, not a target

Coverage is gated in CI and a drop **fails the PR**. That is not a contradiction of "we don't
chase a number", but it does need stating precisely, because the two ideas get confused.

**What the gate is for:** catching the case where tests were not written at all. It is a smoke
alarm, not a quality measure. Code can be 100% covered by tests that assert nothing.

**Where it applies:**

| Scope | Line | Branch |
|---|---|---|
| `service/`, `domain/` — rules, money, authorization | 85% | 75% |
| Everything else (backend, aggregate) | 70% | 60% |
| Frontend `features/`, `core/` | 80% | 70% |

**Excluded**, because covering them measures nothing: DTOs and records with no behaviour,
generated code (MapStruct, `freezed`, `json_serializable`), configuration classes, and framework
entry points.

**The threshold is met, not chased.** Those two are different and both matter: the number is a
hard gate that fails the PR, *and* meeting it proves almost nothing on its own. Do not treat
hitting it as evidence the feature is tested.

**What the gate never substitutes for:** the mandatory tests above. An endpoint with 95% coverage
and no cross-household test is a data leak with good statistics. If you are ever choosing between
raising coverage and writing one of the mandatory tests, write the mandatory test.

**Never** add a test purely to move the number. A test that exists to satisfy a threshold is
noise that will be maintained forever, and it makes the metric lie about the thing it exists to
detect.

## What actually measures whether the tests are any good

Coverage answers *"was this line executed?"*. It cannot answer *"would a test fail if this line
were wrong?"* — and an assertion-free test scores identically to a thorough one. Three further
layers close that gap (ADR-0024):

**Mutation testing — the real answer.** PIT (backend) and Stryker (frontend) change the code
deliberately — flip a conditional, drop a call, alter a return — and re-run the tests. A mutant
that *survives* means that line is covered but unguarded. Mutation score cannot be faked by
writing tests that assert nothing, which is exactly what coverage cannot detect.

It is slow, so it runs on **changed classes on PRs** with a threshold, and fully on a schedule.
Thresholds start achievable and ratchet up — a threshold nobody can hit gets deleted, and then
there is no signal at all.

**Architecture tests — making the dangerous shape unwritable.** ArchUnit turns rules in this
guide into failing builds rather than review comments:

- a repository method returning a financial entity **must** take a `householdId`
- `web` never calls `persistence`
- no `double`/`float` in any type touching money
- entities never appear in controller signatures
- `@Transactional` only in `service`

These run in milliseconds. When one blocks legitimate code, fix the rule deliberately — do not
delete it, because that is how the whole layer quietly goes away.

**Property-based tests — for edge cases nobody enumerated.** jqwik generates the inputs you did
not think of. Use it wherever the interesting cases are unbounded:

- a split of *any* amount reconciles exactly to the original
- rounding never creates or destroys value
- mixed-currency arithmetic throws for *any* pair
- period boundaries hold across month lengths, leap years and DST

**And the part no tool covers.** The mandatory list above is still a checklist worked through by
a person or an agent. Tooling narrows the gap; judgement closes it. That is what `test-author`
and `security-auditor` are for — use them.

Enforced by `jacoco:check` (backend), Vitest coverage thresholds (frontend), and
`flutter test --coverage` (mobile), all run inside `tools/verify.sh`.

## What runs in CI, and what cannot be skipped

`tools/verify.sh` is the gate locally *and* in CI, but it behaves differently in the two places,
deliberately:

- **Locally**, tooling that is unavailable (no Docker, no Flutter) prints a warning marked `!`
  and the run still passes, so you can get partial signal while working.
- **In CI** (`CI=true`), the same condition is a **failure**. Integration tests and coverage
  cannot silently not-run.

The distinction that matters: a stack that *does not exist yet* is a benign skip in both places;
tooling that *should have been there* is a failure in CI. "A skip is not a pass" was printed
advice for weeks before it was enforced — the enforcement is the point.

CI additionally asserts Docker is present before running the gate, so a runner without it fails
with a message naming the reason rather than quietly skipping the tests that matter most.

## Running

```bash
tools/verify.sh              # everything — this is the gate
mvn -f backend test          # backend unit + slice
mvn -f backend verify        # + integration (needs Docker)
npm --prefix frontend test          # vitest
```
