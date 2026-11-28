# Feature: Deployment: install, back up, restore, upgrade

- **Status:** In progress. Slice 15b. Round 2 answers the security audit ("Don't ship as is"):
  backups are signed and a restore refuses anything this server (or one the operator names) did
  not sign. The Proxmox VM script is still untested on a real Proxmox host (see Testing notes).
- **Owner:** Repository owner
- **Last updated:** 2026-11-28
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
- As the operator, I want a restore to **accept only backups my server made**, so that someone
  who can write to the backup share cannot slip their own database, users or SQL into my server.
- As the operator, I want to **restore** onto a new machine and know it worked, so that losing
  the server is a bad afternoon and not the end of our records.
- As the operator, I want a restore to **refuse** to overwrite a database that has data in it
  unless I say so explicitly, so that typing the wrong command on the wrong day does not erase it.
- As the operator, I want to **upgrade** with a backup taken first, without exception, so that a
  failed migration can always be undone.
- As the operator, I want to **roll back** an upgrade that went wrong, and be told the exact
  commands, so that I am not working out the procedure while the app is down.
- As the operator on **Proxmox**, I want one script that makes a correctly sized, locked-down
  VM that runs exactly the code I looked at, so that I do not have to remember twenty `qm` flags.

## Rules and behavior

### What is backed up

1. **The database is the only state.** The stack has one volume, `db-data`. The api and web
   containers hold nothing: imported files are never stored (ADR-0038) and there is no upload
   directory. A backup is a `pg_dump` of the one database.
2. **`.env` is not in the backup and is not needed to restore.** Role passwords are cluster-level
   and `pg_dump` does not include them, so a restore works on a new install with a new `.env`.
   The operator keeps a copy of `.env` in a password manager because it is the only copy of the
   running instance's passwords.
3. **Sign-ins are left out**: `--exclude-table-data` for `spring_session`,
   `spring_session_attributes` and `auth_tokens`. A web session id is a live credential; a phone
   token row is only a hash, but restoring it would bring back a phone that was signed out (a lost
   one, say). After a restore everybody signs in again, on the web and on phones, and the guide
   says to review **Devices** afterwards.
4. **Everything else is in it**, including what ADR-0026 accepts the operator can read: every
   transaction, the OIDC client secret in `instance_settings` (stored in plaintext), password
   hashes, invitation token hashes, account last-four digits (ADR-0028).

### Settings, shared by every script

