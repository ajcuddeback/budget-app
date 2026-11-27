# Feature: Deployment: install, back up, restore, upgrade

- **Status:** In progress. Slice 15b. Scripts, the repeatable check and the user guide are written;
  the Proxmox VM script is untested on a real Proxmox host (see Testing notes).
- **Owner:** Repository owner
- **Last updated:** 2026-11-27
- **Related:** ADR-0016 (self-hosted; upgrades and backups are features), ADR-0007 (Flyway),
  ADR-0026 (the owner is the operator), ADR-0030 (the runtime cannot change the schema),
  ADR-0038 (an imported file is never stored), `docs/architecture/security-model.md`,
  `deploy/README.md`, `userguide/installing.md`, `userguide/run-it-on-proxmox.md`
- **Designs:** None. There is no screen. Everything here happens at the machine (ADR-0026, and
  [admin-console.md](admin-console.md) "Out of scope").

## Purpose

Somebody is about to put their household's whole financial history on a machine in their
cupboard. They need to install it, take backups they can trust, get the data back when the disk
dies, and move to a new version without losing a year of records. ADR-0016 calls upgrades and
backups features, not afterthoughts. This is that feature.

## User stories

- As the **operator**, I want to install Budget Owl from the repository with one password to
  choose, so that a first start works with nothing else configured.
- As the operator, I want to **back up** on a schedule into a folder I choose, optionally
  encrypted to a key only I hold, so that a stolen backup drive does not give away our finances.
- As the operator, I want to **restore** onto a new machine and know it worked, so that losing
  the server is a bad afternoon and not the end of our records.
- As the operator, I want a restore to **refuse** to overwrite a database that has data in it
  unless I say so explicitly, so that typing the wrong command on the wrong day does not erase it.
- As the operator, I want to **upgrade** with a backup taken first, without exception, so that a
  failed migration can always be undone.
- As the operator, I want to **roll back** an upgrade that went wrong, and be told the exact
  commands, so that I am not working out the procedure while the app is down.
- As the operator on **Proxmox**, I want one script that makes a correctly sized, locked-down
  VM that installs the app on first boot, so that I do not have to remember twenty `qm` flags.

## Rules and behavior

### What is backed up

1. **The database is the only state.** The stack has one volume, `db-data`. The api and web
   containers hold nothing: imported files are never stored (ADR-0038) and there is no upload
   directory. A backup is a `pg_dump` of the one database.
2. **`.env` is not in the backup and is not needed to restore.** Role passwords are cluster-level
   and `pg_dump` does not include them, so a restore works on a new install with a new `.env`.
   The operator keeps a copy of `.env` somewhere safe (a password manager) because it is the only
   copy of the passwords for the running instance, not because a restore needs it.
3. **Web sign-in sessions are left out**: `--exclude-table-data` for `spring_session` and
   `spring_session_attributes`. A session id is a live credential for up to 12 hours; a backup is
   kept for months. A restored server signs everyone out of the browser, which is right anyway.
   Phone tokens are kept: only their SHA-256 hash is stored (`auth_tokens.token_hash`).
4. **Everything else is in it**, including what ADR-0026 accepts the operator can read: every
   transaction, the OIDC client secret in `instance_settings` (stored in plaintext), password
   hashes, invitation token hashes, account last-four digits (ADR-0028).

### `deploy/backup.sh`

5. Runs `pg_dump --format=custom` **inside the `db` container** through `docker compose exec`, as
   the owning role, over the container's local socket. No password is read, passed or printed.
   The script bodies are single-quoted so the container's shell expands `$POSTGRES_USER`.
6. Writes to a directory the operator chooses (`--out` or `BACKUP_DIR`). Required; there is no
   default location, so a backup never lands somewhere nobody looks.
7. `umask 077` for the whole run. The directory is created `0700` if missing. The script
   **refuses** a directory that group or others can write, because they could swap the backup or
   its checksum. Files are `0600`.
