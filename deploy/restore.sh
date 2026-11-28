#!/usr/bin/env bash
# Restore Budget Owl's database from a backup made by deploy/backup.sh.
#
#   deploy/restore.sh BACKUP                       # onto a new, empty install
#   deploy/restore.sh BACKUP --replace             # over a database that has data in it
#   deploy/restore.sh BACKUP.age --age-identity ~/budgetowl-backup-key.txt
#   deploy/restore.sh BACKUP --signers old-server.allowed_signers   # made by another server
#
# Options:
#   --replace                 allow replacing a database that already has Budget Owl in it.
#                             A safety backup of what is there now is taken first.
#   --signers FILE            the allowed_signers line(s) of the server(s) whose backups you
#                             trust (default: this server's own, from its signing key).
#   --allow-unsigned          restore a backup with no valid signature. ONLY for a file you
#                             know you made yourself: its contents run as the database superuser.
#   --age-identity FILE       the age PRIVATE key that decrypts an .age backup. Read once, so
#                             --age-identity <(cat /media/usb/key.txt) works.
#   --safety-out DIR          where the safety backup goes (default: beside BACKUP).
#   --safety-age-recipient K  encrypt the safety backup to this age public key,
#   --safety-gpg-recipient K  ...or to this gpg key. Default: like the backup being restored
#                             (the identity's own public key for an .age), else as backup.env says.
#   --no-start                leave the app stopped afterwards.
#   --skip-version-check      restore even if this version's app image cannot be read to check
#                             that the backup is not newer than it. Avoid.
#   -h, --help                this text.
#
# What it does, in order. Nothing that is there now is changed until step 8.
#   1. checks BACKUP's signature against the signers you trust — before reading anything in it;
#   2. checks BACKUP against BACKUP.sha256;
#   3. checks for a database left behind by an interrupted restore;
#   4. checks pg_restore can read it;
#   5. refuses if the database already holds Budget Owl, unless you gave --replace;
#   6. stops the app, and restores into a separate scratch database;
#   7. checks what came back: a Budget Owl schema, not newer than this version understands;
#   8. with --replace, takes a safety backup; then, in ONE transaction, renames the live database
#      to budgetowl_restore_previous and the restored one into its place;
#   9. re-applies the application role's grants (ADR-0030), starts the app, and drops the
#      previous database only once the app is healthy on the restored one.
#
# Exit codes: 2 usage, 3 checksum, 4 has data (no --replace), 5 newer than this version,
# 6 signature, 7 left over from an interrupted restore, 1 anything else.

# Database commands are single-quoted on purpose: the CONTAINER's shell expands them (lib.sh).
# shellcheck disable=SC2016
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"
load_backup_env

SCRATCH=budgetowl_restore_scratch
PREVIOUS=budgetowl_restore_previous

go_back() {
  log "The database from before the restore is kept as $PREVIOUS. To go back to it:"
  log "  docker compose stop $API_SERVICE $WEB_SERVICE"
  log "  docker compose exec db psql -U <DB_USER> -d postgres \\"
  log "    -c 'ALTER DATABASE <DB_NAME> RENAME TO budgetowl_restore_failed' \\"
  log "    -c 'ALTER DATABASE $PREVIOUS RENAME TO <DB_NAME>'"
  log "  docker compose up -d"
}

backup=""
replace=0
identity=""
signers=""
allow_unsigned=0
safety_out=""
safety_args=()
start=1
skip_version_check=0

while [ $# -gt 0 ]; do
  case "$1" in
    --replace) replace=1; shift ;;
    --signers) signers="${2:?--signers needs a file}"; shift 2 ;;
    --allow-unsigned) allow_unsigned=1; shift ;;
    --age-identity) identity="${2:?--age-identity needs a file}"; shift 2 ;;
    --safety-out) safety_out="${2:?--safety-out needs a directory}"; shift 2 ;;
    --safety-age-recipient) safety_args=(--age-recipient "${2:?needs a key}"); shift 2 ;;
    --safety-gpg-recipient) safety_args=(--gpg-recipient "${2:?needs a key}"); shift 2 ;;
    --no-start) start=0; shift ;;
    --skip-version-check) skip_version_check=1; shift ;;
    -h|--help) print_help "$0"; exit 0 ;;
    -*) die 2 "unknown option: $1 (see --help)" ;;
    *) [ -z "$backup" ] || die 2 "one backup at a time"; backup="$1"; shift ;;
  esac
done

