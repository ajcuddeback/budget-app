# Installing Budget Owl

Budget Owl runs on your own hardware. You will need a machine that stays on — a home server, a
NAS, a mini PC, a VPS — with Docker installed.

You do not need to be an expert. If you have run anything with Docker Compose before, this will
be familiar; if you have not, the steps below are meant to be followable anyway.

**On Proxmox?** [Run it on Proxmox](run-it-on-proxmox.md) has a script that makes the VM and does
sections 1 and 2 for you. Come back here for section 3 onwards.

## 1. What you need

- **Linux with Docker Engine and the Docker Compose plugin.** Check both with:

  ```bash
  docker --version
  docker compose version
  ```

  You need `docker compose` (with a space), which is version 2 or later. Budget Owl has been
  tested on Docker 26.1.5 with Compose 2.26.1, which is what Debian 13 installs from its own
  packages (`apt install docker.io docker-cli docker-compose docker-buildx`), and on Compose
  5.6.1. The older `docker-compose` (with a dash, version 1) does not work.
- **git**, to get Budget Owl and to update it later.
- **Memory.** Running, Budget Owl uses about 600 MB: we measured about 490 MB for the app, 77 MB
  for the database and 5 MB for the web server. Building it the first time needs more, because
  it compiles the app on your machine. Give it **4 GB** if you can. 2 GB is enough once it is
  built.
- **Disk.** About 1 GB for the three images, and up to 2 GB more that Docker keeps from the build
  (`docker builder prune` frees that afterwards). A new database is 65 MB, and a backup of a
  household with a few accounts is under 1 MB. **Allow 10 GB free; 20 GB is comfortable.**
- **Somewhere else to keep backups**: a NAS share, a USB disk, another machine. Section 5 is about
  this. Decide now, because a backup on the same disk as the database is lost with it.
- **Whether anyone will use it from outside this machine.** As installed it answers only on the
  machine itself. For anything else, including the phone app, you will give it an HTTPS
  address (section 4).

## 2. Get it running

1. Get Budget Owl.

   ```bash
   git clone https://github.com/ajcuddeback/budget-app.git budget-owl
   cd budget-owl
   ```

   Every command from here on runs in this `budget-owl` folder.

2. Make your settings file, readable only by you.

   ```bash
   cp .env.example .env
   chmod 600 .env
   ```

3. Make two passwords, one for each line below.

   ```bash
   openssl rand -base64 32
   openssl rand -base64 32
   ```

   Each prints a long random password, like `q3Vx…` (yours will differ).

4. Open `.env` in an editor and set these three lines, pasting one password into each
   `PASSWORD` line:

   ```bash
   DB_PASSWORD=<the first password>
   DB_APP_USER=budgetowl_app
   DB_APP_PASSWORD=<the second password>
   ```

   **Set `DB_APP_USER` and `DB_APP_PASSWORD` together, or neither.** With only the password set,
   the app fails every start with `password authentication failed for user "budgetowl"`.

   **Keep a copy of `.env` in your password manager.** You do not need it to restore a backup,
   but it is the only copy of the passwords for this install.

5. Build Budget Owl. This takes several minutes the first time.

   ```bash
   docker compose build
   ```

   It finishes with lines saying the `api` and `web` images were built.

   Budget Owl's own images are not published for download yet, so `docker compose pull` fails
   for them; build instead.

6. Start it.

   ```bash
   docker compose up -d
   ```