8. The file name is `budgetowl-<UTC yyyymmddThhmmssZ>-<version>-schema<N>[-label].dump`, plus
   `.age` or `.gpg` when encrypted. `<version>` is the api image's tag unless it is `latest`, else
   `git describe` of the checkout. `<N>` is the last successful Flyway version.
9. A `.sha256` file is written beside it, in `sha256sum` format.
10. The dump is written to `<name>.partial` and renamed only after it succeeded and
    `pg_restore --list` read it back (read from the same stream, so it costs no second dump). A
    failed or unreadable backup leaves nothing that looks like a backup.
11. **Optional encryption**: `--age-recipient` / `--age-recipients-file` (age public keys) or
    `--gpg-recipient` (a public key in the keyring). The plain dump never touches the disk: the
    stream goes `pg_dump | age > file`. **Only public keys are accepted.** The script refuses to
    write into a folder holding a small file that contains `AGE-SECRET-KEY-` or
    `BEGIN PGP PRIVATE KEY BLOCK`. A key beside the backups makes the encryption pointless.
12. `--keep N` deletes all but the newest N **unlabelled** backups in the folder after a good one.
    Labelled backups (`pre-upgrade`, `pre-restore`) are never deleted by it.

### `deploy/restore.sh`

13. **Checksum first, and it is mandatory.** There is no `.sha256`, so no restore. The hash is
    compared by value, so a renamed file still verifies and a swapped one does not. A mismatch
    exits 3 before anything is touched, with or without `--replace`.
14. It checks that `pg_restore` can read the archive (after decrypting, if encrypted) before
    anything else happens.
15. **It refuses a database that has any table in `public`** (exit 4) unless `--replace` is
    given. The refusal happens before anything is stopped. A fresh install that has started once
    has empty tables and is refused too; the message says `--replace` is safe there.
16. It restores into a **separate scratch database** (`budgetowl_restore_scratch`, a fixed name)
    with `--single-transaction --exit-on-error --no-owner --no-privileges`, while the api and web
    are stopped. A failure drops the scratch database and starts the services that were running.
    The live database is untouched up to here.
17. **What came back is checked before it is used**: a Flyway history must exist, it must have no
    failed migration, and its schema must not be **newer** than the highest migration in this
    version's api image (read from the jar without starting it). A newer backup exits 5: this
    version cannot run it, and migrations cannot be undone. **If the image cannot be read, that is
    also exit 5** (fail closed); `--skip-version-check` is the explicit override. An earlier
    version failed open here under Compose 2.26, and the deploy check caught it.
18. With `--replace`, a **safety backup** of the current database is taken with `backup.sh
    --label pre-restore` (optionally encrypted with `--safety-age-recipient` /
    `--safety-gpg-recipient`) **before** the swap. If it fails, nothing is replaced.
19. The swap is `DROP DATABASE <target> WITH (FORCE)` then `ALTER DATABASE scratch RENAME TO
    <target>`. Once it starts, the scratch database is never dropped by the cleanup, so a swap
    that stops halfway leaves the restored data in the scratch database and the message says so.
20. The application role and its grants are re-applied by running the same
    `deploy/db-init/10-create-app-role.sh` that made them (ADR-0030). This is why `--no-privileges`
    is safe: the grants always match this install's role, whatever the backup's was.
21. It prints the number of people, households, accounts and transactions before and after, then
    starts the stack and waits for the api to be healthy. `--no-start` leaves it stopped.

**Test restore (`deploy/test-restore.sh BACKUP`)** restores into a second Compose project,
`budgetowl-restore-test`, on `127.0.0.1:8481`, beside the real instance. `--clean` removes it.
The project name is fixed in the script, so neither the restore nor `--clean` can reach the real
instance's volume. The operator never has to type a project name next to `down -v`.

### Upgrades: `deploy/upgrade.sh`

22. **There is no upgrade without a backup.** `--backup-dir` is required, and a failed backup
    stops the upgrade before anything changes.