5. `/etc/budgetowl/backup.env` (the nightly timer's `EnvironmentFile`) is also read by
   `backup.sh`, `restore.sh` and `upgrade.sh`, so a manual, safety or pre-upgrade backup is
   encrypted and signed exactly like the nightly one. It is **parsed, never sourced**: only the
   `BACKUP_*` keys, as `KEY=value`. The environment and command-line options win.

### `deploy/backup.sh`

6. Runs `pg_dump --format=custom` **inside the `db` container** through `docker compose exec`, as
   the owning role, over the container's local socket. No password is read, passed or printed.
   Every `psql` has `-w`.
7. Writes to a directory the operator chooses (`--out` or `BACKUP_DIR`). Required; there is no
   default location.
8. **The folder.** `umask 077`. The folder is created `0700` if missing and resolved with
   `pwd -P`, so a symlinked `--out` is checked where it really is. The script refuses:
   - a folder that group or others can write;
   - any parent folder that group or others can write, unless it is root's and sticky (`/tmp`).
     Otherwise someone could move the backup folder aside and put their own in its place.

   It **warns** on NFS, SMB, 9p and FUSE, where mode bits are the server's to enforce, if at all.
   Files are `0600`.
9. **No private key beside the backups.** Every file under 64 KB in the folder is read,
   whatever it is called; only `*.dump`, `*.dump.age` and `*.dump.gpg` are skipped. Any of these
   refuses the backup:
   - `AGE-SECRET-KEY-`;
   - `BEGIN PGP PRIVATE KEY BLOCK`;
   - `BEGIN OPENSSH PRIVATE KEY`, which covers the signing key.

   The signing key's own folder must not be inside the backup folder.
10. The file name is `budgetowl-<UTC yyyymmddThhmmssZ>-<version>-schema<N>[-label].dump`, plus
    `.age` or `.gpg` when encrypted. Beside it:
    - `.sha256`, which detects damage;
    - `.sig`, which says who made it (rule 12);
    - `.meta`, which says which install it came from (rule 14).

    All are `0600`, written as `.partial` and renamed only once the dump succeeded and
    `pg_restore --list` read it back from the same stream. **An existing backup is never
    overwritten**: two backups in the same second would share a name, so the second waits for
    the next second, and gives up rather than replacing the first.
11. **Optional encryption**: `--age-recipient` / `--age-recipients-file` (public keys) or
    `--gpg-recipient`. The plain dump never touches the disk. A **plaintext backup into a folder
    that already holds `.age` or `.gpg` backups is refused** (usually a forgotten setting), unless
    `--allow-plaintext`.
12. **Every backup is signed**: `ssh-keygen -Y sign -n budgetowl-backup` over the exact bytes on
    disk (after encryption), with the server's signing key.
    - **Where the key is:** `BACKUP_SIGNING_KEY`, else `/etc/budgetowl/backup-signing-key` (made by
      the VM bootstrap), else `~/.config/budgetowl/backup-signing-key`, made on first use.
    - **What it is:** ed25519 with no passphrase, so the timer runs unattended. It is mode
      `0600`, owned by the operator account, and refused if it is any looser or is someone else's.
    - **Its public half:** made beside it as `<key>.allowed_signers`, one line:
      `budgetowl-backup namespaces="budgetowl-backup" ssh-ed25519 …`.
    - `--print-signing-key` prints that line.
13. **Rotation.** `--keep N`, `--keep-weekly N` and `--keep-monthly N` are tiers.
    - **What is kept:** the newest N nightly backups, plus the newest of each of the last N ISO
      weeks, plus the newest of each of the last N months.
    - **What is pruned:** only unlabelled backups whose `.meta` names **this** install. Labelled
      ones (`pre-upgrade`, `pre-restore`) and other installs' are never deleted. A pruned backup
      goes with its `.sha256`, `.sig` and `.meta`.
14. **Rotation never loses the real history.** Nothing is pruned:
    - when the new backup has nobody in it (a new, unset-up install);
    - or when the newest earlier backup is from a different install, or does not say which.
      The install is `left(md5('budgetowl:' || instance_settings.created_at), 16)`, which a new
      install never shares with an old one.

    This is the scenario the audit raised: the VM is rebuilt, the old share is mounted at the
    timer's path, and 14 nights later no real backup is left.

### `deploy/restore.sh`

15. **Signature first, before anything else reads the file, including before decryption.**
    - **How:** `ssh-keygen -Y verify -I budgetowl-backup -n budgetowl-backup` against an
      allowed-signers file. `--signers FILE` names the trusted servers; the default is this
      server's own `<key>.allowed_signers`.
    - **Refused, exit 6:** no `.sig`, or a signature by a key not listed. The same goes for a
      changed file.
    - **A new server after a disaster** has a new key, so the operator gives the old server's line
      with `--signers`. A file may hold several lines, old and new.
    - **`--allow-unsigned`** is the explicit override, for a backup the operator made themselves.
      That includes one from before backups were signed. It prints a warning that the contents
      run as the database **superuser**.
16. **Checksum second**, mandatory, compared by value (exit 3). It catches damage; the signature
    catches everything else.
17. The age identity is read **once, into memory**, so `--age-identity <(cat /media/usb/key.txt)`
    works and the key file can be put away as soon as the restore starts.
18. **Leftovers** from an interrupted restore are found before anything happens (exit 7):
    - the live database is missing but `budgetowl_restore_previous` or `…_scratch` exists;
    - or `…_previous` still exists beside the live one.

    The message gives the exact SQL to put things back or to drop it.
19. `pg_restore --list` must read it; **a database with any table in `public` is refused** without
    `--replace` (exit 4), before anything is stopped.
20. It restores into `budgetowl_restore_scratch` (a fixed name) with `--single-transaction
    --exit-on-error --no-owner --no-privileges`, while the api and web are stopped. A failure drops
    the scratch database and starts what was running. If that start fails, it says Budget Owl is
    **STOPPED**.
21. **What came back is checked before it is used**:
    - a Flyway history must exist, with no failed migration;
    - the schema must not be newer than the highest migration in this version's api image (exit 5).

    If the image cannot be read, that is also exit 5 (fail closed). `--skip-version-check` is the
    override.
22. With data present (`--replace`), a **safety backup** is taken first, signed by this server, and
    encrypted:
    - an `.age` restore encrypts it to `age-keygen -y` of the identity being used;
    - otherwise it follows `backup.env`, or `--safety-age-recipient` / `--safety-gpg-recipient`.

    It is therefore never plaintext when the operator encrypts. Rule 11 makes a plaintext safety
    backup into a folder of encrypted ones fail, and then nothing is replaced.
23. **The swap is one transaction**: `ALTER DATABASE <live> RENAME TO budgetowl_restore_previous;
    ALTER DATABASE budgetowl_restore_scratch RENAME TO <live>`.
    - **Why it is safe:** PostgreSQL runs both renames in one `BEGIN … COMMIT` (checked), so there
      is never a moment with no database under Budget Owl's name.
    - **Dropping the old one:** `…_previous` is dropped **only once the api is healthy** on the
      restored data. Until then it is kept, and the exact commands to go back to it are printed.
24. Grants are re-applied by `deploy/db-init/10-create-app-role.sh` (ADR-0030). The counts of
    people, households, accounts and transactions are printed before and after.

**Test restore (`deploy/test-restore.sh BACKUP`)** restores into a second Compose project,
`budgetowl-restore-test`, on `127.0.0.1:8481`, with **its own throwaway passwords**. They live in
`~/.local/state/budgetowl/restore-test.env`, 0600; only the image tag is read from `.env`. The
backup is checked exactly like a real restore (signature first; `--signers`, `--allow-unsigned`
pass through). `--clean` removes the copy and its env file. The project name is fixed in the
script, so neither command can reach the real instance.

### Upgrades: `deploy/upgrade.sh`

25. **There is no upgrade without a backup.** `--backup-dir` (or `BACKUP_DIR` in `backup.env`) is
    required. The pre-upgrade backup is encrypted and signed like the nightly one (rule 5), and a
    failed backup stops the upgrade.
26. Refuses a checkout with local changes to tracked files. `--ref` must look like a branch, tag
    or commit name: `^[A-Za-z0-9][A-Za-z0-9._/-]*$`, with no `..`. It reaches git only after
    `--end-of-options`, and is resolved to a commit before checkout.
27. `git pull --ff-only` (or `--ref`, or `--no-pull`), then `docker compose build --pull`, then
    `docker compose pull db`. **Images are built locally today**; none are published, so
    `docker compose pull` fails for api and web. `--no-build` uses images already present.
28. `docker compose up -d`, then wait up to 300 s for the api to be healthy. Flyway runs every new
    migration as the api starts, and the api is healthy only after all of them succeed.
29. **It never rolls back by itself.** It prints the exact commands: previous commit, build, and
    `restore.sh --replace <pre-upgrade backup>`, with `--age-identity` when that backup is `.age`.

### Rollback

30. **Rolling back means the previous version AND the pre-upgrade backup, together.** Flyway's
    default `ignoreMigrationPatterns` is `*:future`, so the old version may start against a newer
    schema without complaint. `restore.sh` enforces the other half (rule 21).

### The repeatable check: `tools/deploy-check.sh`

31. The real stack from nothing, as its own Compose project (`budgetowl-deploycheck`; any name
    without `check` in it is refused). It has its own env file of random passwords, its own
    signing keys (a "server 1" and, after the disaster, a "server 2"), and `BACKUP_ENV_FILE=/dev/null`.
    It never reads `.env` or `/etc/budgetowl`. 68 checks, in these steps (the output names each
    check):
    - **Empty install**: rotation does not run; the signing key is made on first use.
    - **Household**: set up through the API; the pool connects as `budgetowl_app`.
    - **Back up**:
      - file modes, the name, and the signature;
      - no session or token rows;
      - an age backup;
      - plaintext refused among encrypted backups;
      - key files refused, whatever they are called;
      - a second backup with the same name refused, the first intact (a fake `date`);
      - a writable parent folder refused; a symlinked `--out` accepted.
    - **Refusals**:
      - data present without `--replace` (exit 4);
      - a damaged checksum (exit 3);
      - a changed file with a recomputed checksum (exit 6);
      - a forged `.age` with no signature, and with a real backup's `.sig` beside it (exit 6);
      - a backup signed by another key (exit 6);
      - in every case, no safety backup and unchanged data;
      - a plaintext safety backup among encrypted ones, which replaces nothing;
      - `--allow-unsigned` works and warns, and the previous database is dropped once the app is
        healthy.
    - **Leftovers**: a missing live database with `…_previous` present is refused (exit 7).
    - **Disaster**:
      - `down -v`; then a new signing key refuses the old backup (exit 6);
      - `--signers <old line>` restores it to the fourth decimal;
      - an old phone token gets 401, and the pool is still `budgetowl_app`.
    - **Encrypted `--replace`** with `--age-identity <(cat …)`: the safety backup is `.age`,
      `0600`, signed by server 2, and holds the newer row.
    - **Rollback guard**: a schema-9999 backup is refused (exit 5) and nothing changes.
    - **test-restore.sh**:
      - the copy runs with the backup's data and a database password that is not the real one;
      - `--clean` removes it;
      - a forged backup is refused (exit 6).
    - **Rotation**:
      - exactly the survivors an independent statement of the policy predicts;
      - companions removed with each pruned backup;
      - nothing pruned after another install's backup.
32. `tools/verify.sh packaging` runs it, and so does CI's Packaging job (with `age`; GitHub's runner
    has `ssh-keygen`).

