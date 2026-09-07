# Architecture Overview

## Shape

Everything below runs **on the user's own hardware** (ADR-0016). There is no service of ours in
this diagram, and there must never be one in the core.

```
Browser                          Mobile app (Flutter, iOS + Android)
  │  Session cookie                 │  Bearer token from the platform
  │  (HttpOnly, Secure,             │  secure store (Keychain / Keystore)
  │   SameSite=Lax) + CSRF          │
  ▼                                 ▼
Angular SPA                         │
  │  /api/** JSON                   │  /api/** JSON
  ▼                                 ▼
        ┌──────────────────────────────┐
        │   Spring Boot API (Java 21)  │
        └──────────────────────────────┘
  │  Two credential transports, ONE authentication system (ADR-0018):
  │  same user store, same authorization, same revocation.
  ▼
Spring Boot API  (Java 21)
  ├── web        controllers, request/response DTOs, validation, error mapping
  ├── service    business rules, transactions, authorization decisions
  ├── domain     entities, value objects (Money), domain exceptions
  └── persistence repositories (Spring Data JPA), Flyway migrations
  ▼
PostgreSQL
```

## Layering rules

Dependencies point inward only: `web → service → domain`, and `persistence → domain`.

- **`web`** knows HTTP. It never contains business rules. It maps DTO → command, calls a
  service, maps result → DTO. Entities never cross this boundary in either direction.
- **`service`** owns transactions (`@Transactional` lives here, not on controllers or
  repositories) and owns **authorization decisions**. If a rule says "a user may only see their
  own accounts", the service enforces it — not the controller, not a JPA filter alone.
- **`domain`** is plain Java. No Spring annotations beyond JPA mapping, no HTTP, no framework.
- **`persistence`** exposes repositories. Query methods take the owner's id as a parameter;
  there are no repository methods that fetch user-scoped data without it.

A controller that calls a repository directly is a bug. So is a service that returns an entity
to a controller.

## Package layout (backend)

```
com.budgetapp
├── config/            security, jackson, web, flyway configuration
├── common/            error handling, Money, pagination, base types
└── <feature>/         one package per bounded feature — vertical slices
    ├── web/           <Feature>Controller, <Feature>Request/Response
    ├── service/       <Feature>Service
    ├── domain/        entities, value objects, domain exceptions
    └── persistence/   <Feature>Repository
```

Organize by **feature**, not by layer-at-the-top. `com.budgetapp.transaction.web` — not
`com.budgetapp.web.transaction`. Features are the unit of change; layers are the unit of
discipline within a feature.

## Deployment shape

The deployable unit is a **`docker-compose.yml` plus published images** (ADR-0016): API,
Postgres, and the SPA served as static assets behind the API or a small reverse proxy. The
mobile app is installed from a store and **pointed at the user's own instance** — its server URL
is user-supplied configuration, so it must cope with LAN hostnames, self-signed certificates and
non-standard ports.

Consequences that shape the code, not just the ops story:

- **No component may require a service we operate.** Not for auth, not for updates, not for
  telemetry (ADR-0016).
- **Migrations run unattended** on a machine nobody is watching (ADR-0007). A migration that
  needs a human is a migration that eats someone's data at 3am.
- **First run must work** with nothing configured beyond a database password.
- **We cannot see failing instances.** Diagnostics are things the user can run and choose to
  share, which makes clear error messages a product feature rather than a nicety.

## Frontend shape

```
frontend/src/app
├── core/         singletons: auth, http interceptors, guards, error handling
├── shared/       dumb reusable components, pipes, directives
├── features/     one lazy-loaded route module per feature
└── styles/       design tokens, global styles
```

Standalone components, signals for state, typed reactive forms, lazy routes.
See `docs/guides/angular-style.md`.

## Mobile shape

```
mobile/lib
├── core/       config (incl. the user's server URL), http, auth, storage, theme
├── shared/     reusable widgets, formatters
└── features/   one directory per feature, mirroring the backend's packages
```

Flutter, one codebase for iOS and Android (ADR-0019). Riverpod for state, Drift for the offline
cache, `flutter_secure_storage` for tokens. See `docs/guides/flutter-style.md`.

Offline is a normal condition rather than an error path: reads come from the local cache and
writes queue, because a budgeting app that is blank on a train is useless.

## Cross-cutting decisions

Recorded as ADRs — read `docs/adr/README.md` for the index. The ones that shape everything:

- **ADR-0002** Angular + Spring rewrite of the legacy MERN app
- **ADR-0003** PostgreSQL
- **ADR-0004** Session-cookie authentication (not JWT)
- **ADR-0005** Monorepo layout
- **ADR-0006** Money is `BigDecimal` / `NUMERIC(19,4)`, never floating point
- **ADR-0007** Flyway migrations, never `ddl-auto`
- **ADR-0008** Every query is scoped to the owner — amended by ADR-0017 to the **household**
- **ADR-0016** Self-hosted, open-source product — nothing may require a service we operate
- **ADR-0017** Households own financial data; roles gate what a member may do
- **ADR-0018** First-party auth, optional OIDC, opaque tokens for mobile (supersedes ADR-0004)
- **ADR-0019** Flutter for the mobile app
