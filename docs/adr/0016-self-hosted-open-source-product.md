# ADR-0016: Budget Owl is a self-hosted, open-source product

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** Repository owner

## Context

The project began as a personal budgeting app for one person. The intent has changed: Budget Owl
is to be an open-source, self-hostable product in the shape of Immich — you run it on your own
hardware, and your data stays there.

This is a product decision with large engineering consequences, so it is recorded before the code
that would assume otherwise gets written. The alternative — discovering the product is
multi-tenant SaaS after building for single-tenant, or vice versa — is a rewrite of the data
model and the auth layer.

The category is not empty. Actual Budget and Firefly III are established, self-hosted, and good.
What neither does well is mobile. That is the opening, and it is the reason this is worth
building rather than adopting one of them.

## Decision

Budget Owl is distributed as **open-source software that people run themselves**. The primary
deployment is Docker Compose on the user's own hardware.

Consequences we accept as first-class product requirements, not afterthoughts:

- **The deployable unit is a `docker-compose.yml` plus published images.** Onboarding is "copy
  this file and run it". If that is not a good experience, the product is not good.
- **Upgrades and backups are features.** A self-hoster who loses a year of budget data to a bad
  migration will not come back, and will say so publicly. Flyway migrations (ADR-0007) must be
  safe to run unattended on a machine nobody is watching.
- **No mandatory external service.** Nothing in the core may require an account with us, an
  internet connection, or a third-party SaaS. This directly constrains auth (ADR-0018) and AI.
- **Configuration is environment variables with sane defaults**, documented, and a first run that
  works with no configuration beyond a database password.
- **We have no access to user instances.** No telemetry by default, no phone-home, no remote
  support path. Diagnostics are things the user can run and choose to share.

The current architecture — Angular SPA, Spring Boot API, PostgreSQL — already fits this shape and
does not change.

## Alternatives considered

| Option | Why not |
|---|---|
| SaaS with an optional self-host tier | The self-hosted path always rots when it is second. It also puts us in the business of holding other people's financial data, which is the thing our users are trying to avoid |
| Local-only desktop app (no server) | Kills household sharing and the mobile app, which are the two things we are differentiating on |
| Contribute mobile to Actual Budget instead | Genuinely worth considering, and cheaper. Rejected because the differentiation is a coherent product built mobile-first, not a bolted-on client — but this is the strongest argument against building at all, and it should stay uncomfortable |

## Consequences

**Good:** the privacy story is architectural rather than promissory, which is both more honest
and more defensible. The audience is well-defined and reachable. No compliance burden from
holding user financial data, because we hold none.

**Bad / costs:** we own a distribution problem — images, versioning, upgrade paths, backup
documentation, and support for hardware we cannot see. Debugging is harder when you cannot look
at the failing instance. Every feature must work without any service we operate. Monetisation, if
it ever happens, is harder than SaaS by construction.

**Follow-ups:** ADR-0017 (households), ADR-0018 (auth), ADR-0019 (mobile), ADR-0020 (bank
connections), ADR-0021 (licence). Compose file and image publishing land with slice 1.
