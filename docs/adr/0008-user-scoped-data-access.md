# ADR-0008: Every query is scoped to the authenticated user

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner
- **Amended by:** [ADR-0017](0017-households-own-financial-data.md) — the principle below is
  unchanged; the scope is now the household rather than the individual user.

## Context

Broken object-level authorization is the most common and most damaging web vulnerability, and
the legacy app had it in its most literal form: `GET /api/user` returned every user in the
database along with their bills and income, to any caller, with no authentication.

The usual failure mode is subtler — `findById(id)` where `id` came from the URL, with ownership
checked somewhere else, or not at all, or only on the happy path.

## Decision

Every repository method that touches user-owned data takes the owner's id as a parameter and
filters on it. There are no user-scoped finders without an owner parameter.

```java
// Banned for user-owned entities
Optional<Account> findById(UUID id);

// Required
Optional<Account> findByIdAndUserId(UUID id, UUID userId);
```

Supporting rules:

- The acting user comes from the `SecurityContext`. Never from a body, query parameter, or path.
- "Not found" and "not yours" both return **404**. A `403` would confirm the row exists.
- Authorization decisions live in the service layer.
- Every endpoint gets a test proving user B receives `404` for user A's resource. No exceptions —
  this is the test that catches the bug class this ADR exists to prevent.

## Alternatives considered

| Option | Why not |
|---|---|
| Check ownership in the controller | Easy to forget on a new endpoint; puts a security decision in the layer least likely to be tested |
| A Hibernate filter or `@Where` applied globally | Invisible, easy to bypass with a native query, and hard to reason about. Good as a *second* layer, not the only one |
| Postgres row-level security | Strong defense in depth and worth adding later; requires per-request session variables and careful connection-pool handling. Not the primary mechanism |

## Consequences

**Good:** the dangerous pattern becomes unwritable — the repository has no method that lets you
forget the check. Reviewers can spot violations by shape alone.

**Bad / costs:** slightly more verbose repository interfaces, and admin or reporting queries
need an explicit, separately reviewed escape hatch.

**Follow-ups:** the `security-auditor` agent checks this on every change touching data access.
Consider Postgres RLS as a second layer once the schema stabilizes.
