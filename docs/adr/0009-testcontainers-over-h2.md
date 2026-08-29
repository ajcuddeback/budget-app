# ADR-0009: Testcontainers over H2

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

Integration tests need a database. The fast, conventional choice is in-memory H2 in
Postgres-compatibility mode. But H2 accepts SQL Postgres rejects, rejects SQL Postgres accepts,
and differs on exactly the things this app depends on: `NUMERIC` behavior, `citext`, partial
indexes, `CHECK` constraint semantics, and locking.

A test suite that passes against a database we don't ship is a suite that reports false
confidence, and it does so precisely where financial correctness lives.

## Decision

Integration tests run against **real PostgreSQL 16 via Testcontainers**, with Flyway migrations
applied. H2 is not used.

- One shared container per test run (singleton pattern), not one per class.
- Tests roll back or truncate between cases; they never depend on each other's data.
- Pure unit tests — domain logic, `Money`, mappers, validators — need no database at all and
  should not start a Spring context.

## Alternatives considered

| Option | Why not |
|---|---|
| H2 in Postgres mode | Different enough to hide real bugs and invent fake ones. False confidence on exactly our risk areas |
| A shared dev database | Tests interfere; state leaks between runs; can't run in parallel or offline |
| Only unit tests, no integration tests | Migrations, constraints, and user-scoping queries (ADR-0008) are only verifiable against a real database |

## Consequences

**Good:** tests exercise the database we actually deploy. Migrations are tested on every run.
Constraint violations surface in CI.

**Bad / costs:** Docker is required to run the test suite, and the first container start costs
a few seconds. CI needs a Docker-capable runner.

**Follow-ups:** a shared `AbstractIntegrationTest` base class owns the singleton container so no
test configures it by hand.
