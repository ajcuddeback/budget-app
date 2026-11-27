#!/usr/bin/env bash
# Back up Budget Owl's database: everything your household has ever recorded.
#
#   deploy/backup.sh --out /mnt/backups/budgetowl
#   deploy/backup.sh --out DIR --age-recipient age1...        # encrypted with age
#   deploy/backup.sh --out DIR --age-recipients-file FILE     # ...to the public keys in FILE
#   deploy/backup.sh --out DIR --gpg-recipient KEY-ID         # encrypted with gpg
#   deploy/backup.sh --out DIR --keep 14                      # then keep only the newest 14
#
# Options:
#   --out DIR                   where to write (or set BACKUP_DIR). Created 0700 if missing.
#   --age-recipient KEY         encrypt to an age PUBLIC key (age1...). Repeatable.
#   --age-recipients-file FILE  encrypt to every age public key in FILE.
#   --gpg-recipient KEY         encrypt to a gpg PUBLIC key already in your keyring.
#   --keep N                    after a good backup, delete all but the newest N scheduled
#                               backups in DIR. Labelled ones (pre-upgrade, pre-restore) are
#                               never deleted by this.
#   --label NAME                add NAME to the file name (letters, digits, dashes).
#   -h, --help                  this text.
#
# For a timer, the same can come from the environment: BACKUP_DIR, BACKUP_KEEP,
# BACKUP_AGE_RECIPIENT, BACKUP_GPG_RECIPIENT. Options on the command line win.
#
# Writes budgetowl-<UTC time>-<version>-schema<N>[-label].dump (plus .age or .gpg when
# encrypted), mode 0600, with a .sha256 beside it. The plain dump never touches the disk when
# you encrypt. The last line printed on stdout is the backup's path.
#
# Run it from the folder that holds docker-compose.yml. It reads the database through
# `docker compose exec`, as the role that owns it, and never needs a password.

# Database commands are single-quoted on purpose: the CONTAINER's shell expands them (lib.sh).
# shellcheck disable=SC2016
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

out="${BACKUP_DIR:-}"
keep="${BACKUP_KEEP:-}"
label=""
age_args=()
[ -z "${BACKUP_AGE_RECIPIENT:-}" ] || age_args=(-r "$BACKUP_AGE_RECIPIENT")
gpg_recipient="${BACKUP_GPG_RECIPIENT:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="${2:?--out needs a directory}"; shift 2 ;;
    --age-recipient) age_args+=(-r "${2:?--age-recipient needs a key}"); shift 2 ;;
    --age-recipients-file) age_args+=(-R "${2:?--age-recipients-file needs a file}"); shift 2 ;;
    --gpg-recipient) gpg_recipient="${2:?--gpg-recipient needs a key}"; shift 2 ;;
    --keep) keep="${2:?--keep needs a number}"; shift 2 ;;
    --label) label="${2:?--label needs a name}"; shift 2 ;;
    -h|--help) print_help "$0"; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done

[ -n "$out" ] || die 2 "say where to write the backup: --out DIR (see --help)"
[ -z "$keep" ] || [[ "$keep" =~ ^[1-9][0-9]*$ ]] || die 2 "--keep takes a whole number of at least 1"
[ -z "$label" ] || [[ "$label" =~ ^[a-z0-9][a-z0-9-]*$ ]] \
  || die 2 "--label takes lower-case letters, digits and dashes"
