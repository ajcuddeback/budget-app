# Feature: Admin console

- **Status:** In progress — slice 9
- **Owner:** Repository owner
- **Last updated:** 2026-11-22
- **Related:** ADR-0016 (self-hosted, no service we operate), ADR-0018 (two transports),
  ADR-0026 (one household per instance; the owner is the operator),
  ADR-0029 (exactly one instance administrator), ADR-0030 (the runtime cannot change the schema),
  `docs/architecture/security-model.md` (**mandatory** — it specifies most of this)
- **Designs:** **None.** The canvas covers home, transactions, bills, insights, debt plan, chat
  and onboarding; there is no operator screen in it. The roadmap says the designs lead, so this
  doc records the exception rather than hiding it: the screens here are derived from the
  **schema and the ADRs**, not invented, and they follow the visual language the shipped screens
  already establish. If the console is ever designed, the designs win.

## Purpose

Budget Owl is software somebody runs on their own hardware. Everything else in this app is about
a household's money; this is about **the instance** — who may sign in, how they sign in, and what
the operator needs to see to know their server is healthy.

It is small on purpose. Most of what an operator does — backups, upgrades, the database — they do
at the machine, because it is their machine (ADR-0026). What belongs in the app is the handful of
settings that are awkward to change any other way, and the security model is explicit about why:

> A typo in an env var means editing a Compose file and restarting a container; a typo in a form
> is fixed in the browser. Self-hosters are configuring this at 11pm on a machine in a cupboard.

## Prerequisites

Authentication and households (slice 2). Nothing else: the console must work on an instance where
no money has ever been recorded.

## Rules and behaviour

### The administrator is not a household role

ADR-0029. `users.is_instance_admin` governs the **instance**; `OWNER` / `MEMBER` / `VIEWER`
govern **financial data** (ADR-0017). They are different axes and neither implies the other:

- A household may have several `OWNER`s. The instance has exactly one administrator, enforced by
  a unique partial index, because the race an application check loses is real.
- An `OWNER` is **not** an administrator. Being trusted with the household's money is not the
  same as being trusted with the server's login configuration.

Every route here requires the administrator. `OWNER` is refused like anyone else, and the refusal
is a `403` with its own code, because "you are not the administrator" is a different fact from
"this does not exist" and the instance's existence is not a secret from its own members.

### The console holds no financial data

Not one figure. ADR-0026 already establishes that the operator can read the database if they
choose — it is their machine — and that is precisely why this must not become a screen that does
it for them. An operator opening the console to change a login setting should not be shown
somebody's rent, and a `VIEWER` who becomes administrator should gain no new view of the money.

### The OIDC client secret is write-only

It can be set and it can be replaced. It is **never** returned, in any response, to anybody —
including the administrator who typed it. The console reports _whether_ a secret is configured,
and nothing else about it.

It does **not** say when the secret last changed, though this line used to promise that: there is
no `oidc_client_secret_updated_at`, only an `updated_at` for the whole row, which moves when
anything on it moves. Either the column lands or the promise goes; the promise has gone, because a
doc that describes a field nobody built is how the next reader loses an afternoon.

A secret that is read back is a secret that lands in a browser cache, a screenshot, an HTTP log
and a bug report. There is no operational need: an administrator who cannot remember it replaces
it, which is also what they should do if they are unsure whether it leaked.

### The lockout rule is explained, not merely enforced

`ck_instance_settings_password_login_lockout` already refuses to disable password login until an
`OWNER` has completed a real OIDC login. The console's job is to make that legible: it says
**why** the switch is unavailable and what has to happen first, rather than offering a control
that fails.

Email and password remain a permanent capability of the build (security-model.md). The console
can hide that route on an instance that has proved another one works. It cannot remove it.

### There is a recovery path that does not need the browser

The security model requires one and nothing has built it:

> A self-hoster has shell access to their own box, so an administrative command that re-enables
> password login must exist and be documented.

