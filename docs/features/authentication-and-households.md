# Feature: Authentication & households

- **Status:** In progress — this is slice 2
- **Owner:** Repository owner
- **Last updated:** 2026-09-26
- **Related:** ADR-0016 (self-hosted), ADR-0017 (households), ADR-0018 (auth),
  ADR-0026 (one household per instance; owner is operator),
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

- **An instance holds exactly one household** (ADR-0026). Every user who has a membership has it
  in that household; there is no switcher, and no way to create a second. All financial APIs
  resolve scope from the caller's verified membership (ADR-0017), which is why the endpoints are
  named `/current` — the shape survives if a managed deployment ever needs more.
- A user may exist **without** a membership (an OIDC-provisioned user before invitation). They
  sign in successfully and see an empty state, not an error.
- Roles: **`OWNER`** (everything, including invites, role changes, removal, deletion),
  **`MEMBER`** (read and write financial data), **`VIEWER`** (read only).
- **A household always has at least one `OWNER`.** The last owner cannot be removed, demoted, or
  leave. The only way out is to transfer ownership first, or delete the household.
- **Nobody may change their own role**, and only an `OWNER` may change anyone's.


### Joining tells you what you are joining

**Before an invited person's account exists**, the acceptance screen states plainly that the
person running this instance can see everything they record: every transaction, every balance,
every note. Not in a linked policy, not in small print — in the flow, in those words, above the
button.

This is a security control, not copy (ADR-0026). One household per instance and no encryption
against the operator is an honest model *only if the person joining knows it*. Without the
disclosure it is the same weak model with better documentation, and someone shares their finances
with a housemate's server believing otherwise. A change that weakens or buries this wording is a
security change and needs review as one.

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
household_members     id, household_id, user_id, role (OWNER|MEMBER|VIEWER), joined_at,
                      display_currency (nullable — falls back to household base, ADR-0022),
                      locale (nullable — falls back to platform locale, ADR-0023)
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

Migrations: `V2__users.sql`, `V3__households.sql`, `V4__invitations.sql`, `V5__auth_tokens.sql`,
`V6__instance_settings.sql`, `V7__spring_session.sql`. Numbered from 2 because `V1__baseline.sql`
shipped with slice 1 and is frozen (ADR-0007).

Three invariants are enforced by the **schema**, not by a service, because a service cannot
enforce them without racing:

- **The last-owner rule.** `households.owner_count` is maintained by a trigger on
  `household_members`, and a `DEFERRABLE INITIALLY DEFERRED` constraint trigger re-reads it at
  `COMMIT`. Two concurrent removals must update the same `households` row, so the second blocks and
  then recomputes against the committed value; exactly one succeeds. Deferred rather than a `CHECK`
  because a household is legitimately created with no owner and given one a statement later in the
  same transaction — and because PostgreSQL cannot defer a `CHECK`. Failure arrives at `COMMIT` as
  SQLSTATE `23514` with constraint `ck_households_at_least_one_owner`.
- **One household per instance** (ADR-0026): `uq_households_singleton`, a unique index on a
  constant expression.
- **One instance administrator**: `uq_users_single_instance_admin`, the same trick restricted to
  admin rows. With `instance_settings.setup_completed_at`, which the setup transaction claims with
  a conditional `UPDATE`, this is what stops two simultaneous callers of
  `/api/setup/first-user` both winning.

Invitation and bearer tokens are stored as a lowercase-hex SHA-256 and the columns are constrained
to that shape, so a plaintext credential cannot be written at all. `users.password_hash` is
constrained to an encoded form for the same reason.

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
| `PATCH` | `/api/households/current/members/me` | Own display currency and locale | authenticated, self only |
| `DELETE` | `/api/auth/me` | Delete own user | authenticated; refused while owning a household |
| `DELETE` | `/api/households/current/members/{id}` | Remove, or leave | `OWNER`, or self |

The only public routes in the entire application are the setup pair, login, token issue, and
invitation acceptance. Everything else denies by default (ADR-0016 non-negotiable 2).

## UI

**Web:** first-run setup · login · household settings with members and roles · invitation dialog
that produces a copyable link · logged-in devices. **No household switcher** — there is one
household (ADR-0026).

**Mobile:** a server URL field before anything else — the instance is the user's, so this is
step one and must handle LAN hostnames, self-signed certificates and non-standard ports
(`../guides/flutter-style.md`). Then login and devices.

## Threat model

Worked through before implementation (`/threat-model`, 2026-09-26). This feature *is* the security
boundary — everything in `../architecture/security-model.md` applies; below is what is specific to
it.

### Assets

