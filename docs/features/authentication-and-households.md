# Feature: Authentication & households

- **Status:** Planned — this is slice 2
- **Owner:** Repository owner
- **Last updated:** 2026-09-06
- **Related:** ADR-0016 (self-hosted), ADR-0017 (households), ADR-0018 (auth),
  `../architecture/security-model.md` (**read it before implementing any of this**)

> Renamed from `accounts-and-auth.md`. "Account" in this project means a container where money
> sits (`../memory/glossary.md`), and slice 3 is called Accounts. A login is a **user**.

## Purpose

Someone installs Budget Owl on their own server, creates the first account, and that account owns
a new household. They invite their partner. Both sign in — on the web, on their phones — and see
the same money.

Nothing else in the product works until this does, and every authorization decision in every
later slice rests on it.

## Prerequisites

A running instance (slice 1). No email server, no identity provider, and no internet access can
be assumed — see *The self-hosted realities* below, which is where most of the design pressure
in this feature comes from.

## The self-hosted realities

Three constraints that a hosted SaaS would not have, and that shape most of what follows:

**1. There is no SMTP server.** A self-hoster may have one; most will not, and requiring one to
finish setup would be a bad first experience. So:
- **Invitations are links**, generated in the app and shared however the owner likes — text,
  chat, reading it aloud. Email delivery is an *optional enhancement* if SMTP is configured.
- **Password reset is not an email flow.** An `OWNER` resets another member's password in the
  app, and a documented CLI command resets any password from the host shell.

**2. The instance may be reachable from the internet.** Many self-hosters expose their server.
Open registration would mean anyone who finds it can create an account on someone's private
finances. So **registration is closed after the first user** — see below.

**3. Nobody is coming to help.** There is no support desk. Every irreversible action needs a
recovery path that works from the host shell, because that is all the user has.

## Rules and behaviour

### First run

- A **fresh instance has no users**. The first visit shows a create-first-account screen, not a
  login.
- The first user created becomes an **instance administrator** and the **`OWNER`** of a new
  household created at the same time. There is no such thing as a user without a household.
- **After the first user, self-service registration is closed.** New people join by invitation.
  An instance administrator may re-open registration explicitly; it is off by default.
- Setup must complete with no configuration beyond a database password (ADR-0016).

### Sign in

- Email and password. **Always available, permanently** (ADR-0018) — it cannot be removed from
  the build, only hidden on an instance where OIDC is proven working.
- **Identical response for an unknown email and a wrong password**: same status, same body, and
  comparable latency. Always run a password comparison, against a dummy hash when no user was
  found. The legacy app's distinct messages were a user-enumeration oracle; do not reproduce it.
- **Web** receives a session; the session id rotates on login. **Mobile** receives an opaque
  bearer token. Same credentials, same user store, same authorization (ADR-0018).
- Rate limited per IP **and** per email, with exponential backoff. Never a permanent lock — that
  is a denial of service against the real user.
- Authentication events — success, failure, logout, lockout, token issue, token revoke — are
  logged with user, source IP and outcome. This is the audit trail.

### Sessions and devices

- **Web session**: cookie `HttpOnly` / `Secure` / `SameSite=Lax`, CSRF token required on every
  state-changing request. Idle timeout 30 minutes, absolute 12 hours, both server-enforced.
- **Mobile token**: opaque, stored server-side, revocable. Records a device label, created-at and
  last-used-at. Sliding expiry with an absolute cap.
- Every user has a **logged-in devices** view listing their own sessions and tokens, each
  individually revocable. Revoking is immediate — that is why the tokens are opaque rather than
  self-contained.
- Logout invalidates server-side and is idempotent: logging out when not logged in also succeeds,
  revealing nothing.

### Households and membership

- A user belongs to **one or more** households; every session has a **current household**, which
  the user can switch. All financial APIs resolve scope from it (ADR-0017).
- Roles: **`OWNER`** (everything, including invites, role changes, removal, deletion),
  **`MEMBER`** (read and write financial data), **`VIEWER`** (read only).
- **A household always has at least one `OWNER`.** The last owner cannot be removed, demoted, or
  leave. The only way out is to transfer ownership first, or delete the household.
- **Nobody may change their own role**, and only an `OWNER` may change anyone's.

### Invitations