So: a command the operator runs **on the machine**, against the database, that re-enables
password login and reports what it changed. It is the escape hatch for a provider that changed
its endpoints, a certificate that expired, or a container restored from a backup with stale
configuration — the cases where the browser route is exactly what is unavailable.

It is `deploy/restore-password-login.sh`, documented in `deploy/README.md` and under "things that
commonly go wrong" in `userguide/installing.md`, because a recovery path nobody can find at 11pm is
not a recovery path. It shipped in the backend half of this slice with its name appearing nowhere
outside its own file, which is the same as not having it; a security review caught that the doc you
are reading already claimed otherwise.

### Every change is audited

Who changed what, and when. Not the values — a log line carrying an OIDC client secret would
undo the rule above — but the field names and the actor. An instance whose login configuration
changed and cannot say who changed it is one with a worse problem than a bad setting.

## Data model

**No new table.** Everything is already modelled:

- `instance_settings` (V6, hardened in V8) — one row forever, `registration_open`,
  `password_login_enabled`, the `oidc_*` columns, `setup_completed_at`, and the four CHECKs that
  make the invariants structural rather than hopeful.
- `users.is_instance_admin` (V2) with its unique partial index.

If this slice needs a column it does not have, that is a migration and an append-only one
(ADR-0007). It should need none.

## API

| Method  | Path                  | Notes                                                       |
| ------- | --------------------- | ----------------------------------------------------------- |
| `GET`   | `/api/admin/instance` | Version, setup date, who the administrator is, counts       |
| `GET`   | `/api/admin/settings` | Registration, password login, OIDC — **secret never shown** |
| `PATCH` | `/api/admin/settings` | Partial update. Requires `version`                          |
| `GET`   | `/api/admin/users`    | Accounts that can sign in. No financial data                |

All four require the instance administrator. All four are in `AuthorizationIT`'s enumerated list
and in the `VIEWER`/`OWNER` refusal matrix, because an authorization rule that is only in prose is
not a rule.

## UI

`/admin`, reached from the masthead **only when the signed-in person is the administrator** —
hidden rather than shown-and-refused, because an operator menu that is always visible and always
refuses teaches people to ignore refusals.

Three blocks:

1. **This instance** — version, when setup completed, who the administrator is, how many accounts
   exist. The "is my server alright" block.
2. **Signing in** — registration open or closed; password login; OIDC configuration. The lockout
   rule is stated here, beside the switch it governs.
3. **Accounts** — who can sign in, their status and when they joined. Read-only in this slice.

### As built (slice 9, frontend)

`features/admin/` — a lazy route behind `instanceAdminGuard`, a page that fetches and coordinates,
and three presentational components. There are **no designs** (see the header), so the layout is the
one the bills and transactions screens already establish: a `.page-title` and lede, then one `.card`
per block, every colour, space and radius a token.

- **This instance** is a `<dl>` of four facts in an auto-fitting grid — four across on a desktop,
  stacked on a phone. A date with no value reads "Not recorded" rather than rendering blank.
- **Signing in** is one typed reactive form with three `<fieldset>`s, so a screen reader says which
  setting a checkbox belongs to. Each group carries the words it needs:
  - _Registration_ — the consequence sits in the flow at full size, pointed at by the checkbox's
    `aria-describedby`, never a tooltip. Ticking it adds a `role="status"` line, so the moment the
    switch moves is also the moment the consequence is announced.
  - _Email and password_ — **when `passwordLoginLocked` is true there is no switch at all.** The
    state is shown as a fact and the rule beside it, because a control that answers
    `409 password-login-locked` teaches nothing. The switch does appear if password login is
    somehow already off, so turning it back on is never unreachable from here. The `409` is still
    handled: the error is shown and the settings re-read, so the screen stops offering what the
    server now refuses. When the switch _is_ offered and somebody clears it, the form names
    `deploy/restore-password-login.sh` before the save — the browser is no help once the browser is
    the thing you cannot get in through.
  - _Single sign-on_ — issuer, client ID, secret, provisioning, and whether an `OWNER` has proved
    the route works. Clearing the checkbox warns, before anything is sent, that password login
    comes back; submitting it opens a confirmation that says the same thing. Only
    `{oidcEnabled: false}` is sent — the server clears the rest, so describing it would be fiction.
