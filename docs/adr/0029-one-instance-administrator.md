# ADR-0029: Exactly one instance administrator, enforced in the schema

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** Repository owner

## Context

`V2__users.sql` carries a unique index on a constant expression, restricted to administrator rows:

```sql
CREATE UNIQUE INDEX uq_users_single_instance_admin
    ON users ((true))
    WHERE is_instance_admin;
```

It permits at most one `is_instance_admin` row for the life of the instance, and it has been there
since slice 2. It was written to close a race — two simultaneous callers of
`POST /api/setup/first-user` both becoming administrator — and the limit it imposes on the product
was a _consequence_ of that, never a decision anybody recorded.

That is the problem this ADR exists to fix. Three reviews in a row flagged "one admin per instance"
as an inference drawn from a line of SQL rather than a stated rule, which means nobody can tell
whether it is load-bearing or incidental, and a future change relaxes it without knowing what it
was holding up. It is also, now, expensive to revisit: dropping a unique index is a migration, and
migrations here are append-only (non-negotiable #4).

ADR-0026 settles the surrounding shape — one household per instance, the operator is its `OWNER`,
and the control against the operator is disclosure rather than encryption. What it does not say is
how many _administrators_ that instance has.

## Decision

**An instance has exactly one administrator, and the schema is where that is true.**

- The administrator is the person who completed first-run setup. They are the operator: it is their
  hardware, their database and their backups.
- The index stays, and stays as a unique index rather than an application check, because the race
  it closes is real and a service that reads-then-writes loses it.
- **Administrator is not a role in the household's permission model.** `OWNER`, `MEMBER` and
  `VIEWER` govern financial data (ADR-0017); `is_instance_admin` governs the instance. A household
  may have several `OWNER`s; the instance has one administrator.
- A second person who needs to operate the instance operates it the way they already can — at the
  machine, the Compose file and the database. ADR-0026 already establishes that the operator has
  full access by construction, so this adds no exposure that was not already stated.

## Alternatives considered

| Option                                              | Why not                                                                                                                                                                                                                                                                                                   |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Leave it unrecorded**                             | The status quo this ADR exists to end. Three reviews independently flagged it; each one re-derived the same conclusion from the same SQL, which is waste, and none of them could tell whether relaxing it was safe                                                                                        |
| **Several administrators, uniqueness dropped**      | Buys nothing a household `OWNER` does not already have. Instance administration means backups, updates and storage — things done on the box. Adding a second admin inside the app, for an instance whose operator already has the database, is complexity for a capability that already exists outside it |
| **Transfer the administrator to another user**      | Genuinely useful, and a real gap (see Consequences). Rejected _for now_ rather than forever, because it is a feature with its own threat model — a transfer is a privilege escalation path, and it needs the admin console (slice 9) to have somewhere to live                                            |
| **Enforce it in the service rather than the index** | Loses the race. Two concurrent setup calls both read "no admin yet" and both write. The index is what makes the second one fail                                                                                                                                                                           |

## Consequences

**Good:** the rule is now a decision with reasons attached rather than a line of SQL somebody has to
reverse-engineer. The race stays closed. The boundary between instance administration and household
roles is stated, which matters because `is_instance_admin` and `OWNER` are easy to conflate and
carry different powers.

**Bad / costs:** **there is no way to transfer the administrator**, and that is a real limitation
rather than a theoretical one. If the administrator's account is lost — the person leaves, forgets
their password with no reset path, or the row is damaged — the only recovery is the database. On a
self-hosted instance that is a `psql` session the operator already has, so the instance is not
bricked; but it is manual, undocumented, and exactly the kind of thing somebody discovers at the
worst moment. The index also cannot be relaxed without a migration.

**Follow-ups:** the admin console (slice 9) should carry administrator transfer, and the recovery
path — "how to make a different user the administrator with `psql`" — belongs in the deployment
documentation before the first release, not after somebody needs it.