[ -n "$backup" ] || die 2 "say which backup to restore (see --help)"
[ -f "$backup" ] || die 2 "no such file: $backup"
backup="$(cd "$(dirname "$backup")" && pwd -P)/$(basename "$backup")"
case "$backup" in *.partial|*.sha256|*.sig|*.meta) die 2 "that is not a backup: $backup" ;; esac
[ -n "$safety_out" ] || safety_out="$(dirname "$backup")"

# 1. The signature, over the exact bytes of the file, before anything reads what is inside it.
#    A restore runs the backup's SQL as the database superuser; a file anyone could have written
#    is a way in, and a checksum beside it (or an age file, which anyone can encrypt to your
#    public key) proves nothing about who made it.
if [ "$allow_unsigned" -eq 1 ]; then
  log "WARNING: --allow-unsigned. This backup's signature is NOT being checked. Restoring runs"
  log "         everything in it as the database SUPERUSER. Only go on with a file you know you"
  log "         made yourself and that nobody else could have changed."
else
  require_ssh_keygen
  if [ -z "$signers" ]; then
    signers="$(signing_key_path).allowed_signers"
    [ -r "$signers" ] || die 6 "there is no signing key on this server to check the backup against.
       Give the allowed_signers line of the server that made it: --signers FILE
       (backup.sh --print-signing-key prints it; keep it in your password manager)."
  fi
  [ -r "$signers" ] || die 2 "cannot read $signers"
  [ -f "$backup.sig" ] || die 6 "the backup has no signature ($backup.sig), so there is no telling
       who made it. Nothing was restored, and nothing was changed.
       (If you made it yourself before backups were signed: --allow-unsigned.)"
  verify_signature "$backup" "$signers" || die 6 "BAD SIGNATURE: $backup was not signed by a
       server in $signers, or has been changed since. Nothing was restored, and nothing
       was changed. A backup from another server needs that server's line: --signers FILE"
  log "Signature good: made by a server in $signers."
fi

# 2. The checksum. Read from the .sha256 file but compared by hash alone, so a renamed backup
#    still verifies and a swapped one does not.
sumfile="$backup.sha256"
[ -f "$sumfile" ] || die 3 "no checksum beside the backup ($sumfile). Without one there is no way
       to tell a damaged file from a good one, so this will not restore it."
expected=$(awk 'NR==1 {print $1}' "$sumfile")
actual=$(sha256sum < "$backup" | awk '{print $1}')
if [ "$expected" != "$actual" ]; then
  die 3 "CHECKSUM MISMATCH: $backup is not the file its checksum was made from.
       It is damaged or has been changed. Nothing was restored, and nothing was changed."
fi
log "Checksum matches."

# The age key is read ONCE, into memory, so a process substitution works and the file can be
# put away (or deleted) as soon as this starts.
identity_key=""
case "$backup" in
  *.age)
    command -v age >/dev/null 2>&1 || die 1 "this backup is encrypted with age; install age"
    [ -n "$identity" ] || die 2 "this backup is encrypted: give the private key with --age-identity FILE"
    identity_key=$(cat -- "$identity") || die 2 "cannot read $identity"
    grep -q 'AGE-SECRET-KEY-' <<<"$identity_key" || die 2 "$identity is not an age private key"
    # The safety backup of what is replaced is encrypted to the same key, so a restore of an
    # encrypted backup never leaves an unencrypted one behind.
    [ ${#safety_args[@]} -gt 0 ] \
      || safety_args=(--age-recipient "$(age-keygen -y <(printf '%s\n' "$identity_key"))") ;;
  *.gpg)
    command -v gpg >/dev/null 2>&1 || die 1 "this backup is encrypted with gpg; install gpg" ;;
esac

stream() { # the backup as a stream; the decrypted dump is never written to disk
  case "$backup" in
    *.age) age -d -i <(printf '%s\n' "$identity_key") -- "$backup" ;;
    *.gpg) gpg --quiet --decrypt -- "$backup" ;;
    *) cat -- "$backup" ;;
  esac
}

require_docker
ensure_db_up