### Proxmox: `deploy/proxmox/create-vm.sh`

33. Runs as root on the Proxmox host. Creates a **VM, not an LXC**: Docker in an unprivileged LXC
    needs `nesting=1` (and often `keyctl=1`), shares the host kernel, and is not something Proxmox
    recommends for Docker.
34. Downloads Debian 13 `genericcloud-amd64` over HTTPS from `cloud.debian.org`, verified against
    `SHA512SUMS` from the same folder.
    - **No signature exists:** Debian publishes none for cloud-image checksums. `SHA512SUMS.sign`
      is a 404 for trixie (checked 2026-11-27), and Debian's guidance is TLS. A `.sign`, if one
      appears, is checked with `gpgv`.
    - **Pinning:** `--image-sha512` pins a hash the operator checked. `--image-base` takes a
      **dated build folder** (e.g. `…/trixie/20261123-2671`), whose file name carries the build,
      so a pin does not break at Debian's next build. It must be on `cloud.debian.org` or
      `cdimage.debian.org`.
    - **Paths:** `--image-dir` must be an absolute path of `[A-Za-z0-9._/-]` with no `..`.
35. **The VM runs the commit the operator reviewed.** The default `--ref` is the branch the host's
    checkout is on, and `BUDGETOWL_COMMIT` is that checkout's `HEAD`. The bootstrap refuses to
    build if `git rev-parse HEAD` differs after cloning. `--commit SHA` pins another commit;
    `--no-commit-check` skips the check, for a ref chosen deliberately.