- **The client secret input is empty on every load and on every save.** There is no value to put in
  it: the API never returns one. "A secret is set." / "No secret set." is rendered from
  `oidcClientSecretSet` — never dots, which would claim to be showing something. The field is sent
  only when somebody typed into it, because the server reads a present-but-blank secret as "clear
  it" (`AdminRequests`), and whitespace alone counts as nothing typed.
- **`version` travels on every `PATCH`.** A `409 stale-version` re-reads the server's copy, keeps
  what the person typed, names the settings that moved — **field names only, because one of them is
  a secret** — and stops. They choose "Take the latest" or "Save my changes anyway"; the second
  sends against the version they were shown, and still goes through the single-sign-on confirmation.
- **Accounts** is a read-only `.rows` list: name, email, when they joined, the `Administrator` tag
  on the one account that has it, and the status. No action on any row.
- The masthead link is last in the nav and rendered only for `instanceAdmin`. Every user-visible
  string is in `src/i18n/en.json` under `admin.*`, plus `error.not-instance-admin` and
  `error.password-login-locked` for the code renderer.

## Security considerations

- **Administrator-only, enforced in the service**, not by hiding the link. The nav hides it; the
  API refuses it.
- **The OIDC client secret is never in a response, and never in a log line at `INFO` or
  `DEBUG`.** `AdminDisclosureIT` captures the log across the whole journey, as
  `CredentialDisclosureIT` does — a redacted `toString` is not a logging control.

  The qualifier is load-bearing and the unqualified version was wrong. `oidc_client_secret` is a
  mapped column on a managed entity, so Hibernate's own `EntityPrinter` would print it at
  `org.hibernate.orm.core` DEBUG, and the settings row is read on **every login attempt**. What
  stops that is the level pinned for that logger in `application.yml`, and because the disclosure
  test raises the *root* logger it cannot detect the pin being removed. At `TRACE` the secret is
  bound as a JDBC parameter, which is documented as a deliberate act. It is also stored in
  plaintext and therefore sits in every database backup — acceptable under ADR-0026, where the
  operator owns the box, but not the same claim as "never in a log".

  Nothing structural protects this one the way `PasswordService` detaching the credential protects
  the password hash. That asymmetry is known and not yet closed.
- **No financial data on any route here**, which is also what stops this becoming the one screen
  that ignores household scoping.
- Changing `registration_open` to true opens the instance to anyone who can reach it. The console
  says so in the words a person needs, not as a tooltip.
- The recovery command runs with database credentials the operator already has. It grants nothing
  that shell access did not already grant (ADR-0026) — it only saves them writing the SQL.

## Out of scope

- **Transferring the administrator.** ADR-0029 is explicit: a second person who needs to operate
  the instance does so at the machine. Moving the flag is a database operation with no UI.
- **Suspending or deleting accounts.** What happens to a person's data when their account goes is
  a real decision and not this slice's.
- **Backups, upgrades, logs, metrics.** The operator's machine already does these better than a
  web page would, and ADR-0016 means we cannot assume any service to send them to.

## Known gaps

As of the frontend half of slice 9:

- **The schema does not require a client secret, so SQL can still break the only login route.**
  `ck_instance_settings_oidc_configured` demands a non-blank issuer and client id when OIDC is on,
  and says nothing about the secret. The service refuses a missing one, so the edge holds — but
  V8's own argument is against leaving it there: the database is the half that cannot be bypassed
  by adding a caller. `UPDATE instance_settings SET oidc_client_secret = NULL` is accepted today
  and leaves no working route when password login is hidden. **Not fixed, because the obvious
  constraint forbids something legitimate**: a public OIDC client using PKCE has no secret at all.
  The invariant wants to be "a secret, or PKCE declared", and migrations here are append-only, so
  it is worth deciding before writing rather than after.