23. Refuses a checkout with local changes to tracked files (`git status`), because a pull would
    merge into them. `.env` is ignored by git and does not count.
24. `git pull --ff-only`, or `--ref` to check out a tag, branch or commit from `origin`, or
    `--no-pull` if the operator updated the code themselves.
25. `docker compose build --pull`, which takes patched base images, then `docker compose pull db`.
    **Images are built locally today.** `docker-compose.yml` names
    `ghcr.io/ajcuddeback/budget-owl-{api,web}`, but CI builds and scans images without publishing
    them, so `docker compose pull` for those two fails. `--no-build` uses images already on the
    machine.
26. `docker compose up -d`, then wait up to 300 s for the api to be healthy. **Flyway runs every
    new migration as the api starts, and the api is healthy only after all of them succeed.**
27. **It never rolls back by itself.** Going back is a decision about data. It prints the exact
    commands (previous commit, build, `restore.sh --replace <pre-upgrade backup>`) on success
    and on failure.

### Rollback

28. **Rolling back means the previous version AND the pre-upgrade backup, together.** Flyway
    migrations are forward-only. Flyway's default `ignoreMigrationPatterns` is `*:future`, so the
    old version may **start** against a newer schema without complaint. Hibernate's
    `ddl-auto: validate` catches only some differences. Checking out the old code without restoring
    is never correct, and the docs say so.
29. `restore.sh` enforces the other half: a backup newer than the code is refused (rule 17).

### The repeatable check: `tools/deploy-check.sh`

