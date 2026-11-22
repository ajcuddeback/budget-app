# `deploy/`

Things the Compose stack mounts, and the commands an operator runs on the machine itself.

Everything here assumes shell access to the host. That is not a privilege escalation: a
self-hoster owns the box and the database (ADR-0026), and these scripts only save them writing the
SQL correctly at a bad moment.

## `db-init/`

Mounted into the PostgreSQL container on first start. It creates the non-owning role the
application connects as, so the application cannot alter its own schema at runtime (ADR-0030).
Migrations run as the owning role; the app does not.

## `restore-password-login.sh`

**Use this when nobody can sign in.**

An instance administrator may hide the email-and-password login route once their identity
provider is working. If that provider then breaks — a certificate expires, an endpoint moves, a
container comes back from a backup with stale configuration — the browser route back in is the one
thing that is gone. `docs/architecture/security-model.md` requires a way back that does not need a
browser, and this is it.

```bash
deploy/restore-password-login.sh --dry-run   # show what it would change
deploy/restore-password-login.sh             # turn password login back on
```

It turns **one boolean** back on. It does not touch passwords, accounts, or any financial data,
and it deliberately leaves the OIDC configuration alone — you probably want your provider back
once it is fixed, and silently tearing it down would turn a recovery into a second outage. Sign in
with your email and password, fix the provider, and hide password login again from **Admin** in
the app.

It reads the database through `docker compose exec`, so you need no `psql` installed and no
password on a command line where shell history would keep it. If your stack names the database
service something other than `db`, or the database or role something other than `budgetowl`, set
`DB_SERVICE`, `DB_NAME` or `DB_USER`.

If password login is already enabled it says so and changes nothing, which makes it safe to run
when you are not yet sure that is the problem.

**The database will not let you reach a state with no login route at all** —
`ck_instance_settings_password_login_lockout` refuses it, and
`SchemaConstraintsIT.acceptsTheRecoveryUpdateThatDeployRestorePasswordLoginRuns` proves this
script's own statement is one the constraint accepts. That test exists because nobody runs a
recovery path until they need it, and that is the worst moment to find out it was wrong.
