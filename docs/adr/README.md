# Architecture Decision Records

An ADR captures **one decision, its context, and its consequences** at the moment it was made.

## Rules

- **Immutable.** Never rewrite the substance of an accepted ADR. If the decision changes, write
  a new one that supersedes it and add a `Superseded by ADR-NNNN` line to the old one. The
  record of what we believed and why is the point.
- **Numbered sequentially**, zero-padded: `0012-short-kebab-title.md`.
- **Write it when the decision is made**, not at the end of the project.
- Use `/adr` to create one — it picks the next number and fills the template.

## When something deserves an ADR

- It is hard or expensive to reverse.
- Someone will ask "why on earth is it like this?" in six months.
- We rejected a reasonable alternative, and the reason matters.
- It changes a security posture, a data model, or a public contract.

Not everything is an ADR. A naming convention goes in `memory/conventions.md`; a surprise goes
in `memory/gotchas.md`. Reach for an ADR when the *reasoning* is what needs preserving.

## Index

| # | Title | Status | Date |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 2026-08-27 |
| [0002](0002-rewrite-to-angular-and-spring.md) | Rewrite to Angular + Spring Boot | Accepted | 2026-08-27 |
| [0003](0003-postgresql-as-datastore.md) | PostgreSQL as the datastore | Accepted | 2026-08-27 |
| [0004](0004-session-cookie-authentication.md) | Session-cookie authentication, not JWT | ~~Superseded by 0018~~ | 2026-08-27 |
| [0005](0005-monorepo-layout.md) | Monorepo: `backend/` + `frontend/` | Accepted | 2026-08-27 |
| [0006](0006-money-representation.md) | Money as BigDecimal / NUMERIC(19,4) | Accepted | 2026-08-27 |
| [0007](0007-flyway-migrations.md) | Flyway migrations, never ddl-auto | Accepted | 2026-08-27 |
| [0008](0008-user-scoped-data-access.md) | Every query scoped to the authenticated user | Accepted (amended by 0017) | 2026-08-27 |
| [0009](0009-testcontainers-over-h2.md) | Testcontainers over H2 | Accepted | 2026-08-27 |
| [0010](0010-ai-harness-and-doc-structure.md) | AI harness and documentation structure | Accepted | 2026-08-27 |
| [0011](0011-playwright-ui-validation-harness.md) | Playwright harness for agent-driven UI validation | Accepted | 2026-08-28 |
| [0012](0012-customer-facing-user-guide.md) | Customer-facing user guide captured from the running app | Accepted (amended by 0013) | 2026-08-28 |
| [0013](0013-captures-render-against-fixtures.md) | Captures render against fixtures, never a live app | Accepted | 2026-08-28 |
| [0014](0014-adopt-official-angular-skills.md) | Adopt the official Angular agent skills | Accepted | 2026-08-28 |
| [0015](0015-delete-the-legacy-app.md) | Delete the legacy app; rebuild from scratch | Accepted | 2026-08-29 |
| [0016](0016-self-hosted-open-source-product.md) | Budget Owl is a self-hosted, open-source product | Accepted | 2026-09-05 |
| [0017](0017-households-own-financial-data.md) | Households, not users, own financial data | Accepted | 2026-09-05 |
| [0018](0018-first-party-auth-with-optional-oidc.md) | First-party auth, optional OIDC, opaque tokens for mobile | Accepted | 2026-09-05 |
| [0019](0019-flutter-for-mobile.md) | Flutter for the mobile app | Accepted | 2026-09-05 |
| [0020](0020-bank-connections-use-user-credentials.md) | Bank connections use the user's own credentials | Accepted | 2026-09-05 |
| [0021](0021-licence-agpl-3.md) | Licence — AGPL-3.0 | **Proposed** | 2026-09-05 |