| Asset | Why it is worth taking |
|---|---|
| **Password hashes** | Offline cracking yields credentials people reuse elsewhere |
| **Session cookies / bearer tokens** | Direct impersonation, no cracking needed |
| **Invitation tokens** | Possession *is* the authorization — a link is a credential |
| **The household's financial record** | The reason the product exists. Auth is the only gate in front of it |
| **Member email addresses** | PII, and a target list for whoever finds the instance |
| **Instance-admin capability** | Reaches logs, backups, exports — i.e. everyone's data (ADR-0026) |

### Actors

1. **An unauthenticated stranger who found the instance.** The primary adversary: a self-hosted
   box may be internet-facing with no WAF, no rate-limiting proxy, and nobody watching.
2. **An invited `MEMBER` or `VIEWER`** trying to act beyond their role.
3. **Someone holding a leaked invitation link** — forwarded, screenshotted, in a chat backup.
4. **A hostile page in a member's browser** (CSRF; XSS if we ever allow injection).
5. **Someone with a stolen cookie or token**, from a shared machine or a backup.
6. *Not* an adversary: **the instance operator.** See Accepted risks.

### Attacks considered

Each written as a finishable sentence. Attacks that could not be finished were dropped, and are
listed as ruled out — that is what stops this analysis being redone.

**Live, and mitigated:**

- *An attacker POSTs `/api/auth/login` with a list of addresses and reads the response to learn
  which exist.* → identical status, body and comparable latency for unknown-email and
  wrong-password; always hash-compare, against a dummy hash when no user was found.
- *An attacker finds an internet-facing fresh-looking instance and POSTs `/api/setup/first-user`
  to become its administrator.* → the endpoint is refused the moment any user exists, checked in
  the same transaction as the insert so two simultaneous callers cannot both win.
- *An attacker brute-forces one account, or sprays one password across many accounts.* → rate
  limiting per IP **and** per email with exponential backoff; never a permanent lock, which
  would be a DoS against the real user.
- *A `VIEWER` sends `PATCH /api/households/current/members/{id}` to promote themselves.* → role
  checked in the service layer; nobody may change their own role at all, and only an `OWNER` may
  change anyone's.
- *A `MEMBER` sends `POST /api/households/current/invitations` to add an accomplice.* → owner-only,
  enforced in the service, with a `MEMBER`-gets-`403` test.
- *An attacker replays a used or expired invitation link.* → single-use and expiring, both
  enforced in the accept transaction; expired, revoked and already-used all return an identical
  generic failure so the link's history is not disclosed.
- *An attacker brute-forces the invitation token space.* → high-entropy token, hashed at rest,
  and acceptance is rate-limited like login.
- *A hostile page makes a member's browser POST an invitation or a role change.* → session
  transport requires a CSRF token on every state-changing request; `SameSite=Lax` is defence in
  depth, not the control.
- *An attacker who has stolen a cookie keeps using it after the member logs out.* → sessions and
  tokens are server-side and revocable; logout invalidates server-side, and revocation is
  immediate. This is why bearer tokens are opaque rather than self-contained.
- *An attacker fixes a session id before login and reuses it afterwards.* → session id rotates on
  login, with an explicit test.
- *A removed member keeps using a mobile token.* → removal revokes that user's sessions and
  tokens for the household immediately.
- *Two owners remove each other simultaneously, leaving the household with no administrator.* →
  the last-owner rule is enforced as a database constraint inside the transaction, not as
  application logic that races.
- *A user deletes their own account while owning the household, stranding its data.* → refused,
  with a specific reason (one of the few places a specific message is correct).
- *An attacker reads a password hash or token out of an API response.* → hashes are never
  selected into a DTO; projections that cannot carry them, rather than annotations that must be
  remembered. Tests assert on raw JSON, not on DTO types.
- *An attacker harvests credentials from logs or a bug report.* → no hash, token, invitation token
  or `Authorization` header value is ever logged. Authentication events log user, source IP and
  outcome only.
- *An OIDC-provisioned user from the provider's whole directory lands inside the household.* →
  OIDC may provision a *user*, never a membership. No membership means no financial data.
- *An administrator disables password login before OIDC works and locks everyone out.* → refused
  until at least one `OWNER` has completed a successful OIDC login; a CLI command re-enables it.
- *An unauthenticated caller enumerates members via `GET /api/households/current/members`.* →
  authenticated and member-only; the only public routes in the entire application are the setup
  pair, login, token issue, and invitation acceptance.

**Ruled out, with reasons:**

- *Cross-household data leakage.* There is one household per instance (ADR-0026), so there is no
  second household to leak to. **This removed what used to be the highest-value test in the
  codebase**, and the risk did not disappear with it — it moved onto role enforcement and
  authentication, which is why those carry explicit tests on every endpoint below.
- *Tampering with a `householdId` in a request body.* The household is never read from the
  request; it is resolved from verified membership. The parameter does not exist to tamper with.
- *Timing attacks on the token comparison.* Tokens are looked up by hash, so the comparison is a
  database index lookup, not a byte loop over a secret.
