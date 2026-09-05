# Tech Stack

Pin versions. No ranges, no `latest`. When you bump something, note it here.

## Backend

| Thing | Choice | Why |
|---|---|---|
| Language | Java 21 (LTS) | Records, pattern matching, virtual threads |
| Framework | Spring Boot 3.x | Spring Security 6, Jakarta EE 10 |
| Build | Maven | Verified available: 3.9.x |
| Security | Spring Security 6 + Spring Session JDBC | ADR-0004 |
| Persistence | Spring Data JPA / Hibernate | |
| Migrations | Flyway | ADR-0007 |
| Database | PostgreSQL 16 | ADR-0003 |
| Validation | Jakarta Bean Validation (Hibernate Validator) | |
| Mapping | MapStruct **or** hand-written mappers | No reflection-based magic in the hot path |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, Spring Security Test | ADR-0009 |
| Format | Spotless (google-java-format AOSP) | Formatting is not a review topic |
| Static analysis | Error Prone + SpotBugs (`find-sec-bugs` plugin) | |
| API docs | springdoc-openapi | Generated, never hand-maintained |

## Frontend

| Thing | Choice | Why |
|---|---|---|
| Framework | Angular (latest stable major) | |
| Language | TypeScript, `strict: true` | Non-negotiable |
| Components | Standalone (no NgModules) | |
| State | Angular signals; RxJS at the HTTP boundary | |
| Forms | Typed reactive forms | Not Signal Forms — see the override table in `docs/guides/angular-style.md` |
| Styling | SCSS + design tokens | No component library lock-in yet |
| Testing | Vitest + Angular Testing Library; Playwright for E2E | Angular's default runner (ADR-0014) |
| Lint/format | ESLint + Prettier | |

## Mobile (ADR-0019)

| Thing | Choice | Why |
|---|---|---|
| Framework | Flutter (iOS + Android, one codebase) | Immich's mobile client is Flutter — same audience, same self-hosted shape |
| Language | Dart | Small language, unremarkable from Java |
| State | Riverpod | |
| Navigation | go_router | |
| HTTP | Dio, bearer-token interceptor | ADR-0018 |
| Models | freezed + json_serializable | No `dynamic` maps threaded through the app |
| Offline cache | Drift (SQLite) | Phones lose signal; blank screens are unacceptable |
| Secure storage | flutter_secure_storage → Keychain / Keystore | Tokens live nowhere else |
| Money | `decimal` package. **Never `double`** | ADR-0006 applies to every client |
| Testing | flutter_test, mocktail, integration_test | |

Versions to be confirmed when the app is scaffolded — this table predates it.

## Deployment (ADR-0016)

| Thing | Choice | Why |
|---|---|---|
| Distribution | Docker Compose + published images | The self-hoster's first experience |
| Database | PostgreSQL 16 in the same Compose stack | |
| Migrations | Flyway, run unattended on startup | Nobody is watching the machine |
| Config | Environment variables, sane defaults | First run needs only a database password |
| Reverse proxy | The user's (Traefik, Caddy, nginx) — we do not ship one | Self-hosters already have one and opinions about it |

Nothing here may require a service we operate — no auth server, no update check, no telemetry.

## Local environment

Verified present in the dev container: Java 21.0.10, Maven 3.9.11, Node 22.22.2, npm 10.9.7,
Docker 29.3.1.

Postgres runs in Docker for local dev (`tools/dev-up.sh`). Tests use Testcontainers against
**real Postgres** — never H2. An in-memory database that accepts SQL Postgres would reject is
a test that lies. See ADR-0009.

## Removed

The original stack — React 17, Express, Sequelize, MySQL/JawsDB, `express-session`,
styled-components — was deleted in the clean-slate rewrite (ADR-0015). Do not reintroduce any of
it. What that app did is recorded in `docs/domain/legacy-app.md`.
