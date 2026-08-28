# ADR-0013: UI and doc captures render against fixtures, never a live app

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Repository owner
- **Amends:** the capture-data guidance in ADR-0012

## Context

ADR-0012 established a customer-facing user guide built from screenshots of the running app,
committed to the repository. It addressed the risk of a screenshot capturing real financial
records by **writing a warning** — "demo data only" — repeated in the style guide, the agent
prompt, the skill, and the workflow guide.

That was the wrong instrument, for two reasons.

**It was a request, not a constraint.** Every one of those four statements depended on a human
or an agent choosing to comply. The same session that wrote them had already argued that soft
controls are inadequate, in the context of screenshot review. Applying a weaker standard here
was an inconsistency, not a considered trade-off.

**It left the actual hole open.** The capture script accepted `--url <anything>`. The pipeline
as built could be pointed at a staging or production instance; the warning simply asked that it
not be. A control that can be defeated by ignoring a comment is not a control.

Underneath both was a missing piece of engineering: there was no fixture dataset. "Use demo
data" was an instruction with nothing behind it, so the only way to get a screenshot of a
populated screen was to point at a real instance with real records in it. The warning existed to
compensate for an absent capability.

The correct framing: development environments should never hold production data, agents should
never hold credentials to a live system, and end-to-end runs should use deterministic fixtures
regardless. Once those hold, the privacy concern largely dissolves — and what remains is handled
by a mechanism rather than a paragraph.

## Decision

Captures render the real UI against fixture data, in process, with no server behind them.

1. **A single canonical dataset**, `tools/ui/fixtures/demo-data.ts`: a fictional user, three
   accounts, a month of transactions including a transfer, and a budget with lines both under
   and over. Deterministic, obviously fictional (`example.test` addresses, invented names),
   and realistic in shape.

2. **The API is mocked in-process.** `helpers/mock-api.ts` intercepts every `/api/**` request
   and answers it from the fixtures. The `doc` fixture installs it automatically before the
   first navigation, so a spec author cannot forget. There is no database, no server, and no
   credential involved in a capture run. An unmocked endpoint returns `501` with a message
   naming the file to extend — loud, because a screenshot of an unexpectedly empty screen is
   worse than a failed run.

3. **Documentation captures refuse a non-local target.** Enforced in
   `tools/userguide-capture.sh`, matching on hostname so `localhost.example.com` is rejected
   too. **There is deliberately no override flag.** A capture that commits images to the
   repository has no legitimate reason to reach a remote host.

4. **Validation runs keep their freedom.** `tools/ui-check.sh` may target any URL: its output
   is gitignored, and checking a deployed staging environment is a reasonable thing to do. The
   restriction applies to the pipeline that commits images, not to all browser automation.

5. **Extending the guide means extending the fixtures.** An empty state, an overspent budget,
   a very long account name — add it to `demo-data.ts`. That is the only source of guide data.

The warnings added by ADR-0012 are replaced by this mechanism, not supplemented by it.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep the warnings, add no mechanism | The status quo being corrected. Depends on everyone remembering, and left the `--url` hole open |
| Seed a real local database with demo data | Works, but needs a running backend, a database, and a seeding step before any capture — slow, and it reintroduces "which data is in there?" as a live question. Interception has none of that |
| Allow a remote target behind an override flag | The flag becomes the documented way to do the thing the rule exists to prevent. A control with an override is guidance wearing a costume |
| Commit no screenshots at all | Removes the concern by removing the user guide's main value |
| Record HTTP fixtures from a real instance (VCR-style) | Recording is exactly the step that would touch real data. Hand-authored fixtures never do |

## Consequences

**Good:** the concern is structurally absent rather than mitigated — a capture run cannot read
real records because it has nothing to read from. Screenshots become byte-for-byte reproducible,
which is what makes ADR-0012's staleness check meaningful: a diff now means the UI changed.
Captures need no backend, no database and no seed step, so they run in CI and on a laptop
identically. Documentation now has one obvious place to add a scenario.

**Bad / costs:** the fixtures are a second definition of the API's shape, and they will drift
from the real contract. The `501`-on-unmocked-endpoint behavior surfaces that drift at capture
time rather than hiding it, but drift is real maintenance. A guide screenshot proves the UI
renders a known payload — it does **not** prove the backend produces that payload; that is what
the integration tests in ADR-0009 are for, and neither substitutes for the other. Fixtures must
also be kept realistic: a dataset with one account per type produces a guide that never shows
the awkward cases.

**Follow-ups:** when the API stabilizes, consider generating fixture types from the
springdoc-openapi schema so drift becomes a compile error rather than a runtime `501`.
