# `deploy/`

Things the Compose stack mounts, and the commands an operator runs on the machine itself.

Everything here assumes shell access to the host. That is not a privilege escalation: a
self-hoster owns the box and the database (ADR-0026), and these scripts only save them writing the
SQL correctly at a bad moment.

## `db-init/`

Mounted into the PostgreSQL container on first start. It creates the non-owning role the
application connects as, so the application cannot alter its own schema at runtime (ADR-0030).
Migrations run as the owning role; the app does not.

## `backup.sh`, `restore.sh`, `upgrade.sh`, `test-restore.sh`

Backups, restores and upgrades. The operator's steps are in `userguide/installing.md` (sections
5 and 6), and the rules and the threat model are in `docs/features/deployment.md`.

```bash
deploy/backup.sh --out /mnt/backups/budgetowl [--age-recipient age1...] [--keep 14 --keep-weekly 8 --keep-monthly 12]
deploy/backup.sh --print-signing-key           # this server's allowed_signers line
deploy/restore.sh BACKUP [--replace] [--signers FILE] [--age-identity KEY]
deploy/upgrade.sh [--backup-dir DIR] [--ref TAG]
deploy/test-restore.sh BACKUP                  # into a separate copy on :8481; --clean removes it
```

- **No password anywhere.** All of them run the database tools **inside the `db` container** over
  its local socket (`lib.sh`), so no password is read, passed or printed.
- **Shared settings.** All of them read `/etc/budgetowl/backup.env`, so every backup is encrypted
  and signed the same way.
- **Signed backups.** Every backup is signed with a key only this server holds
  (`ssh-keygen -Y`). A restore runs the backup's SQL as the database superuser, so `restore.sh`
  checks that signature before it reads anything in the file. It refuses an unsigned backup, or
  one signed by any key it was not told to trust.
- **Then the other checks**, in order:
  1. the checksum;
  2. refuse a database with data in it, unless `--replace` is given;
  3. restore into a scratch database and check the result;
  4. take an encrypted safety backup;
  5. swap the two databases in one transaction, keeping the old one until the app is healthy.

`tools/deploy-check.sh` proves all of it, including the refusals, against the real stack. It is
part of `tools/verify.sh packaging` and CI.

## `proxmox/`

`create-vm.sh` runs on a Proxmox VE host. It makes a Debian 13 VM whose first boot runs
`bootstrap-vm.sh`. That script:

- checks the clone is at the commit of the host's checkout;
- installs Docker from Debian's archive;
- sets a firewall that lets in SSH only and drops network packets addressed to `127.0.0.1`;
- generates `.env` and the backup-signing key on the VM;
- starts the stack.

See `userguide/run-it-on-proxmox.md`.

## Behind a reverse proxy: which address the rate limits see (known gap)

Every public route (sign-in, setup, invitations, the phone app's `GET /api/meta`) is rate-limited
per **client address**, and the API takes that address from the socket, never from a forwarded
header a caller could make up (`ClientAddress`).

**As shipped, every caller shares one limit.** The stack publishes nginx (the `web` service) on
`127.0.0.1:8080`, and a self-hoster puts their own TLS-terminating proxy in front of it. Two proxies
sit before the API, so the API's socket address is always nginx's, and nginx's own `$remote_addr`
is the outer proxy's, not the phone's. A busy household can see *"The server is busy. Try again in
a minute."* in the app and `429`s on the web. This is a recorded known gap, not a setting to flip
lightly.

What correct forwarding needs, end to end:

1. **The outermost proxy** — the one that terminates TLS and is the only thing reachable from the
   network — sets `X-Forwarded-For` to the real client's address, **replacing** whatever the
   client sent (nginx: `proxy_set_header X-Forwarded-For $remote_addr;`).
2. **Every inner proxy passes that header through unchanged** rather than appending to it. The
   stack's `frontend/nginx.conf` currently *appends* (`$proxy_add_x_forwarded_for`), which keeps
   whatever arrived and adds the outer proxy's address; with Tomcat skipping private-range entries
   as trusted proxies, a caller could then choose the address the limit sees and step around it.
3. **The API is told to trust the header** with `server.forward-headers-strategy=native`, which
   Spring Boot reads from `SERVER_FORWARD_HEADERS_STRATEGY`. **It only takes effect once
   `docker-compose.yml` passes that variable** in the `api` service's `environment:` block, and
   today it does not: setting it in `.env` alone changes nothing.

Until all three hold, leave the API on the socket address. A shared limit is an inconvenience; a
limit a caller can choose for themselves is no limit.

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