- An `OWNER` creates an invitation for an email address and a role. The app returns a **link
  containing a single-use, high-entropy token**, which the owner shares however they like.
- Invitations **expire** (default 7 days), are **single-use**, and are revocable before use.
- Accepting: an existing user joins the household; a new person creates their account through the
  invitation. **This is the only way to register once the first user exists.**
- The invited email is a label, not an authorization: possession of the link is what grants
  access, so the link is a credential and must be treated as one — never logged, never in a URL
  we record.

### Removing and leaving

- An `OWNER` removes a member; any member may leave (except the last owner).
- Removal **revokes access only**. It never deletes household financial data — that data belongs
  to the household, not the person (ADR-0017).
- Rows the departing member authored keep their attribution, or are anonymised if they delete
  their user entirely. They are never silently reassigned to someone else.
- Removal immediately invalidates that user's sessions and tokens **for that household**.

### Optional OIDC

Off by default. Configured **at runtime by an instance administrator in the app**, not by
environment variable (`../architecture/security-model.md`). It adds a login route and changes
nothing downstream.

- An OIDC login may provision a *user* if the administrator allows it, but **never grants
  household membership**. A newly provisioned user has no household and no financial data until
  invited. Otherwise everyone in the provider's directory lands inside someone's finances.
- Password login may be hidden on an instance **only after at least one `OWNER` has completed a
  successful OIDC login**. The setting is refused otherwise, and a documented CLI command
  re-enables it.

## Data model

```
users                 id, email (citext, unique), display_name, password_hash,
                      status (ACTIVE|DISABLED), is_instance_admin, created_at, updated_at
households            id, name, base_currency, created_at, updated_at
household_members     id, household_id, user_id, role (OWNER|MEMBER|VIEWER), joined_at
                      unique (household_id, user_id)
household_invitations id, household_id, email, role, token_hash, expires_at,
                      accepted_at, revoked_at, created_by
auth_tokens           id, user_id, token_hash, device_label, created_at, last_used_at,
                      expires_at, revoked_at
instance_settings     registration_open, oidc_* , password_login_enabled
```

Plus Spring Session's tables for the web transport.

- `password_hash` and `token_hash` are **never** selected into a DTO. Use projections that cannot
  carry them rather than relying on annotations to hide them.
- Invitation and auth tokens are stored **hashed**, never in plaintext — a database dump must not
  yield working credentials.
- `household_members` is the membership graph, not financial data, so it carries `user_id` rather
  than the `household_id`-only rule that applies to financial tables.

Migrations: `V1__users.sql`, `V2__households.sql`, `V3__invitations.sql`, `V4__auth_tokens.sql`,
`V5__instance_settings.sql`, `V6__spring_session.sql`.