# 3. Anything an interrupted restore left behind is somebody's data. Say where, and stop.
read -r has_target has_scratch has_previous < <(db_admin_sql <<SQL | tr '|' ' '
SELECT (SELECT count(*) FROM pg_database WHERE datname = :'target'),
       (SELECT count(*) FROM pg_database WHERE datname = '$SCRATCH'),
       (SELECT count(*) FROM pg_database WHERE datname = '$PREVIOUS');
SQL
)
if [ "$has_target" -eq 0 ]; then
  if [ "$has_previous" -gt 0 ]; then
    die 7 "Budget Owl's database is missing, and the one from before an interrupted restore is
       still here as $PREVIOUS. Put it back, then start the app:
         docker compose exec db psql -U <DB_USER> -d postgres -c 'ALTER DATABASE $PREVIOUS RENAME TO <DB_NAME>'
         docker compose up -d"
  elif [ "$has_scratch" -gt 0 ]; then
    die 7 "Budget Owl's database is missing; an interrupted restore left its data in $SCRATCH.
       Check it, then: ALTER DATABASE $SCRATCH RENAME TO <DB_NAME>;  and docker compose up -d"
  fi
  die 1 "Budget Owl's database does not exist in the '$DB_SERVICE' container."
fi
if [ "$has_previous" -gt 0 ]; then
  die 7 "$PREVIOUS is still here: the database from before an earlier restore, kept because the
       app was not confirmed healthy afterwards. If the restored data is right, drop it:
         docker compose exec db psql -U <DB_USER> -d postgres -c 'DROP DATABASE $PREVIOUS'
       Nothing was changed."
fi

# 4. Can pg_restore read it at all?
stream | db_sh 'pg_restore --list >/dev/null; s=$?; cat >/dev/null; exit $s' \
  || die 1 "pg_restore cannot read this backup (wrong key, or not a Budget Owl backup). Nothing was changed."