- *Privilege escalation via the invited email address.* The email on an invitation is a label,
  not an authorization — possession of the link grants access. Which is exactly why the link is
  treated as a credential.

### Controls, and where each is enforced

| Control | Layer |
|---|---|
| Deny by default; four public routes, explicitly listed | `SecurityConfig` |
| Unauthenticated → `401` (not `403`) | `HttpStatusEntryPoint`, already in place from slice 1 |
| Role checks (`OWNER` / `MEMBER` / `VIEWER`) | service layer, never the controller |
| Household resolved from verified membership | service layer |
| Last-owner rule | **database constraint**, inside the transaction |
| First-user-only setup | database check in the same transaction as the insert |
| Password hashing | Spring Security `PasswordEncoder`, adaptive (argon2/bcrypt) |
| Constant-response login | service layer: dummy-hash compare when no user found |
| Rate limiting per IP and per email | filter in front of the auth endpoints |
| CSRF token on state-changing session requests | `SecurityConfig`, session transport only |
| Session id rotation on login | `SecurityConfig` |
| Hashes unreturnable | projections in the persistence layer, not DTO annotations |
| Credentials never logged | logging config + an explicit test |
| Invitation single-use and expiring | accept transaction |
| Join-time disclosure (ADR-0026) | the acceptance UI, above the button |

### Tests proving each control

Every one of these must fail if its control is removed. That is the whole point of listing them.

1. `401` unauthenticated on **every** non-public endpoint, enumerated — not a sample.
2. `VIEWER` gets `403` on every write; `MEMBER` gets `403` on every owner-only endpoint.
3. Nobody can change their own role, including an `OWNER`.
4. Unknown email and wrong password return the same status, same body, and comparable latency.
5. `/api/setup/first-user` is refused once a user exists — including two concurrent callers.
6. Last-owner rule holds with two concurrent transactions; exactly one succeeds.
7. Session id changes across login.
8. Missing or wrong CSRF token → `403` on the session transport.
9. The same endpoint behaves identically under session and bearer transports (ADR-0018).
10. Invitation: expired, revoked and already-used all produce an identical response.
11. Invitation accepted twice → second attempt fails.
12. **Raw JSON assertions** that no response body contains `password_hash`, `token_hash`, or any
    token value — asserted on the serialized string, not on the DTO type.
13. Log output contains no token, hash, or `Authorization` value after a full login/logout cycle.
14. Revoked token and logged-out session are rejected on the very next request.
15. A signed-in user with no membership gets `403` from a household endpoint, not a crash.

### Accepted risks

- **The instance operator can read everything.** Deliberate (ADR-0026): encrypting against the
  person who holds the database, the volume and the backups would be theatre. Mitigated by
  *disclosure* rather than by cryptography — the acceptance screen says so in plain words before
  an invited person's account exists. That disclosure is therefore a security control, and
  weakening or burying its wording is a security change.
- **An invitation link in a chat backup is a live credential** until it expires or is used. Bounded
  by single-use and a 7-day default expiry, and revocable. Not eliminated: any shareable-link
  invitation has this property, and SMTP cannot be assumed (ADR-0016).
- **No MFA in this slice.** Out of scope and recorded as such. It is the most valuable future
  addition to this feature, and it needs its own ADR.
- **Rate limiting is per-instance and in-memory.** Adequate for one household on one box;
  it would not survive a multi-instance deployment, which this product does not have.

## Edge cases

- Invitation for an email that is already a member → refused, without confirming the address.
- Invitation accepted by a user who is signed in as somebody else → refused; sign out first.
- Expired, revoked, or already-used invitation → identical generic failure for all three.
- Last owner tries to leave, be removed, or demote themselves → refused with a clear reason
  (this is the one place a specific message is right; it is not an enumeration surface).
- A signed-in user with **no** membership (OIDC-provisioned, not yet invited) calling a
  financial endpoint → `403`, not a crash and not an empty success.
- A member removed while they have an active mobile token → next request fails cleanly, app
  routes to a signed-out state rather than crashing.
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

## Resolved decisions

**A user cannot delete their own account while they own a household.** The request is refused with
a clear reason, and the way out is to transfer ownership or delete the household first. This is
the last-owner rule seen from the other side: allowing it would leave a household with financial
data and no administrator, which is the "weird behaviour" that has no good recovery. Deleting a
household is out of scope for this slice, so in practice self-deletion arrives with it.

**A member may set their own display currency.** It is a per-member preference on
`household_members`, distinct from the household's base currency (ADR-0022). Amounts are always
stored in the account's own currency and converted only for display — so this setting changes
what a member *sees*, never what is recorded. Members of one household may each see different
currencies over identical underlying data.

**A member may set their own language and locale**, independently of currency (ADR-0023). One
household, two people, two languages is a normal case rather than an edge one.

## Open questions

None blocking. Household deletion — and therefore self-deletion — is scoped to a later slice.

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
