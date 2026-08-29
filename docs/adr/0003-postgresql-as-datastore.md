# ADR-0003: PostgreSQL as the datastore

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

The legacy app used MySQL (JawsDB on Heroku) with Sequelize. The new system stores financial
records, where exact arithmetic and strong constraints matter more than anything else in the
data layer.

## Decision

We use PostgreSQL 16.

Deciding factors: exact `NUMERIC` arithmetic for money (ADR-0006); real `CHECK` constraints,
partial indexes, and deferrable foreign keys; `citext` for case-insensitive email uniqueness;
first-class support in Flyway, Testcontainers, and Spring Data; and row-level security available
if we ever want a second enforcement layer under ADR-0008.

## Alternatives considered

| Option | Why not |
|---|---|
| Stay on MySQL 8 | Would ease legacy data migration, but weaker constraint support and a worse `NUMERIC` and type story for financial data |
| SQLite | Fine for local dev, not for a multi-user hosted app |
| A document store | Financial data is deeply relational; we would rebuild joins and constraints by hand |

## Consequences

**Good:** exact money arithmetic; constraints enforced by the database, not just the app;
excellent tooling.

**Bad / costs:** legacy MySQL data needs an explicit migration rather than a straight copy.
Local dev needs Docker for a Postgres instance.

**Follow-ups:** `tools/dev-up.sh` runs Postgres locally. ADR-0009 requires tests to run against
real Postgres via Testcontainers, so dialect differences surface in CI rather than production.
