# ADR-0004: Session-cookie authentication, not JWT

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

The API serves exactly one client: our own Angular SPA, on our own origin. There are no
third-party consumers, no mobile app yet, and no federation requirement. Security is the top
priority for this project.

The legacy app used `express-session` but configured it badly (`httpOnly: false`, no CSRF, no
session rotation on login).

## Decision

We authenticate with **server-side sessions** managed by Spring Security, stored in Postgres via
Spring Session JDBC. The session id travels in a cookie that is `HttpOnly`, `Secure`,
`SameSite=Lax`. CSRF protection is mandatory and always enabled.

Full rules — rotation, timeouts, concurrency caps — are in `docs/architecture/security-model.md`.

## Alternatives considered

| Option | Why not |
|---|---|
| JWT access + refresh tokens | Stateless is a benefit we cannot use: one client, one origin. It costs us instant revocation and adds rotation, replay detection, and a token-storage problem. Tokens in `localStorage` are XSS-readable; tokens in cookies need CSRF protection anyway — so we would take on JWT's complexity *and* keep CSRF |
| External OIDC (Keycloak/Auth0) | Strongest posture and gets MFA free, but adds an external dependency and local-dev setup cost that is disproportionate at this stage. Revisit if we need SSO, social login, or MFA — this ADR would be superseded, not amended |
| Sessions in memory | Lost on restart, and unusable across more than one instance |

## Consequences

**Good:** revocation is a `DELETE`. The credential is unreadable from JavaScript, so an XSS bug
is not automatically an account takeover. Spring Security's defaults do most of the work.

**Bad / costs:** the API is stateful — horizontal scaling requires the shared session store
(which we have). CSRF tokens must be handled correctly on every state-changing request. A future
mobile client would need this decision revisited.

**Follow-ups:** Angular's `HttpClient` reads the `XSRF-TOKEN` cookie automatically when named
conventionally — the frontend needs `withCredentials: true` on API calls and no manual token
plumbing.