- **OIDC login is not implemented**, which is why the lockout can only be reached in tests by
  setting `oidc_owner_login_at` with SQL. `InstanceSettings.recordOwnerOidcLoginAt` has no caller
  in `src/main`. Fail-safe today, with two consequences to pick up when the login path lands:
  `/api/setup/status` already advertises `oidcEnabled` **publicly** for a route that does not
  exist, and writing `oidcOwnerLoginAt` through the now-versioned entity will contend with admin
  `PATCH`es — turning a *user login* into a `409`. That write wants its own conditional `UPDATE`,
  the way `claimFirstUserSetup` does.
- **`oidcIssuerUri` is 500 characters of arbitrary text at the API edge.** No scheme or URI check,
  and the three `oidc_*` columns are unbounded `text` while V8 bounded every other
  operator-supplied text column. Latent rather than live: the value is echoed to the console and
  will eventually be fetched for discovery (admin-triggered SSRF) and possibly rendered as a link.
  The Angular form validates it; the API does not. Worth a `@Pattern` and matching length CHECKs
  when the OIDC client lands.
- **A client secret can be replaced but not removed** from the console. Clearing one is a request
  the API accepts (a present-and-blank `oidcClientSecret`) and the form has no way to express,
  because an empty input has to mean "keep it" for the field to be safe to render at all. Clearing
  it while the provider is on would be refused anyway; the missing case is "turn the provider off
  and forget its secret", which an operator does by replacing it with a throwaway or at the
  database.
- **No administrator transfer**, per ADR-0029 and the out-of-scope list. The console is now the
  place it would live.
- **`GET /api/admin/users` is not paginated** — it answers `{content, total}` rather than the page
  envelope `api-style.md` requires of every collection. It is fine at household scale and the
  client renders it whole, but it is the one collection in the API that would be a breaking change
  to page later.
- **No designs, and no viewport artboards.** Both layouts are CSS judgements made against the
  shipped screens' language rather than against a design. If the console is ever drawn, expect the
  three cards to change.
- **Accounts are read-only**, so a `DISABLED` account is visible and not actionable. Deliberate
  (see out of scope), and it does mean the status column currently explains a state no screen can
  reach.

## Testing notes

- `AdminIT` — every route refuses a non-administrator including an `OWNER`; the secret never
  appears in any response; the lockout rule is refused with its own code and the console's
  explanation matches it; a `PATCH` with a stale `version` is a `409`.
- `AdminDisclosureIT` — the client secret reaches no log line at `INFO` or `DEBUG`, proven by
  leaking one and watching the test fail.
- A constraint test is **not** needed for the lockout rule: `SchemaConstraintsIT` already covers
  `ck_instance_settings_password_login_lockout`. Check before writing a second one.
- `admin.page.spec.ts` — the secret input is empty on load and after a save and is sent only once
  typed; "A secret is set." renders from the flag with no secret-shaped value anywhere in the DOM;
  no password-login switch exists while the lockout applies; the registration consequence is in the
  page and wired to the checkbox; turning the provider off is confirmed before anything is sent; a
  `409 stale-version` keeps the form and names what moved; a `409 password-login-locked` explains
  itself and re-reads. One assertion is a standing guard rather than a behaviour: the page text
  matches no currency symbol, so the day somebody adds a figure to the operator's screen, a test
  says so.
- `admin-data.spec.ts` — the patch builder, `differingSettings`, the issuer validator, and the
  service (the secret goes in a `PATCH` body and never into a URL or a query parameter).
- `app.spec.ts` and `auth.guards.spec.ts` — the masthead link is absent for a household `OWNER` who
  is not the administrator, and the guard sends them home rather than to sign-in.
