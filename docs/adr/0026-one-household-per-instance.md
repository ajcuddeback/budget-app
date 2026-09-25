# ADR-0026: One household per instance; the owner is the operator

- **Status:** Accepted
- **Date:** 2026-09-25
- **Deciders:** Repository owner
- **Amends:** ADR-0017 — the household stays the ownership root; there is now exactly one of them

## Context

ADR-0017 made `Household` the ownership root and said "a single-user instance is a household of
one — there is no special case". It left open how many households an instance could hold, and the
designs then added a nine-page admin console that can read logs, take backups, export everything
and hold instance API keys.

Together those produce an authorization hole with no principal to attach it to. The security model
has two axes — *which household*, then *what role in it* — and the console belongs to neither.
`security-model.md` refers three times to an "instance admin" that the domain model never defines.
If that resolved to a household `OWNER` on a multi-household instance, any household's owner could
back up and read every other household's finances through a legitimate feature.

The alternative — genuinely isolating households from the person who administers the server —
means the operator must not be able to read data they physically host. That requires per-household
encryption with keys the server cannot recover, which brings key management, key loss, and a
recovery story for a product whose support desk does not exist.

The owner's position: the person installing this on their own hardware **is** the owner. If they
create a household with someone else and go digging in that person's finances, that is a human
problem, not a cryptographic one. Someone accepting an invitation to a server their brother-in-law
runs is trusting their brother-in-law, and pretending otherwise with cryptography that cannot
actually deliver the promise is worse than saying so plainly.

## Decision

**An instance holds exactly one household.** There is no UI, API or migration path for a second
one, and multi-tenancy is not a product feature.

- The household's **`OWNER` is the instance operator.** The admin console is `OWNER`-only. That is
  the principal `security-model.md` was missing, and it needs no new role.
- **Multiple users remain**, which was always the point: an owner shares the instance with a
  partner who records and views finances according to their role. `OWNER` / `MEMBER` / `VIEWER`
  are unchanged.
- **Members see the household's finances.** There are no per-member private accounts and no
  data the owner cannot reach. Anyone accepting an invitation is sharing their finances with
  whoever runs the server, and the invitation flow must **say so in those words** before the
  account is created. That disclosure is the control here — the honest one — and it is a
  requirement of the auth feature, not a nicety.
- **No application-level encryption against the operator.** The operator has the database, the
  volume and the backups. Encrypting data against them would be theatre unless the keys were
  outside their reach, which for a self-hosted box they are not.
- **`household_id` stays on every financial table**, and every query keeps resolving it from
  verified membership rather than from the request. See the consequences — this is deliberate
  even though the column now holds one value.

## Alternatives considered

| Option | Why not |
|---|---|
| Multiple households, operator cannot read them | Requires per-household keys the server cannot recover. Key loss means unrecoverable data on a product with no support desk, and an operator with root can capture keys at use anyway. The guarantee cannot actually be delivered, so making it would be a lie. |
| Multiple households, operator *can* read them, disclosed | Exactly the hole this ADR closes. A household owner is not the server operator, so "the operator can read everything" silently means "some other user can read everything". |
| A separate instance-admin role above households | Adds a third authorization axis and a super-user to a financial app, for a deployment shape that no longer exists. With one household the owner already is that person. |
| Drop households entirely; scope to users | Loses the shared-finances model, which is the product. Two people budgeting together would have to share a login — the incumbent workaround ADR-0017 exists to avoid. |
| Drop the `household_id` column since it is now constant | Cheap to keep, expensive to reinstate: the roadmap already notes that retrofitting it would touch every financial table and every query. It also preserves the query *shape* that makes the dangerous call unwritable, and keeps a managed-hosting option open. |

## Consequences

**Good:** the admin console gets a principal without inventing a role, and the console can ship.
The threat model gets smaller and more honest — no cryptographic promise the architecture cannot
keep. Deployment is simpler: one instance, one family, one backup. And the privacy story stays
true in the form that matters, which is that **nobody outside the box** sees the data (ADR-0016).

**Bad / costs:** this removes the app's strongest security control. Cross-household isolation was
the highest-value test in the codebase — "user B gets 404 on user A's resource" — and with one
household there is no B to be. What remains is authentication, role enforcement and input
validation, so those now carry weight they did not have to carry alone before. Concretely:

- **Authentication hardening matters more, not less.** The instance may be internet-facing and
  there is no second boundary behind it. Enumeration resistance, rate limiting, session rotation
  and the closed-registration rule are now the perimeter.
- **Role tests replace household tests** as the highest-value authorization test. Every write
  endpoint needs a `VIEWER`-gets-`403` test, and every admin endpoint a `MEMBER`-gets-`403` test.
  `testing-style.md`'s mandatory list must be updated to say this, or the removed test will simply
  be missing rather than replaced.
- **The trust disclosure becomes a security requirement.** If a person joining a household is not
  clearly told that the host can see their finances, this decision is no longer honest — it is
  just a weaker model with better documentation.

**Follow-ups:** amend `security-model.md` (the missing third axis becomes "the admin console is
`OWNER`-only"), update the mandatory-test list in `testing-style.md`, and add the join-time
disclosure to `features/authentication-and-households.md`. The admin console feature doc (slice 9)
is unblocked by this.
