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
including the administrator who typed it. The console reports *whether* a secret is configured
and when it last changed, never what it is.

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

It is documented in the user guide and in `deploy/`, because a recovery path nobody can find at
11pm is not a recovery path.

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

| Method  | Path                     | Notes                                                      |
| ------- | ------------------------ | ---------------------------------------------------------- |
| `GET`   | `/api/admin/instance`    | Version, setup date, who the administrator is, counts      |
| `GET`   | `/api/admin/settings`    | Registration, password login, OIDC — **secret never shown** |
| `PATCH` | `/api/admin/settings`    | Partial update. Requires `version`                          |
| `GET`   | `/api/admin/users`       | Accounts that can sign in. No financial data                |

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

## Security considerations

- **Administrator-only, enforced in the service**, not by hiding the link. The nav hides it; the
  API refuses it.
- **The OIDC client secret is never in a response, a log line, or a `toString`.** A disclosure
  test captures the log at `INFO` and `DEBUG` across the whole journey, as `CredentialDisclosureIT`
  does — a redacted `toString` is not a logging control.
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

_To be filled in as the slice lands._

## Testing notes

- `AdminIT` — every route refuses a non-administrator including an `OWNER`; the secret never
  appears in any response; the lockout rule is refused with its own code and the console's
  explanation matches it; a `PATCH` with a stale `version` is a `409`.
- `AdminDisclosureIT` — the client secret reaches no log line at `INFO` or `DEBUG`, proven by
  leaking one and watching the test fail.
- A constraint test is **not** needed for the lockout rule: `SchemaConstraintsIT` already covers
  `ck_instance_settings_password_login_lockout`. Check before writing a second one.
