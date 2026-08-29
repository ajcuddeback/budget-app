# Architecture Overview

## Shape

```
Browser
  │  HTTPS only. Session cookie (HttpOnly, Secure, SameSite=Lax) + CSRF token header.
  ▼
Angular SPA  ── served as static assets, separate origin or same-origin reverse proxy
  │  /api/**  JSON
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

## Cross-cutting decisions

Recorded as ADRs — read `docs/adr/README.md` for the index. The ones that shape everything:

- **ADR-0002** Angular + Spring rewrite of the legacy MERN app
- **ADR-0003** PostgreSQL
- **ADR-0004** Session-cookie authentication (not JWT)
- **ADR-0005** Monorepo layout
- **ADR-0006** Money is `BigDecimal` / `NUMERIC(19,4)`, never floating point
- **ADR-0007** Flyway migrations, never `ddl-auto`
- **ADR-0008** Every query is scoped to the authenticated user
