# Feature: User accounts & authentication

- **Status:** Planned
- **Owner:** Repository owner
- **Last updated:** 2026-08-27
- **Related:** ADR-0004 (session cookies), ADR-0008 (user-scoped access),
  `../architecture/security-model.md`

> ⚠️ **OUT OF DATE — do not implement from this document as written.**
>
> It was specified against ADR-0004 (session cookies only, single-user). Both premises changed:
>
> - **ADR-0017** makes the `Household` the ownership root. Registration must create a household;
>   membership and roles do not appear here at all.
> - **ADR-0018** supersedes ADR-0004. Web keeps sessions, **mobile uses opaque bearer tokens**,
>   and **OIDC is an optional login route**. None of that is described below.
> - **ADR-0016** forbids requiring any service we operate — which is *why* OIDC is optional.
>
> Everything about enumeration resistance, timing, rate limiting, session fixation and credential
> handling below remains correct and should be carried forward.
>
> **Rewrite this with `/feature-doc` before slice 2 starts.** It is left in place rather than
> deleted because its security rules are the most carefully-reasoned part of the spec.

> This is the **worked example** of a feature doc as well as a real spec. It is the first
> vertical slice of the rewrite: everything else depends on knowing who is asking.

## Purpose

A person can create an account, sign in, stay signed in across a browser session, and sign out.
Their financial data is visible only to them. Nothing else in the app works until this does.

## User stories

- As a new user, I want to register with an email and password so I can start budgeting.
- As a returning user, I want to sign in and stay signed in while I work.
- As a user, I want to sign out and know the session is genuinely over.
- As a user, I want a wrong password not to reveal whether an account exists.

## Rules and behavior

### Registration
- Email and password required. Display name optional; defaults to the email's local part.
- Email is normalized to lowercase and unique (`citext`). Duplicate registration returns the
  **same generic success-shaped response** as a new registration — it must not confirm that an
  address is already registered. (Confirmation email is where a real duplicate is surfaced,
  once email is wired up.)
- Password: minimum 12 characters, maximum 128, checked against a breached-password list.
  No composition rules — length beats complexity.
- Hashed with BCrypt strength ≥ 12 via `DelegatingPasswordEncoder`.
- Registration does **not** automatically sign the user in. It returns `201`; the client then
  posts to the login endpoint. (The legacy app auto-authenticated on registration, which makes
  the registration endpoint an authentication bypass surface.)
- New users receive the default category set.

### Login
- Accepts email and password.
- **Identical response for unknown email and wrong password**: `401` with a generic message.
  Latency must not distinguish them either — always run the password hash comparison, even when
  no user was found, against a dummy hash.
- On success: rotate the session id (`changeSessionId`), then return `200` with the user profile.
- Rate limited per IP **and** per email. Exponential backoff after repeated failures; never a
  permanent lock (that's a denial-of-service against the real user).
- Failed and successful attempts are logged with email, source IP, and outcome.

### Session
- Cookie `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`.
- Idle timeout 30 minutes; absolute timeout 12 hours. Both server-side.
- Stored in Postgres via Spring Session JDBC.
- Concurrent sessions per user capped (configurable, default 5); oldest evicted.

### Logout
- Invalidates the session **server-side** and clears the cookie. Returns `204`.
- Idempotent: logging out when not logged in also returns `204`, revealing nothing.

### Current user
- `GET /api/auth/me` returns the authenticated profile, or `401`. The SPA uses this on boot to
  decide whether to show the app or the login screen.

## Data model

Adds `users` and Spring Session's tables.

```
users
  id             uuid        pk
  email          citext      not null, unique  (uq_users_email)
  display_name   text        not null
  password_hash  text        not null
  status         text        not null  (ACTIVE | LOCKED | DISABLED)
  created_at     timestamptz not null
  updated_at     timestamptz not null
```

Migrations: `V1__create_users.sql`, `V2__create_spring_session.sql`

`password_hash` is never selected into a DTO, never logged, never returned. Consider a separate
projection for reads so it can't leak by accident.

## API

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/auth/register` | Create an account | public, rate-limited |
| `POST` | `/api/auth/login` | Start a session | public, rate-limited |
| `POST` | `/api/auth/logout` | End the session | authenticated |
| `GET` | `/api/auth/me` | Current user profile | authenticated |

These four are the **only** public endpoints in the application. Everything else denies by
default.

## UI

- `/login` — email + password, error message identical for all failure modes.
- `/register` — email, password, optional display name, with a strength meter (UX only).
- An auth guard on every other route; a `401` interceptor redirects to `/login`.
- The SPA calls `/api/auth/me` on boot to restore session state — it does not store auth state
  in `localStorage`.

## Security considerations

This feature *is* the security boundary. Everything in `../architecture/security-model.md`
applies. Specifically:

- **Session fixation:** the session id must rotate on login. Test it explicitly.
- **User enumeration:** registration, login, and password reset must all be non-committal about
  whether an address exists. This is the legacy app's most concrete mistake.
- **Timing:** always perform a hash comparison on login, even for a nonexistent user.
- **CSRF:** login and logout are state-changing and require a valid token. The token is issued
  before login so the login form can carry it.
- **Credential leakage:** `password_hash` must never appear in a DTO, log line, error, or trace.
- **Brute force:** rate limiting is part of this feature, not a later hardening pass.

## Edge cases

- Registering an email that already exists → generic success shape, no account created.
- Logging in while already logged in → rotate to a fresh session.
- Session expires mid-request → `401`, client redirects and preserves the intended route.
- Password exactly 12 and exactly 128 characters → both accepted.
- Unicode and `+` addressing in emails → accepted; normalize case only, never strip `+` tags.
- Concurrent logins from two devices → both work until the cap evicts the oldest.

## Out of scope

Deliberately not in this slice — do not re-propose without a decision:

- **MFA / TOTP.** Wanted eventually; would likely mean superseding ADR-0004 in favor of an
  external identity provider. Not now.
- **Social / OAuth login.** Same reasoning.
- **Password reset by email.** Needs an email provider first; its own feature doc.
- **Email verification.** Same. Until it exists, an unverified address is not trusted for
  anything security-relevant.
- **Roles and permissions.** Every user is an ordinary user. No admin surface yet.
- **Account deletion.** Its own feature — needs the full data-erasure story from
  `../domain/model.md` invariant 6.

## Open questions

- Do we need email verification before the first real deployment? Probably yes if the app is
  ever publicly reachable.
- Where do we get the breached-password list — bundled k-anonymity dataset, or the HIBP range
  API? The API is a third-party dependency on the login path; bundling is more private.

## Testing notes

Beyond the mandatory set in `../guides/testing-style.md`:

- Session id **changes** across login (fixation).
- Login with a nonexistent email and login with a wrong password produce byte-identical
  responses and comparable latency.
- Logout invalidates server-side: reusing the old cookie afterwards gives `401`.
- `password_hash` appears in **no** response body — assert on the raw JSON, not the DTO type.
- Rate limiter returns `429` after the configured threshold and recovers after backoff.
- Every non-auth endpoint returns `401` when unauthenticated (a test that walks the mapping
  registry, so new endpoints are covered automatically).
