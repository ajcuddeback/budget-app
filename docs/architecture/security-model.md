# Security Model

**Read this before changing anything that touches authentication, authorization, sessions,
cookies, headers, or user data access.** This app holds people's financial records. Treat every
change here as high-risk.

## Threat assumptions

We assume an attacker can: send arbitrary HTTP requests, control their own browser, get a user
to click a link, host a hostile page, and register their own account. We assume they cannot
read the server's environment or database directly.

The failures we most care about, in order:
1. **Broken object-level authorization** — user A reads or edits user B's financial data.
2. **Session hijacking / fixation** — an attacker acts as a logged-in user.
3. **CSRF** — a hostile page causes a state change in an authenticated session.
4. **Injection** — SQL or template injection through unvalidated input.
5. **Sensitive data exposure** — secrets, PII, or amounts leaking into logs, errors, or URLs.

## Authentication: server-side sessions (ADR-0004)

We use Spring Security with server-side sessions, **not** JWTs. Rationale and rejected
alternatives are in ADR-0004; the short version is that a first-party SPA gains nothing from
stateless tokens and loses instant revocation.

Rules:

- Session cookie is `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`. Never readable from JS.
- **Rotate the session id on login** (`changeSessionId`) — prevents session fixation.
- Invalidate the session server-side on logout. Clearing the cookie is not enough.
- Idle timeout **30 minutes**, absolute timeout **12 hours**. Both enforced server-side.
- Sessions are stored in Postgres (Spring Session JDBC) so they survive restarts and can be
  revoked administratively.
- Concurrent sessions per user are capped; the cap is configuration, not a magic number in code.

## Passwords

- Hash with **BCrypt** (strength ≥ 12) via `DelegatingPasswordEncoder`, so the algorithm can be
  upgraded later without a flag day.
- **Never** log, return, or include a password or hash in any DTO, error, or trace.
- Minimum length 12, no composition rules, and check against a breached-password list.
  Length beats complexity; NIST agrees.
- Login must be **constant-time with respect to account existence**: same response body, same
  status, same rough latency whether or not the username exists. Never "no user found at this
  username" — that is a user enumeration oracle. (The legacy app did exactly this; don't
  reproduce it.)
- Rate-limit login and registration per IP **and** per account. Lock out with backoff, not
  permanently.

## CSRF

Because we authenticate with cookies, CSRF protection is **mandatory** and must not be disabled.

- Spring Security CSRF enabled with `CookieCsrfTokenRepository` (cookie readable by JS, token
  echoed in the `X-XSRF-TOKEN` header). Angular's `HttpClient` does this automatically when the
  cookie is named `XSRF-TOKEN`.
- `SameSite=Lax` on the session cookie is defense in depth, **not** a replacement for tokens.
- If you find yourself writing `.csrf(csrf -> csrf.disable())`, stop. That line does not belong
  in this codebase. If a specific endpoint genuinely needs an exemption (a webhook with its own
  signature verification), exempt that one path and document why in the feature doc.

## Authorization (ADR-0008)

**Every** query that reads or writes user-owned data is scoped to the authenticated principal.

```java
// WRONG — trusts the path variable to imply ownership
accountRepository.findById(accountId);

// RIGHT — ownership is part of the query
accountRepository.findByIdAndUserId(accountId, currentUser.id());
```

- Never derive the acting user from a request body, query parameter, or path variable. It comes
  from the `SecurityContext`, always.
- A missing row and a row owned by someone else must be **indistinguishable** to the caller:
  both return `404`. Returning `403` for "exists but not yours" tells an attacker the row exists.
- Authorization lives in the service layer. Method security (`@PreAuthorize`) is fine as an
  additional gate, not as the only one.
- Every new endpoint needs a test proving user B gets `404` on user A's resource. This is the
  single highest-value test in the codebase — see `docs/guides/testing-style.md`.

## Input validation and output encoding

- Bean Validation (`@Valid`) on every request DTO. Validate types, ranges, lengths, and formats
  at the edge — before the value reaches a service.
- Amounts: reject non-finite, absurd magnitudes, and wrong scale explicitly rather than letting
  them through to the database.
- Use JPA parameter binding or named parameters exclusively. **Never** concatenate user input
  into JPQL, native SQL, or an `ORDER BY`. Sort fields come from an allowlist enum, not a string.
- Angular's default interpolation escapes output. Do not reach for `bypassSecurityTrustHtml`;
  if you think you need it, you need a different design.

## Headers and transport

Set at the gateway or in Spring Security config:

- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `Content-Security-Policy` — no `unsafe-inline`, no `unsafe-eval`, explicit allowlist
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `X-Frame-Options: DENY` / `frame-ancestors 'none'`
- `Cache-Control: no-store` on every authenticated JSON response

CORS: an explicit origin allowlist with `allowCredentials=true`. **Never** `*` with credentials —
that combination is rejected by browsers anyway and signals a misunderstanding.

## Secrets and configuration

- No secrets in source, `application.yml`, tests, fixtures, or docs. Environment variables only.
- `application.yml` holds structure and non-secret defaults; every secret is `${ENV_VAR}` with
  **no fallback default**. A missing secret must fail startup loudly, not silently use a default.
- A `PreToolUse` hook blocks writes containing likely secrets. If it fires on real content,
  fix the content — do not restructure the string to sneak past the check.

## Logging and errors

- Never log: passwords, session ids, CSRF tokens, full account numbers, or auth headers.
- Amounts and balances are sensitive. Log identifiers, not values, unless you have a reason.
- Error responses are RFC 7807 `application/problem+json` with a generic message. Stack traces,
  SQL, and framework internals never reach the client. The correlation id does — so support can
  find the real error in the logs.
- Log authentication events (success, failure, logout, lockout) with user id and source IP.
  These are the audit trail.

## Dependencies

- `mvn dependency-check` / OWASP scanning and `npm audit` run in CI; a high or critical
  vulnerability fails the build.
- Pin versions. No version ranges, no `latest`.

## When you are unsure

Ask. A question costs a minute; a broken authorization check costs a user's financial privacy.
Use the `security-auditor` agent on any change touching this document's subject matter —
including your own.
