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
| Forms | Typed reactive forms | Template-driven forms are not used |
| Styling | SCSS + design tokens | No component library lock-in yet |
| Testing | Jest + Angular Testing Library; Playwright for E2E | |
| Lint/format | ESLint + Prettier | |

## Local environment

Verified present in the dev container: Java 21.0.10, Maven 3.9.11, Node 22.22.2, npm 10.9.7,
Docker 29.3.1.

Postgres runs in Docker for local dev (`tools/dev-up.sh`). Tests use Testcontainers against
**real Postgres** — never H2. An in-memory database that accepts SQL Postgres would reject is
a test that lies. See ADR-0009.

## Deprecated — do not use

The legacy stack in `client/` and `server/`: React 17, Express, Sequelize, MySQL/JawsDB,
`express-session`, styled-components. Reference only. See `docs/domain/legacy-app.md`.