if [ ${#age_args[@]} -gt 0 ] && [ -n "$gpg_recipient" ]; then
  die 2 "choose age or gpg, not both"
fi
if [ ${#age_args[@]} -gt 0 ]; then
  command -v age >/dev/null 2>&1 || die 1 "--age-recipient needs the 'age' tool installed"
fi
if [ -n "$gpg_recipient" ]; then
  command -v gpg >/dev/null 2>&1 || die 1 "--gpg-recipient needs gpg installed"
  gpg --batch --list-keys -- "$gpg_recipient" >/dev/null 2>&1 \
    || die 1 "no public key for '$gpg_recipient' in your gpg keyring (gpg --import it first)"
fi

# Everything this script creates is readable by its owner and nobody else, including the
# partial file a crash would leave behind.
umask 077
if [ ! -d "$out" ]; then
  mkdir -p -- "$out"
  chmod 700 -- "$out"
fi
out="$(cd "$out" && pwd)"
# A directory others can write to lets them replace a backup, or its checksum, with their own.
perms=$(stat -c %a -- "$out")
case "$perms" in
  *[2367]?|*?[2367]) die 1 "$out is writable by other users (mode $perms). Fix it: chmod 700 $out" ;;
esac
# Encryption is pointless if the key that undoes it is in the same place as what it protects.
leaked=$(find "$out" -maxdepth 1 -type f -size -64k ! -name 'budgetowl-*' \
  -exec grep -l -s -e 'AGE-SECRET-KEY-' -e 'BEGIN PGP PRIVATE KEY BLOCK' {} + 2>/dev/null || true)
[ -z "$leaked" ] || die 1 "a PRIVATE key is in the backup folder ($leaked). Move it somewhere else:
       anyone who can read the backups can then read the household's whole history."

require_docker
ensure_db_up

schema=$(schema_version "")
version=$(app_version)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
name="budgetowl-${stamp}-${version}-schema${schema:-none}${label:+-$label}.dump"
if [ ${#age_args[@]} -gt 0 ]; then name="$name.age"
elif [ -n "$gpg_recipient" ]; then name="$name.gpg"
fi
final="$out/$name"
partial="$final.partial"
work=$(mktemp -d)
trap 'rm -f -- "$partial" "$final.sha256.partial"; rm -rf -- "$work"' EXIT

encrypt() {
  if [ ${#age_args[@]} -gt 0 ]; then age "${age_args[@]}"
  elif [ -n "$gpg_recipient" ]; then gpg --batch --quiet --encrypt --recipient "$gpg_recipient" --output -
  else cat
  fi
}

log "Backing up to $final"

# --format=custom: compressed, and pg_restore can check it and restore it selectively.
# Web sign-in sessions are left out (their rows, not their tables): a session id is a live
# credential for up to 12 hours, a backup is kept for months, and a restored server signing
# everyone out of the browser is the right outcome anyway. Phone sign-ins survive a restore —
# only a hash of each token is stored.
#
# The stream is also read by pg_restore --list as it goes past, so a dump that pg_restore cannot
# read fails here, today, rather than on the day you need it. That check drains the rest of the
# stream after reading the table of contents, so it never cuts the pipe short.
db_sh 'exec pg_dump --format=custom --no-password -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
         --exclude-table-data=spring_session --exclude-table-data=spring_session_attributes' \
  | tee >(db_sh 'pg_restore --list >/dev/null; s=$?; cat >/dev/null; exit $s' \
            >/dev/null 2>&1; echo $? > "$work/listed") \
  | encrypt > "$partial"
# The check runs beside the pipeline, not in it; give it a moment to write its verdict.
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$work/listed" ] && break; sleep 1; done
[ "$(cat "$work/listed" 2>/dev/null)" = 0 ] \
  || die 1 "pg_restore could not read the dump that was just taken; nothing was kept"
[ -s "$partial" ] || die 1 "the backup came out empty; nothing was kept"

chmod 600 -- "$partial"
mv -- "$partial" "$final"
( cd "$out" && sha256sum -- "$name" > "$name.sha256.partial" && chmod 600 -- "$name.sha256.partial" \
    && mv -- "$name.sha256.partial" "$name.sha256" )

size=$(du -h -- "$final" | cut -f1)
log "Done: $size, schema version ${schema:-none}, checksum in $name.sha256"
if [ ${#age_args[@]} -eq 0 ] && [ -z "$gpg_recipient" ]; then
  log "Not encrypted. Anyone who can read this file can read every transaction in it."
fi

if [ -n "$keep" ]; then
  # Scheduled (unlabelled) backups only, oldest first; the timestamp makes names sort by time.
  mapfile -t scheduled < <(find "$out" -maxdepth 1 -type f -printf '%f\n' \
    | grep -E '^budgetowl-[0-9]{8}T[0-9]{6}Z-.+-schema([0-9]+|none)\.dump(\.age|\.gpg)?$' | sort)
  excess=$(( ${#scheduled[@]} - keep ))
  for ((i = 0; i < excess; i++)); do
    log "Removing old backup ${scheduled[$i]}"
    rm -f -- "$out/${scheduled[$i]}" "$out/${scheduled[$i]}.sha256"
  done
fi

printf '%s\n' "$final"
