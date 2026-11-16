# ADR-0030: The runtime connects as a role that cannot change the schema

- **Status:** Accepted
- **Date:** 2026-11-16
- **Deciders:** Repository owner

## Context

Budget Owl's schema does real work. `V8` requires a named algorithm in `password_hash`. `V9` bounds
an account name, constrains `last_four` to exactly four digits, and restricts `type` and `currency`
to closed sets. A `DEFERRABLE INITIALLY DEFERRED` constraint trigger enforces the last-owner rule
inside the transaction rather than in application code that races. The project's own guidance says
why: the application validates for good messages, and the database enforces what stays true when
something bypasses the edge.

**Today none of that is enforced against the application itself.** The runtime connects as
`${DB_USER}`, which is `POSTGRES_USER` — the role that owns every table. An owner can
`TRUNCATE`, `ALTER TABLE … DROP CONSTRAINT`, and `SET session_replication_role = replica` to skip
triggers entirely. So every guarantee above is advisory: a bug, a careless migration path, or
anything that reaches a `Statement` can step around the lot, and the database will cooperate.

A security audit raised this in slice 2 as finding **F8**. It was recorded in the deployment notes
rather than fixed, and the note has been carried forward in every review since, growing a slice at
a time. `V9` added four more constraints that the runtime can drop.

The reason it was deferred is real: **role creation cannot live in a migration.** Creating a role
needs its password, and a password in a migration file is a secret in the repository — the first
non-negotiable. Roles are also cluster-level objects, not database-level, so a migration is the
wrong place for one even without the secret.

## Decision

**Two roles. The owner runs migrations. The runtime connects as a role that cannot change the
schema.**

|                                        | Owner (`DB_USER`)          | Application (`DB_APP_USER`) |
| -------------------------------------- | -------------------------- | --------------------------- |
| Owns the tables                        | yes                        | no                          |
| DDL (`CREATE`, `ALTER`, `DROP`)        | yes                        | **no**                      |
| `TRUNCATE`                             | yes                        | **no**                      |
| `SET session_replication_role`         | superuser only, so neither | **no**                      |
| `SELECT`, `INSERT`, `UPDATE`, `DELETE` | yes                        | yes                         |
| Used by Flyway                         | **yes**                    | no                          |
| Used by the connection pool            | no                         | **yes**                     |

Concretely:

- **Flyway gets its own credentials** through `spring.flyway.user` / `spring.flyway.password`,
  defaulting to the owner. `spring.datasource.*` serves the application and defaults to the
  application role.
- **The role is created where roles belong — in deployment**, by a Postgres init script in the
  Compose stack, from environment variables. Not in a migration.
- **The grants live in a migration**, because privileges on tables are schema-shaped and have to
  follow every table the schema gains. `V10` grants DML on existing tables and sets
  `ALTER DEFAULT PRIVILEGES` so future tables are covered without another migration.
- **The migration is conditional on the role existing.** A development machine, a Testcontainers
  database, and any instance that has not yet been reconfigured simply have no such role, and the
  grant block does nothing. Migrations must not fail because deployment has not caught up.
- **A test proves the restriction**, by connecting as a genuinely restricted role and asserting
  that `TRUNCATE` and `DROP CONSTRAINT` are refused. A privilege model nothing exercises is a
  privilege model nobody can trust, and this one is invisible from the application's own tests —
  they connect as the owner.

## Alternatives considered

| Option                                                                | Why not                                                                                                                                                                                                                                                                                                                                                                                                                             |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Leave it; the operator can read the database anyway (ADR-0026)**    | Conflates two things. ADR-0026 is about what the _person_ running the instance can see, which is everything, deliberately. This is about what the _application process_ can do if it is compromised or buggy — and an attacker with SQL execution inside the app is not the operator. Defence in depth is the whole argument for having database constraints at all; an application that can drop them has constraints in name only |
| **Create the role in a migration**                                    | A role needs a password, and a password in a migration is a secret in the repository (non-negotiable #1). Roles are cluster-level, so a per-database migration is the wrong scope regardless. This is the reason the finding sat unfixed, and it is worth stating so nobody retries it                                                                                                                                              |
| **One role, with the owner stripped of DDL after migration**          | Means revoking from the owner of the objects, which Postgres largely ignores — an owner's rights are implicit. It also leaves nothing able to run the next migration                                                                                                                                                                                                                                                                |
| **`REVOKE TRUNCATE` from the application role**                       | Already implied: the role is granted only what it needs, so there is nothing to revoke. Listing revocations instead of grants is the wrong default — it fails open for anything nobody thought of                                                                                                                                                                                                                                   |
| **Run migrations as a separate job rather than at application start** | Cleaner in principle, and a real option later. It is a bigger change to the deployment story (an init container or a documented manual step), and it does not by itself stop the runtime from having DDL. Orthogonal; this ADR does not block it                                                                                                                                                                                    |

## Consequences

**Good:** every `CHECK`, every `NOT NULL`, every constraint trigger and the last-owner rule stop
being advisory and start being enforced against the process most likely to get something wrong.
SQL injection that reaches a statement can no longer drop a constraint or truncate a table. The
blast radius of an application-level compromise shrinks to what the application is supposed to do
anyway, which is read and write rows.

**Bad / costs:** a second credential to configure, and **an upgrade step for anyone already
running Budget Owl** — the init script only runs on a fresh database volume, so an existing
instance needs the role created by hand. That is documented in the deployment notes, and it is the
main cost of fixing this late rather than in slice 1. Local development and the test suite keep
working unchanged, because the grant block is conditional, which also means **neither exercises the
restricted path by default** — the dedicated test is what covers it.

A future migration that needs a privilege the application role lacks will fail at runtime rather
than at review. That is the intended behaviour, and the failure names the missing grant.

**Follow-ups:** consider moving migrations out of application start-up entirely, so the owner's
credentials need not be present in the running container at all. That is the stronger version of
this decision and is worth its own ADR when the admin console (slice 9) gives upgrades a home.