36. Refuses an existing VMID (a VM or a container) **before** anything is downloaded or written,
    the snippet included. Every argument is validated against a strict pattern. `--dry-run`
    prints every command; `--print-user-data` prints the snippet.
37. The VM has virtio-scsi-single, the guest agent, `onboot=1`, a serial console, and defaults of
    2 cores, 4096 MiB and 32 GiB.
38. The cloud-init **user-data snippet holds no secrets**: an SSH **public** key, a user name, a
    git URL, a ref and a commit id. No password is set, and a file containing `PRIVATE KEY` is
    refused.
39. First boot runs `deploy/proxmox/bootstrap-vm.sh`, which:
    - installs Docker Engine and Compose from Debian's own archive (`docker.io`, `docker-cli`,
      `docker-compose` 2.26.1 as a CLI plugin) and `openssh-client`;
    - sets the firewall:
      - its own nftables table, never `flush ruleset`;
      - an **input** chain with policy drop, SSH only;
      - a **raw-priority prerouting** chain that drops anything not from `lo` addressed to
        `127.0.0.0/8` or `::1`. Docker before 28 DNATs a port published on `127.0.0.1` for
        packets from **any** interface, and those packets are forwarded, never input.

      It is loaded with `systemctl reload` or `start`, **never `restart`**: Debian's
      `nftables.service` has `ExecStop=nft flush ruleset`, which wipes Docker's rules. Recovery
      if they are wiped: `systemctl restart docker`;
    - turns on unattended security upgrades;
    - clones the repository and checks the commit (rule 35);
    - generates `.env` on the VM (0600, never replacing one);
    - builds and starts the stack;
    - **makes the signing key** (`/etc/budgetowl/backup-signing-key`, owned by the operator,
      0600), and writes `backup.env` with keep 14/8/12;
    - installs the nightly timer;
    - prints the allowed_signers line for the operator's password manager, and the warning to
      restore before mounting an old share at the timer's path.