## API

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET` | `/api/setup/status` | Is this a fresh instance? | public |
| `POST` | `/api/setup/first-user` | Create the first user + household | public, only while no user exists |
| `POST` | `/api/auth/login` | Start a session (web) | public, rate-limited |
| `POST` | `/api/auth/token` | Issue a bearer token (mobile) | public, rate-limited |
| `POST` | `/api/auth/logout` | End the current session/token | authenticated |
| `GET` | `/api/auth/me` | Current user + households + current role | authenticated |
| `GET` | `/api/auth/devices` | This user's sessions and tokens | authenticated |
| `DELETE` | `/api/auth/devices/{id}` | Revoke one | authenticated, own only |
| `POST` | `/api/auth/password` | Change own password | authenticated |
| `GET` | `/api/households/current` | Current household | authenticated, member |
| `PUT` | `/api/households/current` | Rename, set base currency | `OWNER` |
| `GET` | `/api/households/current/members` | List members | authenticated, member |
| `POST` | `/api/households/current/invitations` | Create an invitation link | `OWNER` |
| `DELETE` | `/api/households/current/invitations/{id}` | Revoke | `OWNER` |
| `POST` | `/api/invitations/{token}/accept` | Join (existing or new user) | public + token |
| `PATCH` | `/api/households/current/members/{id}` | Change role | `OWNER`, not self |
| `DELETE` | `/api/households/current/members/{id}` | Remove, or leave | `OWNER`, or self |

The only public routes in the entire application are the setup pair, login, token issue, and
invitation acceptance. Everything else denies by default (ADR-0016 non-negotiable 2).

## UI

**Web:** first-run setup · login · household switcher in the header · household settings with
members and roles · invitation dialog that produces a copyable link · logged-in devices.

**Mobile:** a server URL field before anything else — the instance is the user's, so this is
step one and must handle LAN hostnames, self-signed certificates and non-standard ports
(`../guides/flutter-style.md`). Then login, household switcher, devices.

## Security considerations

This feature *is* the security boundary; everything in `../architecture/security-model.md`
applies. The parts specific to it:

- **Enumeration:** login, invitation acceptance, and password reset must all be non-committal
  about whether an address exists.
- **Timing:** always hash-compare on login, even for an unknown user.
- **Session fixation:** rotate the session id on login. Test it explicitly.
- **Invitation tokens are credentials:** high entropy, hashed at rest, single-use, expiring,
  never logged, never in a `Referer`-visible URL we control.
- **Privilege escalation:** a `MEMBER` must not be able to change any role, including their own;
  a `VIEWER` must not be able to write anything. Both need explicit tests on every endpoint.
- **Cross-household leakage** is the highest-severity bug available here. The household comes
  from verified membership, never from the request.
- **Lockout:** the last-owner rule and the OIDC safeguard both exist to stop a user locking
  themselves out of their own financial records with nobody to call.
- **Credential leakage:** no hash, token, or invitation token in any DTO, log line, error or
  trace. Assert on raw JSON in tests, not on DTO types.

## Edge cases

- Invitation for an email that is already a member → refused, without confirming the address.
- Invitation accepted by a user who is signed in as somebody else → refused; sign out first.
- Expired, revoked, or already-used invitation → identical generic failure for all three.
- Last owner tries to leave, be removed, or demote themselves → refused with a clear reason
  (this is the one place a specific message is right; it is not an enumeration surface).
- A user in three households switching between them mid-session.
- A member removed while they have an active mobile token → next request fails cleanly, app
  routes to household selection rather than crashing.
- OIDC user with no household membership → signs in successfully and sees an empty state
  explaining they need an invitation. Not an error.
- Registration re-opened, then closed, with an invitation outstanding → the invitation still works.
- Two owners removing each other simultaneously → the last-owner rule must hold under
  concurrency, which means enforcing it in a transaction, not in application logic that races.

## Out of scope

Deliberately not in this slice — do not re-propose without a decision:

- **MFA / TOTP.** Wanted eventually. Worth revisiting once the product is real; it is a new ADR.
- **Social login** (Google, Apple). OIDC covers the self-hoster case; consumer social login is a
  different product decision.
- **Email delivery of invitations.** The link is the mechanism; SMTP is a later enhancement.
- **Per-category or per-account permissions.** Three household-wide roles only. Finer-grained
  permission systems are a large feature and usually unwanted.
- **Household deletion.** Needs the full data-erasure story from `../domain/model.md` invariant 6.
- **Transferring an instance to a new administrator.**
- **Audit log UI.** Events are logged; a screen for them is later.

## Open questions

- Should a user be able to delete their own account, and what happens to households they own?
  Probably: refused while they are the last owner of any household. Needs deciding before this
  ships, since it interacts with the last-owner rule.
- Base currency is per household — what happens if a member wants a different display currency?
  Deferred to the reporting slice.

## Testing notes

Beyond the mandatory set in `../guides/testing-style.md`:

- Session id **changes** across login (fixation).
- Unknown email and wrong password produce byte-identical responses and comparable latency.
- Logout invalidates server-side: reusing the old cookie or token afterwards gives `401`.
- **Every endpoint behaves identically under session and bearer transports.** An endpoint that
  authorizes correctly for web but not mobile is a real and easy bug.
- **`VIEWER` gets `403` on every write endpoint**; `MEMBER` gets `403` on every `OWNER` endpoint.
- Cross-household: a member of household A gets `404` on household B's resources.
- The last-owner rule holds under **concurrent** removal attempts.
- An invitation is single-use: the second acceptance fails.
- Password login cannot be disabled before an `OWNER` has completed an OIDC login.
- No `password_hash`, `token_hash` or invitation token appears in any response — asserted against
  the raw JSON, not the DTO type.
- Registration is closed after the first user: `POST /api/setup/first-user` fails on a
  non-empty instance.