7. Check that it is running.

   ```bash
   docker compose ps --format '{{.Service}}   {{.Status}}'
   ```

   After about a minute, all three services say `Up` and `(healthy)`:

   ```
   api   Up About a minute (healthy)
   db    Up About a minute (healthy)
   web   Up About a minute (healthy)
   ```

   If `api` says `Restarting`, see [Things that commonly go wrong](#things-that-commonly-go-wrong).

8. Open `http://localhost:8080` in a browser on the same machine.

   You see **Set up Budget Owl**. Continue with section 3.

### The settings in `.env`

You only need the three lines in step 4. Everything else has a default that is right for most
installs.

| Setting               | Default         | What it is                                                                                                                                                                  |
| --------------------- | --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DB_PASSWORD`         | _none_          | **Required.** The password of the database's owner. Budget Owl will not start without it                                                                                    |
| `DB_APP_USER`         | `budgetowl_app` | The name the app signs in to the database with. Set it together with `DB_APP_PASSWORD`                                                                                      |
| `DB_APP_PASSWORD`     | _none_          | Strongly recommended. With it, the running app uses a database login that can read and write your data but cannot change or empty the tables. Without it, the app uses the owner's |
| `WEB_PORT`            | `8080`          | The port Budget Owl answers on                                                                                                                                              |
| `BIND_ADDRESS`        | `127.0.0.1`     | The address it answers on. Leave it: `127.0.0.1` means this machine only. Give it an HTTPS address instead (section 4)                                                      |
| `DB_NAME`, `DB_USER`  | `budgetowl`     | The database's name and its owner's name. Leave them                                                                                                                         |
| `BUDGET_OWL_VERSION`  | `latest`        | The name your built images are tagged with. Leave it                                                                                                                         |

`DB_APP_USER` and `DB_APP_PASSWORD` only take effect on a **new** database. If you add them to an
install that is already running, also run these once:

```bash
docker compose up -d db
docker compose exec db /docker-entrypoint-initdb.d/10-create-app-role.sh
docker compose up -d
```

Other settings you may read about, such as `SESSION_COOKIE_SECURE`, are not passed to the app by
`docker-compose.yml`. Setting them in `.env` changes nothing.

## 3. Create the first account

Open Budget Owl in a web browser at the address of your server. A new instance shows **Set up
Budget Owl**.

1. Fill in **Your name**, **Email address**, **Password** (at least 12 characters) and **Household
   name**.

2. Select **Create account**.

   You are signed in and land on the **Household** screen. Your account is the **owner** of a new
   household.

![The Set up Budget Owl screen with an empty form and a notice that you will be able to see everything anyone in your household records.](images/create-your-account--setup-form.png)

**You are now the person who runs this instance, and you can see everything.** Every transaction,
every balance and every note anyone in your household records is stored on your server, and you
can see all of it. Budget Owl does not hide it from you. Anyone you invite is told this before
they join; it is worth telling them yourself too.

This screen only appears on a new instance. After the first account exists, everyone else joins
by invitation: see [Invite someone to your household](features/invite-someone.md). The full
walkthrough is in [Create your account and household](features/create-your-account.md).

## 4. Reach it from your phone

The Budget Owl phone app connects only over `https://`, with a certificate the phone trusts. As
installed, your server answers only at `http://localhost:8080` on the machine itself, so give it an
HTTPS address first: [Give your server an HTTPS address](give-your-server-an-https-address.md)
covers three ways, from a Tailscale name to your own certificate authority.

Then make the app for your household's phones and help them connect:
[Use Budget Owl on your phone](features/use-budget-owl-on-your-phone.md), whose last section is
for you.

## 5. Back it up

Nobody else has a copy of your data. If this machine's disk dies and you have no backup, every
transaction your household has recorded is gone.

**A backup holds everything**: every account, every transaction, every note, who is in your
household, and the settings for signing in. Treat the file like the paper statements from every
account you have, all in one envelope.

### What to back up

The database, which `deploy/backup.sh` saves as a single file. Nothing else in Budget Owl holds
your data: a bank statement you import is read and then thrown away, never stored.

You do **not** need `.env` to restore. A restore onto a new install with new passwords works. Keep
a copy of `.env` in your password manager anyway (section 2).

### Take a backup

1. Choose a folder that is **not on the same disk as Budget Owl**: a NAS share or a USB disk
   mounted on this machine, for example `/mnt/backups/budgetowl`.

2. Run:

   ```bash
   deploy/backup.sh --out /mnt/backups/budgetowl
   ```

   You see:

   ```
   Backing up to /mnt/backups/budgetowl/budgetowl-20261127T200414Z-2e1391f-schema20.dump
   Done: 168K, schema version 20, checksum in budgetowl-20261127T200414Z-2e1391f-schema20.dump.sha256
   Not encrypted. Anyone who can read this file can read every transaction in it.
   ```

   The name says when it was taken (in UTC), which version of Budget Owl took it, and which
   version of the database it holds. The `.sha256` file beside it is how a restore knows the
   backup has not been damaged or changed. Keep the two together.

   Both files can be read by your user only. The script refuses a folder that other people on the
   machine can write to, because they could swap a backup for one of their own.

### Encrypt your backups

Do this if the backups go anywhere other people could reach: a shared NAS, a USB disk that could
be lost, a cloud folder.

1. **On a different machine from the server** (your own laptop, say), install `age` and make a key:

   ```bash
   age-keygen -o budgetowl-backup-key.txt
   ```

   It prints a line starting `Public key: age1…`. That public key is safe to put anywhere.

2. Put `budgetowl-backup-key.txt` somewhere safe and **not on the server or the backup disk**: a
   password manager, or a USB stick in a drawer. Anyone with this file can read every backup.
   Without it, **nobody can, including you**. Lose it and the encrypted backups are useless.

3. On the server, install `age` (`sudo apt install age`) and back up to the public key:

   ```bash
   deploy/backup.sh --out /mnt/backups/budgetowl --age-recipient age1…
   ```

   The backup ends in `.dump.age`. The unencrypted copy is never written to disk.

The script refuses to write into a folder that holds a private key, because then the encryption
protects nothing. If you use gpg instead, `--gpg-recipient` takes a key from your keyring.

### Back up every night

As your own user (who must be in the `docker` group), make these two files, changing `/home/you/budget-owl`,
`you` and the backup folder to yours.

`/etc/systemd/system/budgetowl-backup.service`:

```ini
[Unit]
Description=Budget Owl database backup
Wants=docker.service
After=docker.service

[Service]
Type=oneshot
User=you
WorkingDirectory=/home/you/budget-owl
Environment=BACKUP_DIR=/mnt/backups/budgetowl
Environment=BACKUP_KEEP=14
# Environment=BACKUP_AGE_RECIPIENT=age1…
ExecStart=/home/you/budget-owl/deploy/backup.sh
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes
```

`/etc/systemd/system/budgetowl-backup.timer`:

```ini
[Unit]
Description=Nightly Budget Owl database backup

[Timer]
OnCalendar=*-*-* 03:17:00
RandomizedDelaySec=20m
Persistent=true

[Install]
WantedBy=timers.target
```

Then turn it on:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now budgetowl-backup.timer
systemctl list-timers budgetowl-backup.timer      # shows when it runs next
```

`BACKUP_KEEP=14` keeps the newest 14 nightly backups and deletes older ones. It never deletes
the backups taken before an upgrade or a restore. If you prefer cron, this line in `crontab -e`
does the same:

```
17 3 * * * cd /home/you/budget-owl && deploy/backup.sh --out /mnt/backups/budgetowl --keep 14 >/dev/null
```

### Restore from a backup

**On a new machine** (the old one died):

1. Do section 1 and section 2 up to step 5. Install and build, but **do not start it**.

2. Start only the database:

   ```bash
   docker compose up -d db
   ```

3. Restore, with the `.sha256` file next to the backup:

   ```bash
   deploy/restore.sh /mnt/backups/budgetowl/budgetowl-20261127T200414Z-2e1391f-schema20.dump
   ```

   For an encrypted backup, add `--age-identity budgetowl-backup-key.txt`.

   You see:

   ```
   Checksum matches.
   Restoring into a scratch database...
   The backup holds schema 20: 1 people, 1 household, 1 accounts, 0 transactions.
   ADR-0030: role 'budgetowl_app' is ready; it can read and write rows and cannot change the schema.
   Restored: 1 people, 1 household, 1 accounts, 0 transactions.
   Starting Budget Owl...
   Budget Owl is running on the restored data.
   ```

4. Open Budget Owl and sign in with the email and password you had before. Everyone is signed
   out of the browser after a restore; phones stay signed in.

**Over an install that has data in it**, restore refuses:

```
error: the database already has Budget Owl in it (32 tables). Restoring would replace
       everything in it. Nothing was changed.
```

If replacing everything is what you want, add `--replace`. Restore then saves what is there now as
a safety backup (its name ends `-pre-restore.dump`) before it replaces anything. This also applies
to a new install that you started once by mistake: `--replace` is safe there.

**A restore never half-happens.** It checks the backup's checksum first:

```
error: CHECKSUM MISMATCH: … is not the file its checksum was made from.
```

It then restores into a separate database, and it only swaps that in once the restore has
finished and been checked. If anything fails before then, your current data is untouched and
Budget Owl starts again on it. It also refuses a backup made by a **newer** version of Budget Owl
than the one installed (see section 6).

### Test your backups

A backup you have never restored is a hope. Once a month, or after you change anything about
backups:

1. Restore the newest backup into a separate copy, beside your real one:

   ```bash
   deploy/test-restore.sh /mnt/backups/budgetowl/<newest>.dump
   ```

   Your real Budget Owl keeps running. The copy answers at `http://localhost:8481`.

2. Open `http://localhost:8481`. Sign in, and check that your accounts and last week's
   transactions are there.

3. Remove the copy:

   ```bash
   deploy/test-restore.sh --clean
   ```

   You see `The test copy and its database are gone. Your real instance was not touched.`

## 5b. Run the instance

Budget Owl keeps one screen for the person who runs the server: **Admin**, at the end of the row
of links across the top. It appears only for the account that completed **Set up Budget Owl**, and
it covers three things:

- **This instance** — the version running, when the server was set up, who looks after it, and how
  many accounts exist.
- **Signing in** — whether anyone may create an account, whether people sign in with an email
  address and a password, and whether they can sign in through a provider you already run.
- **Accounts** — everybody who can sign in. Read-only: suspending or removing an account is not
  something this screen does.

Nothing else an operator does lives in the app. Backups, upgrades, logs and the database are all
done on the machine, because the machine is yours.

Full steps: [See who can sign in to your server](features/see-who-can-sign-in.md) and
[Choose how people sign in](features/choose-how-people-sign-in.md).

**Two things on that screen surprise people, so they are worth knowing before you meet them.**
Email and password sign-in cannot be switched off until somebody has actually signed in through
your own provider — proving the new way in before hiding the old one is what stops you being
locked out. And replacing the client secret for that provider switches email and password sign-in
back on, because a credential nobody has used yet is a credential nobody has proved.

## 6. Keep it up to date

A new version of Budget Owl arrives as new code in the repository you cloned. You build it on
your machine, because Budget Owl's images are not published for download yet.

### What happens to your data in an upgrade

When the new version starts, it updates the database to match, one step at a time. Each step
either finishes completely or is undone. The app only reports itself healthy once every step has
finished.

These steps only go forwards. An older version of Budget Owl cannot use a database that a newer
one has updated. **That is why you always back up first, and why going back always means
restoring that backup.**

### Upgrade

1. Run:

   ```bash
   deploy/upgrade.sh --backup-dir /mnt/backups/budgetowl
   ```

   Add `--age-recipient age1…` if you encrypt your backups.

   It backs up (and stops if the backup fails), fetches the new code, builds it, starts it, and
   waits until it is healthy. When it finishes you see something like this (here there was no
   new version yet, so the before and after match):

   ```
   Upgraded: 2e1391f (schema 20) -> 2e1391f (schema 20).
   Pre-upgrade backup: /mnt/backups/budgetowl/budgetowl-…-schema20-pre-upgrade.dump

   To go back to the version you had (commit 2e1391f, schema 20):
     git checkout 2e1391f
     docker compose build
     deploy/restore.sh --replace /mnt/backups/budgetowl/budgetowl-…-schema20-pre-upgrade.dump
   ```

   Keep those three lines until you are sure the new version is working.

2. Open Budget Owl and check that your data is there.

To move to a particular version rather than the newest, add `--ref` and the version's name, for
example `--ref v0.2.0`. If you would rather do each step yourself, this is what the script does:

```bash
deploy/backup.sh --out /mnt/backups/budgetowl --label pre-upgrade
git pull --ff-only
docker compose build --pull
docker compose pull db
docker compose up -d
docker compose ps --format '{{.Service}}   {{.Status}}'    # wait for api (healthy)
```

### If an upgrade goes wrong

If the app does not become healthy, `deploy/upgrade.sh` says so and prints the same three lines.
See why with:

```bash
docker compose logs api | tail -50
```

To go back, run the three lines it printed, **all three, in that order**. They put back the code
you had, build it, and restore the backup taken just before the upgrade. Anything recorded after
the upgrade is lost, so go back promptly.

Do not check out the old code alone. The old version may start on the updated database
without complaint and then misbehave, and nothing tells you.

`deploy/restore.sh` refuses to restore a backup that a newer version made:

```
error: this backup was made by a NEWER Budget Owl (schema 21); the version here only
       knows up to schema 20.
```

If you see this, check out the version that made the backup first. Its commit is in the backup's
file name.

### Keep the machine up to date too

Budget Owl's upgrades do not update Docker or the operating system. On Debian or Ubuntu, turn on
automatic security updates with `sudo apt install unattended-upgrades`. Each upgrade fetches the
newest patch release of PostgreSQL 16 (`docker compose pull db`) and of the images Budget Owl is
built on (`docker compose build --pull`).

## Things that commonly go wrong

### Nobody can sign in

If you switched Budget Owl over to your own login provider and that provider has since stopped
working, the sign-in page has nothing left to offer you. The way back in doesn't need a browser.

On the machine running Budget Owl, from the folder holding your `docker-compose.yml`:

```bash
deploy/restore-password-login.sh --dry-run   # see what it would change
deploy/restore-password-login.sh             # turn email and password sign-in back on
```

Then sign in with your email address and password as you did before. Your login provider is left
exactly as it was, so you can fix it and hide password sign-in again afterwards, from **Admin** in
the app.

It's safe to run when you aren't sure that's the problem: if password sign-in is already on, it
says so and changes nothing.

### The app keeps restarting: "password authentication failed"

`docker compose ps` shows `api` as `Restarting`, and `docker compose logs api` contains
`password authentication failed for user "budgetowl"`.

`.env` has `DB_APP_PASSWORD` but no `DB_APP_USER`. Add `DB_APP_USER=budgetowl_app` and run
`docker compose up -d`. If the database was created before you added either line, also run the
three commands under [The settings in `.env`](#the-settings-in-env).

### `docker compose pull` fails for `api` and `web`

Budget Owl's images are not published for download yet. Build them with `docker compose build`.

### A backup or restore says the `db` service is not running

Both start it if they need to. If it will not start, `docker compose logs db` says why. The
most common reason is a disk that is full.

### Everything else

Write down what you ran and what it printed, and open an issue. Leave out your `.env` and your
backups.
