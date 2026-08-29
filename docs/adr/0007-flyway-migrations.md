# ADR-0007: Flyway migrations, never ddl-auto

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

The legacy app called `sequelize.sync()` at startup, letting the ORM infer and apply schema
changes. That gives no review point, no rollback, no history, and no way to express a data
migration — and it can drop or rewrite columns based on a code change nobody read carefully.

## Decision

All schema changes are **Flyway** migrations in
`backend/src/main/resources/db/migration`, named `V<n>__<description>.sql`.

- Hibernate runs with `ddl-auto: validate` in every environment. Never `update`, never `create`.
- Migrations are **append-only**. Once merged, a migration is never edited — a mistake is fixed
  by a new migration.
- Migrations are plain SQL, reviewed like code.
- Constraints (`NOT NULL`, `CHECK`, foreign keys, unique indexes) are expressed in the migration.
  The database is the last line of defense for data integrity and is expected to enforce it.

## Alternatives considered

| Option | Why not |
|---|---|
| Hibernate `ddl-auto: update` | No review, no history, no rollback, no data migrations; silently destructive |
| Liquibase | Comparable and fine; Flyway's plain-SQL model is simpler to review and to generate correctly |
| Hand-applied SQL | Environments drift immediately |

## Consequences

**Good:** schema history is versioned, reviewable, and identical across environments.
`validate` catches entity/schema drift at startup rather than at 2 a.m.

**Bad / costs:** every model change is two edits (entity + migration), and the pair must be kept
in sync. Contributors must know that a merged migration is frozen.

**Follow-ups:** `tools/verify.sh` runs migrations against a throwaway Postgres so a broken
migration fails locally, not in deploy.
