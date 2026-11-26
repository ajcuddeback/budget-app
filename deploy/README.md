# `deploy/`

Things the Compose stack mounts, and the commands an operator runs on the machine itself.

Everything here assumes shell access to the host. That is not a privilege escalation: a
self-hoster owns the box and the database (ADR-0026), and these scripts only save them writing the
SQL correctly at a bad moment.

## `db-init/`

Mounted into the PostgreSQL container on first start. It creates the non-owning role the
application connects as, so the application cannot alter its own schema at runtime (ADR-0030).
Migrations run as the owning role; the app does not.

## Behind a reverse proxy: which address the rate limits see

Every public route (sign-in, setup, invitations, the phone app's `GET /api/meta`) is rate-limited
per **client address**, and the API takes that address from the socket, never from a forwarded
header a caller could make up (`ClientAddress`). Behind a reverse proxy, the socket's address is
the proxy's, so every phone and browser shares one limit: a busy household can see *"The server is
busy"* in the app and `429`s on the web. If, and only if, the proxy is the **only** way to reach
the API and it **replaces** `X-Forwarded-For` with the address it saw (nginx:
`proxy_set_header X-Forwarded-For $remote_addr;`), set `SERVER_FORWARD_HEADERS_STRATEGY=native` in
the API's environment so Spring Boot's `server.forward-headers-strategy` takes the real client
address from the proxy. Never set it when the API port is reachable directly, or when the proxy
*appends* to the header (`$proxy_add_x_forwarded_for`, which is what `frontend/nginx.conf` does
today): the header then keeps whatever the caller sent, and Tomcat skips private-range entries
as trusted proxies, so a caller on the LAN could choose the address the limit sees and step around
it.

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
