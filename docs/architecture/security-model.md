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

## Authentication: sessions for web, opaque tokens for mobile (ADR-0018)

Two credential transports, **one** authentication system — same user store, same authorization,
same revocation. ADR-0018 supersedes ADR-0004; the reasoning for keeping sessions on the web is
unchanged, and mobile is added rather than swapped in.

**Web — server-side sessions.** As below. Still the most XSS-resistant option for a browser.

**Mobile — opaque bearer tokens.** Deliberately opaque and stored server-side rather than JWT, so
revocation stays a `DELETE`: a stolen phone's access dies immediately. Tokens carry a device
label and last-used time so the user gets a revocable "logged-in devices" list. On the device
they live in the platform secure store (Keychain / Keystore) and nowhere else.

**Optional OIDC.** Off by default, configurable by self-hosters who already run an identity
provider. It is an additional login route, never a requirement — ADR-0016 forbids depending on
any service we operate, and that includes one the user would have to stand up. Rules below.

### Email + password is permanent

**Password login is a permanent capability of Budget Owl and is always present in the build.**
It is not a fallback, not a legacy path, and not something a future release removes. A fresh
instance authenticates with email and password and needs nothing else configured.

This is a product guarantee, not just a default (ADR-0016): a self-hoster must be able to run
Budget Owl on a machine with no identity provider, no internet access and no account with us.

### Optional OIDC, and the lockout rule

- **Configured at runtime by an instance admin**, in the app, not by environment variable. A
  typo in an env var means editing a Compose file and restarting a container; a typo in a form
  is fixed in the browser. Self-hosters are configuring this at 11pm on a machine in a cupboard.
- **Additive.** Enabling OIDC adds a "Sign in with…" route. It changes nothing about how
  requests are authorized afterwards — an OIDC session and a password session are the same
  session, and an OIDC-authenticated user gets tokens on mobile the same way.
- **An admin may disable password login** on their own instance once they prefer their provider —
  but only through this safeguard:

  > **Password login cannot be disabled until at least one `OWNER` has completed a successful
  > OIDC login.** The setting is refused otherwise.

  This is a mechanism rather than a warning for a reason: "disable password login, then discover
  the OIDC config is wrong" locks the owner out of their own financial records on their own
  hardware, with no support desk to call. Proving the new route works before removing the old
  one is the only version of this that is safe.

- **Recovery path regardless.** A self-hoster has shell access to their own box, so an
  administrative command that re-enables password login must exist and be documented. This is
  the escape hatch when a provider changes its endpoints, a certificate expires, or a container
  is restored from a backup with stale configuration.
- **No auto-join.** An OIDC login may provision a *user* if the admin allows it, but never grants
  membership of a household (ADR-0017). Household access comes from an invitation, always.
  Otherwise anyone in the provider's directory would land inside someone's finances.

Rules below apply to both transports unless stated otherwise.

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

## Authorization (ADR-0008, amended by ADR-0017 and ADR-0026)

**Every** query that reads or writes financial data is scoped to a **household the authenticated
user is a verified member of** — and then checked against their **role** in it.

```java
// WRONG — trusts the path variable to imply ownership
accountRepository.findById(accountId);

// WRONG — scopes to a household id taken from the request
accountRepository.findByIdAndHouseholdId(accountId, request.householdId());

// RIGHT — household resolved from verified membership, never from the request
accountRepository.findByIdAndHouseholdId(accountId, membership.householdId());
```

**Two axes, both enforced in the service layer:**

1. *Which household* — resolved from the authenticated user's membership, never from the request.
2. *What may this role do* — `VIEWER` reads only; only `OWNER` may invite, remove members, delete
   the household, **or reach the admin console**. Every write endpoint needs a test proving a
   `VIEWER` gets `403`; every admin endpoint needs one proving a `MEMBER` does.

**An instance holds exactly one household (ADR-0026), and its `OWNER` is the instance operator.**
That is who the admin console belongs to — there is no separate instance-admin role. Read the
consequences of that ADR before relying on axis 1 for anything: with one household there is no
second household to leak to, so *role enforcement and authentication now carry the weight that
household scoping used to share*. Keep writing the scoped query anyway — the shape is what makes
the dangerous call unwritable, and it is what a managed deployment would need.

- Never derive the acting user from a request body, query parameter, or path variable. It comes
  from the `SecurityContext`, always.
- A missing row and a row owned by someone else must be **indistinguishable** to the caller:
  both return `404`. Returning `403` for "exists but not yours" tells an attacker the row exists.
- Authorization lives in the service layer. Method security (`@PreAuthorize`) is fine as an
  additional gate, not as the only one.
- Every new endpoint needs a test proving a lower-privileged role is refused: a `VIEWER` gets
  `403` on any write, a `MEMBER` gets `403` on anything administrative. Since ADR-0026 this is the
  single highest-value authorization test in the codebase — it replaces the cross-household test,
  which no longer has a second household to prove anything against. See
  `docs/guides/testing-style.md`.

### The members of a household can see each other's finances

This is by design (ADR-0026), and it is the honest consequence of not encrypting data against the
person who runs the server. The security requirement is therefore a **disclosure** one: anyone
accepting an invitation must be told, in plain words and before the account exists, that the
person hosting the instance can see everything they record. An invitation flow that omits that is
a security defect, not a copy problem.

## The assistant (ADR-0027)

The assistant needs the whole financial picture to answer anything useful, which makes it the
largest privacy surface in the product. It is constrained structurally rather than by policy:

- It runs **on the instance, in a separate optional container**. There is no configuration in
  which financial data is sent to a third-party model or to any endpoint we operate — those
  options were removed, not deferred.
- The sidecar is reachable **only on the internal container network**. It is never published to
  the host or the internet, and the Compose file must not expose its port.
- **Prompts and responses are not logged.** A prompt contains the user's finances by construction,
  so logging one writes financial data into a file that outlives the request and often leaves the
  box in a bug report. Log that a request happened, its duration and its outcome — never content.
- The assistant reads through the same **household-scoped, role-checked services** as everything
  else. It is not given a privileged database connection because it is "internal", and a `VIEWER`
  asking it a question must not receive data a `VIEWER` could not otherwise see.
- Anything it **proposes** — a changed goal contribution, a moved envelope amount — is a proposal
  until a human confirms it. The model never writes to financial data directly.

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