# 5. Is there something here to lose?
tables=$(db_psql "" -At -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
if [ "$tables" -gt 0 ] && [ "$replace" -ne 1 ]; then
  die 4 "the database already has Budget Owl in it ($tables tables). Restoring would replace
       everything in it. Nothing was changed.

       If that is what you want, run this again with --replace. A safety backup of what is
       there now is taken first, into $safety_out.

       (A brand-new install that has been started once has empty tables, and this applies to it
       too: --replace is safe there, and the safety backup will simply be small.)"
fi

# 6. Stop what writes to the database, remembering what was running so a failure puts it back.
was_running=()
for svc in "$API_SERVICE" "$WEB_SERVICE"; do
  if service_running "$svc"; then was_running+=("$svc"); fi
done
swapped=0
cleanup() {
  status=$?
  if [ "$swapped" -eq 0 ]; then
    db_admin_sql <<<"DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE);" >/dev/null 2>&1 || true
    if [ ${#was_running[@]} -gt 0 ] && [ "$status" -ne 0 ]; then
      log "Starting ${was_running[*]} again on the database it had."
      dc start "${was_running[@]}" >/dev/null 2>&1 \
        || log "Budget Owl is STOPPED and did not start again. Start it: docker compose up -d"
    fi
  elif [ "$status" -ne 0 ]; then
    log "The restore stopped after the restored database was swapped in."
    log "Budget Owl may be STOPPED. Check with: docker compose ps"
    go_back
  fi
}
trap cleanup EXIT

if [ ${#was_running[@]} -gt 0 ]; then
  log "Stopping ${was_running[*]} while the restore runs..."
  dc stop "${was_running[@]}" >/dev/null 2>&1
fi

# The scratch database. Its name is fixed (not derived from anything you typed) so the cleanup
# above can only ever drop this one.
db_admin_sql >/dev/null <<SQL
DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE);
CREATE DATABASE $SCRATCH;
SQL

log "Restoring into a scratch database..."
# --single-transaction + --exit-on-error: all of it, or none of it.
# --no-owner: objects belong to whoever this install's owning role is, even if it has a
#   different name from the one that made the backup.
# --no-privileges: the backup's grants name a role that may not exist here. Step 9 re-applies
#   them from the same script that created them, so they always match this install.
stream | db_sh 'exec pg_restore --no-owner --no-privileges --exit-on-error --single-transaction \
                  -w -U "$POSTGRES_USER" -d budgetowl_restore_scratch' \
  || die 1 "the restore failed. The database you had is untouched."

# 7. Is it a Budget Owl database, and one this version can run?
restored=$(schema_version "$SCRATCH")
[ -n "$restored" ] || die 1 "the backup holds no Budget Owl schema history. Nothing was changed."
failed=$(db_psql "$SCRATCH" -At -c "SELECT count(*) FROM flyway_schema_history WHERE NOT success")
[ "$failed" -eq 0 ] || die 1 "the backup records $failed failed migration(s); it was taken from a
       broken database. Choose an earlier backup. Nothing was changed."
shipped=$(image_schema_version)
if [ -z "$shipped" ] && [ "$skip_version_check" -eq 1 ]; then
  log "warning: --skip-version-check: not checking the backup against this version's migrations."
elif [ -z "$shipped" ]; then
  # Closed, not open: restoring a newer backup under older code is the downgrade that cannot be
  # undone, so "could not tell" is a refusal.
  die 5 "could not read which migrations this version's app image ships, so cannot tell whether
       this backup (schema $restored) is newer than it. Build the images first
       (docker compose build), or, if you are sure, run again with --skip-version-check.
       Nothing was changed."
elif [ "$restored" -gt "$shipped" ]; then
  die 5 "this backup was made by a NEWER Budget Owl (schema $restored); the version here only
       knows up to schema $shipped. Migrations cannot be undone, so this version cannot run it.
       Check out the version that made the backup (its name is in the file name), build it,
       and restore again. Nothing was changed."
fi

summary() { # summary <database>
  db_psql "$1" -At -F ' ' -c "SELECT
      (SELECT count(*) FROM users), (SELECT count(*) FROM households),
      (SELECT count(*) FROM accounts), (SELECT count(*) FROM transactions)"
}
read -r users households accounts transactions < <(summary "$SCRATCH")
log "The backup holds schema $restored: $users people, $households household, $accounts accounts, $transactions transactions."
[ "$users" -gt 0 ] || log "warning: the backup has no people in it. It restores a new, unset-up server."

# 8. Only now is anything that was there before touched.
if [ "$tables" -gt 0 ]; then
  log "Taking a safety backup of what is there now..."
  safety=$("$HERE/backup.sh" --out "$safety_out" --label pre-restore "${safety_args[@]}" | tail -1) \
    || die 1 "the safety backup failed, so nothing was replaced."
  log "Safety backup: $safety"
fi

# One transaction: either both renames happen or neither does, so there is never a moment with
# no database under Budget Owl's name. The old one is kept, renamed, until the app is healthy.
swapped=1
db_admin_sql >/dev/null <<SQL || { swapped=0; die 1 "swapping the restored database in failed; nothing was changed."; }
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN (:'target', '$SCRATCH');
BEGIN;
SELECT format('ALTER DATABASE %I RENAME TO $PREVIOUS', :'target') \gexec
SELECT format('ALTER DATABASE $SCRATCH RENAME TO %I', :'target') \gexec
COMMIT;
SQL

# 9. The application role and its grants (ADR-0030), from the script that made them on first start.
if db_sh '[ -x /docker-entrypoint-initdb.d/10-create-app-role.sh ]'; then
  if role_out=$(db_sh '/docker-entrypoint-initdb.d/10-create-app-role.sh' 2>&1); then
    printf '%s\n' "$role_out" | grep '^ADR-0030' >&2 || true
  else
    printf '%s\n' "$role_out" >&2
    log "warning: re-applying the application role's grants failed (above). The data is restored;"
    log "         fix the cause and run: docker compose exec db /docker-entrypoint-initdb.d/10-create-app-role.sh"
  fi
else
  log "warning: deploy/db-init is not mounted in the '$DB_SERVICE' container, so the application"
  log "         role's grants were not re-applied. See deploy/db-init/10-create-app-role.sh."
fi

read -r users households accounts transactions < <(summary "")
log "Restored: $users people, $households household, $accounts accounts, $transactions transactions."
log "Everybody signs in again, on the web and on phones. Then check Devices for anything unexpected."

if [ "$start" -eq 1 ]; then
  log "Starting Budget Owl..."
  dc up -d >&2
  if wait_healthy "$API_SERVICE" 300; then
    db_admin_sql <<<"DROP DATABASE IF EXISTS $PREVIOUS WITH (FORCE);" >/dev/null
    log "Budget Owl is running on the restored data."
  else
    log "Budget Owl did NOT come up healthy on the restored data. Look at: docker compose logs $API_SERVICE"
    [ -z "${safety:-}" ] || log "A safety backup of what was there is also in $safety"
    # The EXIT trap prints how to go back to the previous database.
    exit 1
  fi
else
  log "Budget Owl is STOPPED (--no-start). Start it with: docker compose up -d"
  log "Once it is healthy on the restored data, drop the old database:"
  log "  docker compose exec db psql -U <DB_USER> -d postgres -c 'DROP DATABASE $PREVIOUS'"
  go_back
fi
