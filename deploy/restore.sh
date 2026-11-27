#!/usr/bin/env bash
# Restore Budget Owl's database from a backup made by deploy/backup.sh.
#
#   deploy/restore.sh BACKUP                       # onto a new, empty install
#   deploy/restore.sh BACKUP --replace             # over a database that has data in it
#   deploy/restore.sh BACKUP.age --age-identity ~/budgetowl-backup-key.txt
#   deploy/restore.sh BACKUP.gpg                   # gpg asks for your passphrase
#
# Options:
#   --replace                 allow replacing a database that already has Budget Owl in it.
#                             A safety backup of what is there now is taken first.
#   --age-identity FILE       the age PRIVATE key that decrypts an .age backup.
#   --safety-out DIR          where the safety backup goes (default: beside BACKUP).
#   --safety-age-recipient K  encrypt the safety backup to this age public key,
#   --safety-gpg-recipient K  ...or to this gpg key. Unencrypted otherwise.
#   --no-start                leave the app stopped afterwards.
#   --skip-version-check      restore even if this version's app image cannot be read to check
#                             that the backup is not newer than it. Avoid.
#   -h, --help                this text.
#
# What it does, in order. Nothing that is there now is changed until step 6.
#   1. checks BACKUP against BACKUP.sha256, and refuses if they differ;
#   2. checks pg_restore can read it;
#   3. refuses if the database already holds Budget Owl, unless you gave --replace;
#   4. stops the app, and restores into a separate scratch database;
#   5. checks what came back: a Budget Owl schema, not newer than this version understands;
#   6. with --replace, takes a safety backup, then swaps the restored database in;
#   7. re-applies the application role's grants (ADR-0030) and starts the app.
# If anything fails before step 6, the scratch database is dropped and the app is started again
# on the database it had.

# Database commands are single-quoted on purpose: the CONTAINER's shell expands them (lib.sh).
# shellcheck disable=SC2016
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

backup=""
replace=0
identity=""
safety_out=""
safety_args=()
start=1
skip_version_check=0

while [ $# -gt 0 ]; do
  case "$1" in
    --replace) replace=1; shift ;;
    --age-identity) identity="${2:?--age-identity needs a file}"; shift 2 ;;
    --safety-out) safety_out="${2:?--safety-out needs a directory}"; shift 2 ;;
    --safety-age-recipient) safety_args+=(--age-recipient "${2:?needs a key}"); shift 2 ;;
    --safety-gpg-recipient) safety_args+=(--gpg-recipient "${2:?needs a key}"); shift 2 ;;
    --no-start) start=0; shift ;;
    --skip-version-check) skip_version_check=1; shift ;;
    -h|--help) print_help "$0"; exit 0 ;;
    -*) die 2 "unknown option: $1 (see --help)" ;;
    *) [ -z "$backup" ] || die 2 "one backup at a time"; backup="$1"; shift ;;
  esac
done

[ -n "$backup" ] || die 2 "say which backup to restore (see --help)"
[ -f "$backup" ] || die 2 "no such file: $backup"
backup="$(cd "$(dirname "$backup")" && pwd)/$(basename "$backup")"
case "$backup" in *.partial|*.sha256) die 2 "that is not a backup: $backup" ;; esac

case "$backup" in
  *.age)
    command -v age >/dev/null 2>&1 || die 1 "this backup is encrypted with age; install age"
    [ -n "$identity" ] || die 2 "this backup is encrypted: give the private key with --age-identity FILE"
    [ -r "$identity" ] || die 2 "cannot read $identity" ;;
  *.gpg)
    command -v gpg >/dev/null 2>&1 || die 1 "this backup is encrypted with gpg; install gpg" ;;
esac
[ -n "$safety_out" ] || safety_out="$(dirname "$backup")"

# 1. The checksum. Read from the .sha256 file but compared by hash alone, so a renamed backup
#    still verifies and a swapped one does not.
sumfile="$backup.sha256"
[ -f "$sumfile" ] || die 3 "no checksum beside the backup ($sumfile). Without one there is no way
       to tell a damaged or altered file from a good one, so this will not restore it."
expected=$(awk 'NR==1 {print $1}' "$sumfile")
actual=$(sha256sum < "$backup" | awk '{print $1}')
if [ "$expected" != "$actual" ]; then
  die 3 "CHECKSUM MISMATCH: $backup is not the file its checksum was made from.
       It is damaged or has been changed. Nothing was restored, and nothing was changed."
fi
log "Checksum matches."

# The backup as a stream. The decrypted dump is never written to disk.
stream() {
  case "$backup" in
    *.age) age -d -i "$identity" "$backup" ;;
    *.gpg) gpg --quiet --decrypt "$backup" ;;
    *) cat "$backup" ;;
  esac
}

require_docker
ensure_db_up