## Data model

None. No migration. The scripts read `flyway_schema_history`, `instance_settings.created_at`,
`users`, `households`, `accounts`, `transactions`, `spring_session*` and `auth_tokens`. They write
nothing to the schema except what `pg_restore` and `10-create-app-role.sh` do.

## API

None. `tools/deploy-check.sh` uses `POST /api/setup/first-user`, `POST /api/auth/login`,
`POST /api/auth/token`, `GET`/`POST /api/accounts`, as a client.

## UI

None. Documented for the operator in `userguide/installing.md` (sections 1, 2, 5, 6) and
`userguide/run-it-on-proxmox.md`.

## Security considerations

### Threat model

**Assets.**

- The backup files, which hold the household's entire financial history.
- **The restore path itself**: a restore runs the backup's SQL as the PostgreSQL superuser. A
  forged dump can carry `COPY … TO PROGRAM`, `CREATE ROLE`, or an admin user with the attacker's
  own password hash.
- The signing key.
- The database passwords in `.env`.
- The live database.
- On Proxmox, the hypervisor.

**Actors.**

- Someone who can **read or write the backup share**: a NAS on the LAN, as the guide suggests.
- Someone who gets a backup disk.
- Another local user on the server.
- A machine on the VM's network segment.
- The operator on a bad day.
- Whoever can tamper with a download or a git branch.