30. The real stack from nothing, as its own Compose project (`budgetowl-deploycheck`, refusing
    any name without `check` in it, because it deletes that project's volumes), on
    `127.0.0.1:8480`, with an env file of random throwaway passwords. It never reads `.env`.
31. It creates the first account and two accounts through the API, signs in a browser session,
    backs up (plain and age), then checks:
    - file modes;
    - the name format;
    - no session rows in the dump;
    - the refusal to back up into a folder that holds a private key;
    - restore without `--replace` refused, with the app still serving the same data;
    - a tampered file refused on its checksum, even with `--replace`;
    - `down -v`, then the restore onto the empty install brings the accounts back to the fourth
      decimal, and the pool still connects as `budgetowl_app`;
    - an encrypted `--replace` over live data restores the backup and leaves a `0600` safety
      backup that holds the newer row;
    - a backup claiming schema 9999 is refused and nothing changes.
32. `tools/verify.sh packaging` runs it, and CI's Packaging job runs it with `age` installed.

### Proxmox: `deploy/proxmox/create-vm.sh`

33. Runs as root on the Proxmox host. Creates a **VM, not an LXC**: Docker in an unprivileged LXC
    needs `nesting=1` (and often `keyctl=1`), shares the host kernel, and is not something Proxmox
    recommends for Docker. A VM is the simplest thing with a real isolation boundary.
34. Downloads Debian 13 (trixie) `genericcloud-amd64` over HTTPS from `cloud.debian.org` and
    verifies it against `SHA512SUMS` from the same place. **Debian publishes no signature for the
    cloud image checksums** (`SHA512SUMS.sign` is a 404 for trixie, checked on 2026-11-27). Its
    own guidance is to download both over TLS. If a `.sign` ever appears, it is checked with
    `gpgv` against `debian-role-keys.gpg`, and a bad signature stops everything. `--image-sha512`
    lets the operator pin a hash they checked themselves. A cached image is reused only if it
    still matches.
35. Refuses a VMID that exists as a VM or a container. Every argument that reaches `qm` or the
    YAML is validated against a strict pattern. `--dry-run` prints every command;
    `--print-user-data` prints the snippet.
36. The VM has virtio-scsi-single, the guest agent, `onboot=1`, a serial console, and defaults of
    2 cores, 4096 MiB and 32 GiB. Those come from measurement (see Testing notes) plus room to
    **build** the images on the VM, which has to happen because none are published.
37. The cloud-init **user-data snippet holds no secrets**: the operator's SSH **public** key, a
    user name, a git URL and a ref. It does not set a password (`lock_passwd`,
    `ssh_pwauth: false`, `disable_root`). The script refuses a file that contains `PRIVATE KEY`.
38. First boot runs `deploy/proxmox/bootstrap-vm.sh`. That script:
    - installs Docker Engine and Compose from Debian's own signed archive. Debian 13 ships
      `docker.io` 26.1.5 and `docker-compose` 2.26.1 as a CLI plugin, so no third-party apt
      repository or key is involved;
    - sets an nftables input policy of drop, with SSH allowed. It uses its own table and never
      `flush ruleset`, because Docker's rules are in nftables too;
    - turns on unattended security upgrades;
    - clones the repository;
    - **generates `.env` on the VM** with `openssl rand`, mode 0600, never replacing an
      existing one;
    - builds and starts the stack;
    - installs a nightly `budgetowl-backup.timer` writing to `/srv/budgetowl-backups`.

## Data model

None. No migration. The scripts read `flyway_schema_history`, `users`, `households`, `accounts`,
`transactions` and `spring_session*`. They write nothing to the schema except what `pg_restore`
and `10-create-app-role.sh` do.

## API

None. `tools/deploy-check.sh` uses `POST /api/setup/first-user`, `POST /api/auth/login`,
`POST /api/auth/token`, `GET`/`POST /api/accounts`, as a client.

## UI

None. Documented for the operator in `userguide/installing.md` (sections 1, 2, 5, 6) and
`userguide/run-it-on-proxmox.md`.

## Security considerations

### Threat model

**Assets.**

- The backup files. They hold the household's entire financial history, and they are the one copy
  that leaves the machine on purpose.
- The database passwords in `.env`.
- The live database.
- On Proxmox, the hypervisor itself.

**Actors.**

- Someone who gets a backup: a stolen USB disk, a NAS share open to the LAN, a cloud sync folder.
- Another local user on the server.
- The operator themselves, on a bad day.
- Whoever can tamper with a download between Debian and the Proxmox host.

| # | Threat | Control | Where |
| - | ------ | ------- | ----- |
| T1 | **A backup is read by someone it was not meant for.** It contains everything | `0600` files, `0700` directory, `umask 077`; refuse a group/other-writable directory; optional age/gpg encryption **to a public key**, with the plain dump never written to disk; refuse a folder holding a private key; the docs tell the operator to keep the private key off the server and the backup storage | `backup.sh` rules 7, 11; installing.md section 5 |
| T2 | **Backups written somewhere nobody thought about** (inside the repo, on the same disk as the database) | `--out` is required, with no default. The Proxmox page and the timer say plainly that `/srv/budgetowl-backups` is on the VM's own disk until real off-VM storage is mounted there | rule 6; bootstrap step 6 |
| T3 | **A DB password on a command line or in a dump** | Every database command runs inside the container over the local socket, so no password exists anywhere in the scripts. `pg_dump` never includes role passwords. The deploy check generates its passwords per run in a `0600` file in a temp dir. The VM generates `.env` itself | `lib.sh`; rule 2; rule 38 |
| T4 | **A live session id in a months-old backup** | Session rows are excluded. Phone tokens are hashed | rule 3; asserted in deploy-check |
| T5 | **A restore silently overwrites a live database** | Refuses any non-empty database without `--replace` (exit 4, before anything stops); a safety backup always comes before the swap; the old database is not touched until the new one has been restored and checked | rules 15–19; asserted in deploy-check |
| T6 | **A damaged or altered backup is restored** | Mandatory checksum, compared by value; `pg_restore --list` before anything else; restore in one transaction into a scratch database | rules 13, 14, 16; asserted in deploy-check |
| T7 | **The checksum is swapped together with the file** by someone who can write to the backup folder | Out of reach for a SHA-256 beside the file, honestly. Mitigated by refusing a group/other-writable directory. Encryption with age authenticates the content, so a swapped `.age` file fails to decrypt with the operator's key. Recorded, not solved | rule 7; open question 2 |
| T8 | **An upgrade migration fails halfway** | Each Flyway migration on PostgreSQL is one transaction, so the failing one rolls back. Earlier migrations in the same run stay applied, so the database may sit between versions. The api stays unhealthy rather than serving. `upgrade.sh` always takes a backup first and prints the rollback | rules 22–27 |
| T9 | **A downgrade after migrations**: old code on a newer schema | Rollback is documented as code AND backup, together. `restore.sh` refuses a backup newer than the image's migrations (exit 5). Flyway's `*:future` default means old code may start on a newer schema, which is why the restore is not optional | rules 17, 28, 29; asserted in deploy-check |
| T10 | **The restored database grants the wrong role**, or the runtime ends up as the owner | `--no-privileges`, then grants re-applied by the init script; deploy-check asserts the pool connects as `budgetowl_app` after a restore | rule 20 |
| T11 | **The stack is exposed to the network by default** | Unchanged and kept: the only published port is `${BIND_ADDRESS:-127.0.0.1}:${WEB_PORT:-8080}`; `verify.sh` asserts exactly one published port; the VM firewall drops everything inbound but SSH | compose file; rule 38 |
| T12 | **`create-vm.sh` runs as root on the hypervisor**, so a bug or an injected argument is root on every VM | Every input is validated against a strict pattern before it reaches `qm` or YAML; no `eval`, no `curl \| bash`; downloads go to `.partial` and are renamed only after the hash matches; `--dry-run` shows every command; it refuses an existing VMID; it changes no storage or cluster configuration (snippets content must already be enabled, and it says how) | rules 34–35 |
| T13 | **A tampered cloud image** | HTTPS only (`--proto '=https' --tlsv1.2`); SHA-512 against Debian's `SHA512SUMS`; optional `--image-sha512` pin; a `.sign` verified if Debian ever publishes one. **Gap:** without a signature, trust rests on TLS to `cloud.debian.org` | rule 34 |
| T14 | **Secrets in the cloud-init snippet**, which is a world-readable file in the host's snippets storage and readable by anyone with `VM.Config.Cloudinit` | The snippet holds a public key, a user name, a URL and a ref. Passwords are generated inside the VM | rules 37, 38 |
| T15 | **The VM reachable on more than SSH** | nftables input policy drop, SSH only; password SSH off; no root login; the app on 127.0.0.1, reached through the HTTPS guide | rule 38 |
| T16 | **The docker group is root** | Only the operator is added to it, and the docs say what it means | bootstrap step 1 |

### Things this does not protect against

- **Anyone with root on the server, or with the Proxmox host.** They can read the volume, the
  environment and every backup that is not encrypted. ADR-0026 accepts this: the operator owns
  the box.
- **The superuser password in the api container's environment** (ADR-0030 correction). Unchanged
  by this work.

## Edge cases

- **A fresh install that started once** has 32 empty tables, so it is refused without
  `--replace`. The message says `--replace` is safe there, and the safety backup is small.
- **`DB_APP_PASSWORD` set without `DB_APP_USER`.** The api falls back to the owner's user name with
  the app role's password and fails authentication on every start. This was found during this
  work. The compose file passes both through with no default. `.env.example` must carry both
  (reported to the owner: this agent cannot edit `.env*`).
- **A backup from before the session tables existed**, or without a Flyway history, is refused
  ("no Budget Owl schema history").
- **A backup taken during a failed migration** (Flyway history with `success = false`) is refused.
- **Disk space**: a restore needs room for two copies of the database for a moment (live plus
  scratch).
- **gpg recipients** must be trusted in the keyring, or gpg's batch mode refuses to encrypt.
  The script does not override trust.
- **Running two restores at once** is not guarded against. The second one would drop the first
  one's scratch database. Restore is an operator action; noted, not locked.

## Out of scope

- **Backups in the admin console.** The machine does this better, and a button that writes a file
  of everyone's finances is an attack surface (admin-console.md).
- **Point-in-time recovery (WAL archiving).** Nightly logical dumps fit a household's write rate.
  PITR is a real option for someone who wants it, and they can run it beside this.
- **Automatic rollback.** Deliberately not built (rule 27).
- **Publishing images.** Not done, and not done here (no push). When it happens, `upgrade.sh`
  gains a `docker compose pull` path and this doc changes.
- **An LXC recipe.** See rule 33.
- **Moving Flyway out of api start-up** (the ADR-0030 follow-up) is not this slice's.

## Open questions

1. **Publishing images to ghcr.io.** The compose file already names the images. Once they are
   published, the default changes from build to pull, the VM no longer needs build-sized RAM, and
   signing them (cosign) becomes the integrity story that a git clone over HTTPS is today.
   Owner.
2. **Authenticating backups at rest** beyond the checksum (T7). age encryption authenticates the
   content; an unencrypted backup has only a checksum stored beside it. Signing backups with a
   key held off the server would close this. Owner, if wanted.
3. **A release branch or tags.** There are none, so the VM script's default `--ref` is `main`.
   `main` does not yet contain this work until it merges.

## Testing notes

**Proven in the dev container on 2026-11-27**, with `tools/deploy-check.sh`, which passed 34 of 34.
The full list is in rule 31.

**Measured**: api ~490 MiB, db ~77 MiB, web ~5 MiB at rest. Database on disk 65 MB for a
household with two accounts. A custom-format backup is 168 KB. Images: api 412 MB, web 106 MB,
postgres:16-alpine 420 MB. The build cache during a build is about 2 GB. One run of the check
takes 90 s.

**What the dev container could not test, and how it was worked around:**

- The images were not built by `docker compose build`. The agent proxy refuses Alpine's
  package mirror and Maven Central's certificate inside the build.
- The images under test were built from the same Dockerfiles with the proxy's CA mounted. The
  `apk upgrade` layer was skipped, and the api jar was built on the host with
  `mvn -f backend package`.
- Backup and restore do not depend on any of that. CI builds the real images and runs the same
  check.

**Proxmox: not run on a Proxmox host.**

- Tested: shellcheck, the `--dry-run` output, the argument refusals, and the `--print-user-data`
  output, which was validated by `cloud-init schema` in a Debian 13 container.
- `bootstrap-vm.sh --no-start` was run in a plain `debian:trixie` container with `NET_ADMIN`.
  The packages installed, the nftables ruleset loaded, the clone, `.env` generation (0600, and
  kept on a re-run) and the timer units passed `systemd-analyze verify`.
- `tools/deploy-check.sh` was run end to end **with Debian 13's own docker-cli 26.1.5 and
  docker-compose 2.26.1** against the host's daemon.
- Not tested: `qm`, `pvesm`, `qm importdisk` and cloud-init on a real first boot, which depend on
  Proxmox VE and a real VM.
- The Proxmox flags were written from general knowledge, with partial confirmation from search
  results. `pve.proxmox.com` was not reachable from the sandbox: `--cicustom
  user=<storage>:snippets/<file>` replaces `ciuser`/`sshkeys`, and `qm importdisk` is the older
  form of `qm set --scsi0 <storage>:0,import-from=…`.

**Found by testing, and fixed:**

- Debian 13's `docker.io` only *Recommends* `docker-cli`, so with `--no-install-recommends` the
  VM had no `docker` command. The bootstrap now names `docker-cli`.
- Compose 2.26 does not accept `docker compose run --pull never`. `restore.sh`'s newer-backup check
  read the resulting empty answer as "cannot tell" and went ahead with a schema-9999 backup. It
  now finds the image another way and fails closed (rule 17).
- `DB_APP_PASSWORD` set without `DB_APP_USER` makes the api fail on every start (Edge cases).
