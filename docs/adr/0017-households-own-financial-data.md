# ADR-0017: Households, not users, own financial data

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** Repository owner
- **Amends:** ADR-0008 — the scoping principle stands; the scope changes

## Context

`docs/domain/model.md` makes `User` the sole ownership root, and ADR-0008 requires every query to
filter by the authenticated user's id. That was right for a single-person app.

Budget Owl is for households (ADR-0016). Budgets are rarely a solo activity, and every tool that
models them as one forces couples to share a login — which destroys the audit trail, makes
per-person permissions impossible, and is exactly the workaround people complain about in the
incumbents.

## Decision

A **`Household`** is the ownership root for all financial data: accounts, transactions,
categories, budgets, payees, goals.

- A `User` is a person with credentials. A **`HouseholdMember`** joins a user to a household with
  a **role**: `OWNER`, `MEMBER`, or `VIEWER`.
- Every financial table carries a direct `household_id`, indexed leading composite keys the same
  way `user_id` did.
- **A single-user instance is a household of one.** There is no special case, no toggle, and no
  second code path — the most common deployment is the general model with one member.

**ADR-0008 is amended, not superseded.** Its principle — never infer ownership from an id in the
request, make the dangerous query unwritable — holds exactly. What changes is the predicate:

```java
// Was
findByIdAndUserId(UUID id, UUID userId)
// Now
findByIdAndHouseholdId(UUID id, UUID householdId)
```

with `householdId` resolved from the authenticated user's verified membership, never from the
request. "Not found" and "not in a household you belong to" both return `404`, unchanged.

**Roles add a second authorization axis** that did not exist before: `VIEWER` may read but not
write; only `OWNER` may invite, remove members, or delete the household. This is enforced in the
service layer, and every write endpoint needs a test proving a `VIEWER` gets `403`.

## Alternatives considered

| Option | Why not |
|---|---|
| Keep `User` as the root, share by duplication | Two copies of a transaction that must stay in sync is a correctness problem with no good answer |
| Keep `User` as the root, add a sharing/ACL table | Every query grows a join and an ownership check that is easy to forget — precisely the failure mode ADR-0008 exists to make unwritable |
| Households later, users now | The migration touches every financial table and every query in the app. Doing it before any data exists costs a day; doing it after costs a release |
| Postgres row-level security on household | Strong defence in depth and still worth adding later, but it needs per-request session variables and careful pool handling. Not the primary mechanism |

## Consequences

**Good:** sharing works properly, with real per-person identity and roles. Single-user deployments
get the general path, so the shared case is exercised constantly rather than being a rarely-tested
branch. Per-member attribution ("who added this transaction") becomes available for free.

**Bad / costs:** every query carries a membership resolution, and getting that wrong is a
cross-household data leak — the highest-severity bug this app can have. The role dimension
doubles the authorization test matrix. Invitations, member removal, and "what happens to data
when someone leaves" are new features that a single-user app would not have needed.

**Follow-ups:** update `docs/domain/model.md` and the invariants. The `security-auditor` agent's
checklist needs the household predicate and the role checks added. Household management gets its
own feature doc before slice 3.