| # | Threat | Control | Where |
| - | ------ | ------- | ----- |
| T1 | **A backup is read by someone it was not meant for** | `0600` files; a `0700` folder whose parents others cannot write; `umask 077`; optional age/gpg encryption to a public key, with the plain dump never on disk; no private key allowed in the folder; a warning on network filesystems | rules 8, 9, 11 |
| T1b | **Safety and pre-upgrade backups written as plaintext** when everything else is encrypted (audit M1) | Every script reads `backup.env`. An `.age` restore encrypts its safety backup to the identity's own key. A plaintext backup into a folder of encrypted ones is refused, and then nothing is replaced | rules 5, 11, 22; deploy-check |
| T2 | **Backups written somewhere nobody thought about** | `--out` is required, with no default; the guide and the bootstrap say `/srv/budgetowl-backups` is on the VM's own disk until real storage is mounted | rule 7 |
| T3 | **A DB password on a command line or in a dump** | Every database command runs inside the container over its socket, with `psql -w`. `pg_dump` has no role passwords. The VM generates `.env`, and test-restore generates its own | rules 2, 6, 39 |
| T4 | **A live credential in a months-old backup**, or a signed-out phone coming back (audit L2) | Session **and** token rows are excluded; everyone signs in again; the guide says to review Devices | rule 3; deploy-check (401 for an old token) |
| T5 | **A restore silently overwrites a live database** | Refuses one with data without `--replace` (exit 4); a safety backup before the swap; the old database is kept as `…_previous` until the app is healthy | rules 19, 22, 23 |
| T6 | **A damaged backup is restored** | Mandatory checksum (exit 3); `pg_restore --list`; one transaction into a scratch database | rules 16, 19, 20 |
| T7 | **A forged backup is restored** (audit H1). Someone who can write to the share writes a dump and its checksum. age does **not** help: anyone can encrypt to the operator's public key, and age does not authenticate the sender. Round 1 claimed it did, and that was wrong | Every backup is signed by a key only the server holds (`ssh-keygen -Y`, namespace `budgetowl-backup`). Restore and test-restore check the signature **over the exact file, before anything reads it, decryption included**, against the operator's allowed signers. Unsigned or badly signed is exit 6; `--allow-unsigned` warns about superuser | rules 12, 15; deploy-check (forged checksum, forged `.age`, borrowed `.sig`, other key) |
| T7b | **The signing key leaks** | It is ed25519, 0600, owned by the operator account, and refused if looser. It is outside the backup folder, and the key scan refuses it if it ever lands there. Only the public line leaves the server. Whoever holds the operator account or root holds the key: ADR-0026 territory | rules 9, 12 |
| T7c | **A rebuilt server cannot vouch for old backups** | The operator keeps each server's allowed_signers line in the password manager and passes it with `--signers`; a file can hold several | rule 15; deploy-check (new key refuses old backup; `--signers` accepts it) |
| T8 | **An upgrade migration fails halfway** | Each Flyway migration is one transaction; earlier ones stay applied; the api stays unhealthy; `upgrade.sh` backed up first and prints the rollback | rules 25–29 |
| T9 | **A downgrade after migrations** | Rollback is code AND backup; a backup newer than the image is refused (exit 5); fail closed if the image cannot be read | rules 21, 30 |
| T10 | **The restored database grants the wrong role** | `--no-privileges`, then the init script; deploy-check asserts `budgetowl_app` after a restore | rule 24 |
| T11 | **The stack is exposed to the network.** **Correction:** round 1 said publishing on `127.0.0.1` plus an input-chain firewall kept it local. On Docker before 28, which is what Debian 13 ships, a machine on the same segment can send packets addressed to `127.0.0.1` and Docker's prerouting DNAT forwards them to the container (audit M2) | Published on `${BIND_ADDRESS:-127.0.0.1}` (verify.sh asserts one port). On the VM, a raw-priority prerouting drop of non-`lo` traffic to `127.0.0.0/8` and `::1`. **A non-VM install with Docker before 28 is exposed the same way** unless its own firewall does this; the guide says so | rule 39; netns test (Testing notes) |
| T12 | **`create-vm.sh` runs as root on the hypervisor** | Every input validated (including `--image-dir` and `--image-base`); no `eval`, no `curl \| bash`; downloads go to `.partial` and are renamed only after the hash matches; `--dry-run`; the VMID is refused before anything is written; no storage or cluster configuration is changed | rules 34–36 |
| T13 | **A tampered cloud image** | HTTPS only; SHA-512 against `SHA512SUMS`; `--image-sha512` with a dated `--image-base`; `gpgv` if a signature ever appears. **Gap:** without a signature, trust rests on TLS to `cloud.debian.org` | rule 34 |
| T14 | **Secrets in the cloud-init snippet** (world-readable in snippets storage) | A public key, a user name, a URL, a ref and a commit id. Nothing else | rule 38 |
| T15 | **The VM reachable on more than SSH.** **Correction:** round 1 claimed "input policy drop, SSH only" covered it; published ports bypass the input chain (T11) | Input drop with SSH only, **plus** the prerouting drop; password SSH off; no root login; `reload`, never `restart`, so Docker's rules are not flushed (audit M3) | rule 39 |
| T16 | **The docker group is root** | Only the operator is in it, and the guide says what that means | rule 39 |
| T17 | **The VM builds code nobody reviewed**: the branch moved between review and first boot (audit L4) | The commit is pinned from the host's checkout and checked after cloning; `upgrade.sh --ref` is validated and passed after `--end-of-options` | rules 26, 35 |
| T18 | **Rotation deletes the real history** (audit M4) | No pruning after an empty install's backup, or across installs; only this install's unlabelled backups are pruned; weekly and monthly tiers; the guide says to restore before mounting an old share at the timer's path, or to stop the timer | rules 13, 14 |
| T19 | **An interrupted restore leaves no live database** (audit L5) | Renames in one transaction; the old one kept until healthy; leftovers detected (exit 7) with the fix printed; "STOPPED" said plainly | rules 18, 23 |

### Audit round 2: what each finding became, and the sabotage that proves it