# 2. Can pg_restore read it at all?
stream | db_sh 'pg_restore --list >/dev/null; s=$?; cat >/dev/null; exit $s' \
  || die 1 "pg_restore cannot read this backup (wrong key, or not a Budget Owl backup). Nothing was changed."

# 3. Is there something here to lose?
tables=$(db_psql "" -At -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")
if [ "$tables" -gt 0 ] && [ "$replace" -ne 1 ]; then
  die 4 "the database already has Budget Owl in it ($tables tables). Restoring would replace
       everything in it. Nothing was changed.

       If that is what you want, run this again with --replace. A safety backup of what is
       there now is taken first, into $safety_out.

       (A brand-new install that has been started once has empty tables, and this applies to it
       too: --replace is safe there, and the safety backup will simply be small.)"
fi

# 4. Stop what writes to the database, remembering what was running so a failure puts it back.
was_running=()
for svc in "$API_SERVICE" "$WEB_SERVICE"; do
  if service_running "$svc"; then was_running+=("$svc"); fi
done
swapped=0
cleanup() {
  status=$?
  if [ "$swapped" -eq 0 ]; then
    db_psql postgres -c "DROP DATABASE IF EXISTS \"budgetowl_restore_scratch\" WITH (FORCE)" \
      >/dev/null 2>&1 || true
    if [ ${#was_running[@]} -gt 0 ] && [ "$status" -ne 0 ]; then
      log "Starting ${was_running[*]} again on the database it had."
      dc start "${was_running[@]}" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

if [ ${#was_running[@]} -gt 0 ]; then
  log "Stopping ${was_running[*]} while the restore runs..."
  dc stop "${was_running[@]}" >/dev/null 2>&1
fi

# The scratch database. Its name is fixed (not derived from anything you typed) so the cleanup
# above can only ever drop this one.
db_psql postgres -c "DROP DATABASE IF EXISTS \"budgetowl_restore_scratch\" WITH (FORCE)" >/dev/null
db_psql postgres -c "CREATE DATABASE \"budgetowl_restore_scratch\"" >/dev/null

log "Restoring into a scratch database..."
# --single-transaction + --exit-on-error: all of it, or none of it.
# --no-owner: objects belong to whoever this install's owning role is, even if it has a
#   different name from the one that made the backup.
# --no-privileges: the backup's grants name a role that may not exist here. Step 7 re-applies
#   them from the same script that created them, so they always match this install.
stream | db_sh 'exec pg_restore --no-owner --no-privileges --exit-on-error --single-transaction \
                  -U "$POSTGRES_USER" -d budgetowl_restore_scratch' \
  || die 1 "the restore failed. The database you had is untouched."

# 5. Is it a Budget Owl database, and one this version can run?
restored=$(schema_version budgetowl_restore_scratch)
[ -n "$restored" ] || die 1 "the backup holds no Budget Owl schema history. Nothing was changed."
failed=$(db_psql budgetowl_restore_scratch -At -c "SELECT count(*) FROM flyway_schema_history WHERE NOT success")
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
read -r users households accounts transactions < <(summary budgetowl_restore_scratch)
log "The backup holds schema $restored: $users people, $households household, $accounts accounts, $transactions transactions."
[ "$users" -gt 0 ] || log "warning: the backup has no people in it. It restores a new, unset-up server."

# 6. Only now is anything that was there before touched.
if [ "$tables" -gt 0 ]; then
  log "Taking a safety backup of what is there now..."
  safety=$("$HERE/backup.sh" --out "$safety_out" --label pre-restore "${safety_args[@]}" | tail -1) \
    || die 1 "the safety backup failed, so nothing was replaced."
  log "Safety backup: $safety"
fi

# From here the scratch database IS the data: the cleanup must never drop it again, even if the
# swap stops halfway. The database's name comes from the container's own POSTGRES_DB and goes
# through %I quoting.
swapped=1
db_sh 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres --set=target="$POSTGRES_DB"' <<'SQL' \
  || die 1 "swapping the restored database in failed part-way. The restored data is in the
       database budgetowl_restore_scratch${safety:+, and what was there before is in $safety}.
       Rename it by hand: ALTER DATABASE budgetowl_restore_scratch RENAME TO <your DB_NAME>;"
SELECT format('DROP DATABASE IF EXISTS %I WITH (FORCE)', :'target') \gexec
SELECT format('ALTER DATABASE budgetowl_restore_scratch RENAME TO %I', :'target') \gexec
SQL

# 7. The application role and its grants (ADR-0030), from the script that made them on first start.
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

if [ "$start" -eq 1 ]; then
  log "Starting Budget Owl..."
  dc up -d >&2
  if wait_healthy "$API_SERVICE" 300; then
    log "Budget Owl is running on the restored data."
  else
    log "The app did not come up healthy. Look at: docker compose logs $API_SERVICE"
    [ -z "${safety:-}" ] || log "What was there before is in $safety"
    exit 1
  fi
else
  log "Left stopped (--no-start). Start it with: docker compose up -d"
fi