Each fix was broken on purpose, `tools/deploy-check.sh` (or the named test) was run, and the fix
was restored. "Caught by" is the check that went red.

| Finding | Sabotage | Caught by |
| ------- | -------- | --------- |
| H1 forged backup | restore skips `verify_signature` | "a changed backup with a recomputed checksum is refused on its signature", "... with a real backup's signature beside it", "a backup signed by another key is refused", "the old server's backup is refused by default on the new server", "test-restore.sh refuses a forged backup too" |
| H1 `--allow-unsigned` warning | warning removed | "... and says plainly that it runs as the database superuser" |
| M1 plaintext among encrypted | refusal removed | "a plaintext backup into a folder of encrypted ones is refused", "a restore whose safety backup would be plaintext among encrypted ones replaces nothing" |
| M1 safety backup of an `.age` restore | identity's key not used | "restore.sh --replace from the age backup", "the safety backup is encrypted, like the backup restored" |
| M2 loopback DNAT | prerouting chain removed (round 1's file) | netns test: attacker gets **200** with the input chain only, **000** with the fix; the VM itself still gets 200 |
| M3 flush on restart | not sabotaged | shown instead: Debian 13's `nftables.service` has `ExecStop=/usr/sbin/nft flush ruleset` and `ExecReload=/usr/sbin/nft -f /etc/nftables.conf` (read in the container) |
| M4 empty install prunes | people check removed | "... keeps the older real backup (--keep 1 did not remove it)" |
| M4 other install prunes | instance check removed | "nothing is rotated away when the newest earlier backup is from a different install" |
| M4 tiers | weekly tier keeps nothing | "keeps exactly the newest 2, one a week for 3 weeks, one a month for 4 months" |
| L1 key named `budgetowl-*` | the round-1 name filter put back | "backup refuses a folder holding an age key, even one named budgetowl-backup-key.txt" |
| L2 tokens restored | `auth_tokens` data kept | "... and no phone-token rows", "a phone token from before the backup no longer works" |
| L3 parent folders | parent walk skipped | "backup refuses a folder inside one that others can write to" |
| L3 `pwd -P` | logical `pwd` | "a symlinked --out works": `find -perm` sees the symlink's own 777 |
| L4 reviewed commit | not sabotaged in-tree | shown instead: bootstrap in Debian 13 with a wrong `BUDGETOWL_COMMIT` exits 1 and makes no `.env`; with the right one it proceeds |
| L5 leftovers | detection removed | "restore refuses when the live database is missing but the previous one is there" |
| L5 previous kept until healthy | never dropped | "... and the database from before was dropped once the app was healthy" |
| test-restore's own passwords | caller's env used (round 1's behaviour) | "... and with a database password of its own, not this install's" |
| Same-second backups (found in round 2) | overwrite guard removed | "a second backup with the same name is refused, and the first is not overwritten" |

### Things this does not protect against

- **Anyone with root, the operator account, or the Proxmox host.** They can read the volume, the
  environment, unencrypted backups and the signing key, and so sign a backup of their own.
  ADR-0026 accepts this: the operator owns the box.
- **The restore still runs as the superuser.** Signing decides *whose* SQL runs; it does not
  reduce *what* that SQL can do. See open question 2.
- **The superuser password in the api container's environment** (ADR-0030 correction). Unchanged.

## Edge cases

- **A fresh install that started once** has 32 empty tables and is refused without `--replace`;
  the message says `--replace` is safe there.
- **`DB_APP_PASSWORD` set without `DB_APP_USER`** fails authentication on every start.
  `.env.example` must carry both; this agent cannot edit `.env*`, so it was reported.
- **Backups from before signing** have no `.sig`. They need `--allow-unsigned`, with its warning.
- **A backup with no Flyway history**, or one recording a failed migration, is refused.
- **Disk space**: a restore needs room for two copies of the database until the app is healthy.
- **gpg recipients** must be trusted in the keyring; the script does not override trust.
- **Two restores at once** are not locked against each other. A second one finds `…_scratch` or
  `…_previous` and either reuses the scratch name or stops at exit 7.

## Out of scope

- **Backups in the admin console** (admin-console.md).
- **Point-in-time recovery (WAL archiving).**
- **Automatic rollback** (rule 29).
- **Publishing images.**
- **An LXC recipe** (rule 33).
- **A non-superuser restore**: open question 2. Deliberately not this slice, by the owner's
  decision.

## Open questions

1. **Publishing images to ghcr.io** and signing them (cosign). Owner.
2. **Restore as a non-superuser owner (follow-up, recorded by owner decision).** ADR-0030's
   correction says the owner is a superuser, so a restore can run `COPY … TO PROGRAM`, create
   roles and so on. Signing (T7) limits that to backups this server made. Demoting the owner to a
   non-superuser role, created by the init script and owning the database, would close it
   outright. It would also take the superuser credentials out of the api container. It needs its
   own ADR, because it changes how Flyway connects and how existing installs are upgraded.
3. **A release branch or tags.** There are none. Until PR #7 merges, `main` does not contain
   these scripts: clone the working branch and use it as `--ref` (the guide shows how).
   `create-vm.sh` defaults to whatever branch the host's checkout is on.
4. **Signing key rotation.** There is no command for it; a new key means keeping both lines in
   the signers file. Cheap to add when someone needs it.

## Testing notes

**Proven in the dev container on 2026-11-28**: `tools/deploy-check.sh` passed 68 of 68, against
images built from the merged branch. The sabotage table above lists each check made to fail.

**Measured** (unchanged from round 1):
- **Memory at rest:** api about 490 MiB, db about 77 MiB, web about 5 MiB.
- **Disk:** 65 MB for a household with two accounts. A backup is 168 KB.
- **Time:** one run of the check takes about 2 min 10 s.

**The loopback-DNAT test (M2)** ran in a privileged `debian:trixie` container with three network
namespaces: an attacker, the VM and a container.
- **The setup:** a DNAT rule in the VM stands in for Docker's. Like Docker before 28, it takes
  `127.0.0.1:8080` from any interface. The attacker routes `127.0.0.1` to the VM's address on
  the shared segment.
- **No firewall:** HTTP 200.
- **Round 1's input-only firewall:** HTTP 200.
- **The firewall `bootstrap-vm.sh` writes now:** refused. The VM itself still got 200 through
  loopback.
- **Loading it twice** left one `budgetowl` table and the Docker-like table intact.
- **Not tested:** real Docker 26 on a real network segment. The DNAT rule is an nftables
  equivalent of Docker's iptables rule, not Docker's own.

**The bootstrap** ran in `debian:trixie`, with `NET_ADMIN`:
- **The commit check:** a wrong `BUDGETOWL_COMMIT` was refused (exit 1, no `.env`); the right one
  went ahead.
- **The signing key:** 0600, owned by `owl`, and passes `check_private_file` as `owl`.
  `backup.env` reads back through `load_backup_env`.
- **The rest:** the firewall loaded; `systemd-analyze verify` passed; a second run left the
  `.env`, the key and the single table unchanged.
- **The snippet:** passes `cloud-init schema`, embeds the bootstrap byte for byte, and holds no
  secrets.

**Still not tested:**
- `qm`, `pvesm`, `qm importdisk` and a real cloud-init first boot on Proxmox VE.
- The systemd timer firing on a real VM.
- The Proxmox flags were written from general knowledge; `pve.proxmox.com` was not reachable from
  the sandbox.

**Sandbox image builds** (as in round 1): built from the same Dockerfiles with the proxy's CA
mounted, the `apk upgrade` layer skipped, and the jar built on the host. CI builds the real
images and runs the same check.

**Found by testing, and fixed**:
- **Round 1:** Debian's `docker.io` only *Recommends* `docker-cli`; Compose 2.26 rejects
  `run --pull never`; `DB_APP_PASSWORD` without `DB_APP_USER` fails every start.
- **Round 2:**
  - `test-restore.sh` exited silently with status 2 on a machine with no `.env` (`sed` under
    `pipefail`).
  - The deploy check's own `grep -q` on a listing failed its pipe under `pipefail`.
  - **Two backups in the same second shared a name, and the second silently replaced the
    first.** It surfaced as one intermittent failure inside `verify.sh`, in the rotation step,
    which takes two backups back to back. It is now refused (rule 10), and tested
    deterministically with a `date` that never moves.
