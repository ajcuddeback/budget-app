#!/usr/bin/env bash
# Back up Budget Owl's database: everything your household has ever recorded.
#
#   deploy/backup.sh --out /mnt/backups/budgetowl
#   deploy/backup.sh --out DIR --age-recipient age1...        # encrypted with age
#   deploy/backup.sh --out DIR --gpg-recipient KEY-ID         # encrypted with gpg
#   deploy/backup.sh --out DIR --keep 14 --keep-weekly 8 --keep-monthly 12
#   deploy/backup.sh --print-signing-key                      # the line for your password manager
#
# Options:
#   --out DIR                   where to write. Created 0700 if missing.
#   --age-recipient KEY         encrypt to an age PUBLIC key (age1...). Repeatable.
#   --age-recipients-file FILE  encrypt to every age public key in FILE.
#   --gpg-recipient KEY         encrypt to a gpg PUBLIC key already in your keyring.
#   --keep N                    after a good backup, keep the newest N nightly backups (N >= 1;
#                               leave it out for no rotation at all)...
#   --keep-weekly N             ...plus the newest from each of the last N weeks (0 = none)...
#   --keep-monthly N            ...plus the newest from each of the last N months (0 = none),
#                               and delete the other nightly backups OF THIS INSTALL that THIS
#                               SERVER signed. Labelled backups (pre-upgrade, pre-restore),
#                               other installs', unsigned ones and oddly named ones are never
#                               deleted, or counted.
#   --label NAME                add NAME to the file name (letters, digits, dashes).
#   --allow-plaintext           write an unencrypted backup into a folder that holds encrypted
#                               ones. Refused otherwise: it is usually a forgotten setting.
#   --print-signing-key         print this server's allowed_signers line (making the key if
#                               needed) and exit.
#   -h, --help                  this text.
#
# Settings also come from /etc/budgetowl/backup.env (the nightly timer's file) and the
# environment: BACKUP_DIR, BACKUP_KEEP, BACKUP_KEEP_WEEKLY, BACKUP_KEEP_MONTHLY,
# BACKUP_AGE_RECIPIENT, BACKUP_GPG_RECIPIENT, BACKUP_SIGNING_KEY. Options win.
#
# Writes budgetowl-<UTC time>-<version>-schema<N>[-label].dump (.age or .gpg when encrypted),
# mode 0600, and beside it: .sha256 (damage), .sig (made by THIS server: restore refuses anything
# else) and .meta (which install it came from, for --keep). The plain dump never touches the disk
# when you encrypt. The last line on stdout is the backup's path.
#
# Signing key: BACKUP_SIGNING_KEY, else /etc/budgetowl/backup-signing-key, else
# ~/.config/budgetowl/backup-signing-key — made on first use, mode 0600, never in DIR.
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
load_backup_env

out="${BACKUP_DIR:-}"
keep="${BACKUP_KEEP:-}"
keep_weekly="${BACKUP_KEEP_WEEKLY:-}"
keep_monthly="${BACKUP_KEEP_MONTHLY:-}"
label=""
allow_plaintext=0
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
    --keep-weekly) keep_weekly="${2:?--keep-weekly needs a number}"; shift 2 ;;
    --keep-monthly) keep_monthly="${2:?--keep-monthly needs a number}"; shift 2 ;;
    --label) label="${2:?--label needs a name}"; shift 2 ;;
    --allow-plaintext) allow_plaintext=1; shift ;;
    --print-signing-key)
      key=$(ensure_signing_key)
      cat -- "$key.allowed_signers"
      exit 0 ;;
    -h|--help) print_help "$0"; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done

[ -n "$out" ] || die 2 "say where to write the backup: --out DIR (see --help)"
for n in "$keep" "$keep_weekly" "$keep_monthly"; do
  [ -z "$n" ] || [[ "$n" =~ ^[0-9]+$ ]] || die 2 "--keep, --keep-weekly and --keep-monthly take whole numbers"
done
# --keep 0 would mean "delete every nightly backup but tonight's". Nobody means that; leaving
# --keep out is how to say "no rotation".
if [[ "$keep" =~ ^0+$ ]]; then
  die 2 "--keep 0 would delete every nightly backup except this one. Leave --keep out for no rotation."
fi
if [ -z "$keep" ] && [ -n "$keep_weekly$keep_monthly" ]; then
  die 2 "--keep-weekly and --keep-monthly need --keep (how many nightly backups to keep)"
fi
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
encrypted=0
{ [ ${#age_args[@]} -gt 0 ] || [ -n "$gpg_recipient" ]; } && encrypted=1

# Everything this script creates is readable by its owner and nobody else, including the
# partial file a crash would leave behind.
umask 077
if [ ! -d "$out" ]; then
  mkdir -p -- "$out"
  chmod 700 -- "$out"
fi
out="$(cd "$out" && pwd -P)"
check_backup_folder "$out"
refuse_private_keys "$out"

signing_key=$(ensure_signing_key)
case "$(cd "$(dirname "$signing_key")" && pwd -P)/" in
  "$out"/*) die 1 "the signing key ($signing_key) is inside the backup folder. Move it out:
       anyone who can read it can sign a backup of their own." ;;
esac

if [ "$encrypted" -eq 0 ] && [ "$allow_plaintext" -eq 0 ] \
   && find "$out" -maxdepth 1 -type f \( -name 'budgetowl-*.dump.age' -o -name 'budgetowl-*.dump.gpg' \) \
        | grep -q .; then
  die 1 "$out already holds encrypted backups, and this one would not be encrypted.
       Give the same --age-recipient (or set BACKUP_AGE_RECIPIENT in $BACKUP_ENV_FILE),
       or --allow-plaintext if an unencrypted one is really what you want."
fi

require_docker
ensure_db_up

schema=$(schema_version "")
version=$(app_version)
# Which install this is, for --keep: a hash of when its instance_settings row was created, which
# a new install never shares with an old one. Not secret, but not the timestamp itself either.
read -r instance people < <(db_psql "" -At -F ' ' -c "SELECT
    CASE WHEN to_regclass('public.instance_settings') IS NULL THEN 'none'
         ELSE coalesce((SELECT left(md5('budgetowl:' || created_at::text), 16)
                          FROM instance_settings WHERE id = 1), 'none') END,
    CASE WHEN to_regclass('public.users') IS NULL THEN 'no'
         WHEN EXISTS (SELECT 1 FROM users) THEN 'yes' ELSE 'no' END" 2>/dev/null \
  || echo "none no")
ext=""
if [ ${#age_args[@]} -gt 0 ]; then ext=".age"
elif [ -n "$gpg_recipient" ]; then ext=".gpg"
fi
# Never overwrite a backup: two taken in the same second would otherwise share a name, and the
# second would silently replace the first.
for _ in 1 2 3 4 5; do
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  name="budgetowl-${stamp}-${version}-schema${schema:-none}${label:+-$label}.dump$ext"
  [ -e "$out/$name" ] || [ -e "$out/$name.partial" ] || break
  sleep 1
done
[ ! -e "$out/$name" ] || die 1 "a backup called $name already exists; not overwriting it"
final="$out/$name"
partial="$final.partial"
work=$(mktemp -d)
trap 'rm -f -- "$partial" "$partial.sig" "$final.sha256.partial" "$final.meta.partial" "$final.meta.partial.sig"; rm -rf -- "$work"' EXIT

encrypt() {
  if [ ${#age_args[@]} -gt 0 ]; then age "${age_args[@]}"
  elif [ -n "$gpg_recipient" ]; then gpg --batch --quiet --encrypt --recipient "$gpg_recipient" --output -
  else cat
  fi
}

log "Backing up to $final"

# --format=custom: compressed, and pg_restore can check it and restore it selectively.
# Sign-ins are left out (their rows, not their tables). A web session id is a live credential;
# a phone token row is only a hash, but restoring it would bring back a phone someone signed
# out — a lost one, say. After a restore everybody signs in again, which is the right outcome.
# Invitations are left out for the same reason: restoring one would bring back a link that was
# revoked after the backup. Membership itself lives in household_members and is kept.
#
# The stream is also read by pg_restore --list as it goes past, so a dump that pg_restore cannot
# read fails here, today, rather than on the day you need it. That check drains the rest of the
# stream after reading the table of contents, so it never cuts the pipe short.
db_sh 'exec pg_dump --format=custom --no-password -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
         --exclude-table-data=spring_session --exclude-table-data=spring_session_attributes \
         --exclude-table-data=auth_tokens --exclude-table-data=household_invitations' \
  | tee >(db_sh 'pg_restore --list >/dev/null; s=$?; cat >/dev/null; exit $s' \
            >/dev/null 2>&1; echo $? > "$work/listed") \
  | encrypt > "$partial"
# The check runs beside the pipeline, not in it; give it a moment to write its verdict.
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$work/listed" ] && break; sleep 1; done
[ "$(cat "$work/listed" 2>/dev/null)" = 0 ] \
  || die 1 "pg_restore could not read the dump that was just taken; nothing was kept"
[ -s "$partial" ] || die 1 "the backup came out empty; nothing was kept"
chmod 600 -- "$partial"

# The signature is over the exact bytes on disk (after encryption), so it can be checked before
# anything is decrypted.
ssh-keygen -Y sign -q -f "$signing_key" -n "$SIGNING_NAMESPACE" "$partial" </dev/null \
  || die 1 "signing the backup failed; nothing was kept"
( cd "$out" && sha256sum -- "$(basename "$partial")" | sed "s/\.partial\$//" > "$name.sha256.partial" )
printf 'backup=%s\ninstance=%s\npeople=%s\n' "$name" "$instance" "$people" > "$final.meta.partial"
# The .meta decides what rotation may delete, so it is signed too, and names its backup.
ssh-keygen -Y sign -q -f "$signing_key" -n "$SIGNING_NAMESPACE" "$final.meta.partial" </dev/null \
  || die 1 "signing the backup's .meta failed; nothing was kept"
chmod 600 -- "$partial.sig" "$final.sha256.partial" "$final.meta.partial" "$final.meta.partial.sig"
mv -- "$partial.sig" "$final.sig"
mv -- "$final.sha256.partial" "$final.sha256"
mv -- "$final.meta.partial.sig" "$final.meta.sig"
mv -- "$final.meta.partial" "$final.meta"
mv -- "$partial" "$final"

size=$(du -h -- "$final" | cut -f1)
log "Done: $size, schema version ${schema:-none}, signed, checksum in $name.sha256"
if [ "$encrypted" -eq 0 ]; then
  log "Not encrypted. Anyone who can read this file can read every transaction in it."
fi

# ------------------------------------------------------------------------------ rotation
meta_get() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -1; }

# ours <name> — a nightly backup this server signed, whose signed .meta names it and names THIS
# install, with a real date in its name that is not in the future. Anything else in the folder
# is not rotation's business: it is never counted and never deleted. Never fails.
ours() {
  local n="$1" when
  when=$(name_epoch "$n")
  [ -n "$when" ] && [ "$when" -le "$((now + 300))" ] || return 1
  [ -f "$out/$n.sig" ] && [ -f "$out/$n.meta" ] && [ -f "$out/$n.meta.sig" ] || return 1
  verify_signature "$out/$n" "$own_signers" || return 1
  verify_signature "$out/$n.meta" "$own_signers" || return 1
  [ "$(meta_get "$out/$n.meta" backup)" = "$n" ] || return 1
  [ "$(meta_get "$out/$n.meta" instance)" = "$instance" ]
}

prune() {
  local names=() candidates=() n prev="" kept_daily=0 week month i when
  local -A keep_names=() weeks=() months=()
  [ -n "$keep" ] || return 0
  # Rotation must never be the thing that loses the real history.
  if [ "$people" != yes ]; then
    log "Not removing any old backups: this one has nobody in it (a new, unset-up install?)."
    return 0
  fi
  # Nightly (unlabelled) backups, newest first; the timestamp makes names sort by time.
  mapfile -t names < <(find "$out" -maxdepth 1 -type f -printf '%f\n' \
    | grep -E '^budgetowl-[0-9]{8}T[0-9]{6}Z-.+-schema([0-9]+|none)\.dump(\.age|\.gpg)?$' \
    | sort -r || true)
  # The newest earlier backup with a real, past date: if it is not ours, stop. (One we cannot
  # vouch for is from another install, or another server, or was put there by someone else.)
  for n in "${names[@]}"; do
    [ "$n" = "$name" ] && continue
    when=$(name_epoch "$n")
    [ -n "$when" ] && [ "$when" -le "$((now + 300))" ] || continue
    prev="$n"; break
  done
  if [ -n "$prev" ] && ! ours "$prev"; then
    log "Not removing any old backups: the newest one before this ($prev) is from a different"
    log "install or server, or is not signed by this one. Restore it, or move the old backups"
    log "elsewhere, first."
    return 0
  fi
  for n in "${names[@]}"; do
    ours "$n" && candidates+=("$n")
  done
  for n in "${candidates[@]}"; do
    week=$(date -u -d "${n:10:8}" +%G-W%V 2>/dev/null) || continue
    month=${n:10:6}
    if [ "$kept_daily" -lt "$keep" ]; then keep_names[$n]=1; kept_daily=$((kept_daily + 1)); fi
    if [ -z "${weeks[$week]:-}" ] && [ "${#weeks[@]}" -lt "${keep_weekly:-0}" ]; then
      weeks[$week]=1; keep_names[$n]=1
    fi
    if [ -z "${months[$month]:-}" ] && [ "${#months[@]}" -lt "${keep_monthly:-0}" ]; then
      months[$month]=1; keep_names[$n]=1
    fi
  done
  keep_names[$name]=1
  for n in "${candidates[@]}"; do
    [ -n "${keep_names[$n]:-}" ] && continue
    log "Removing old backup $n"
    for i in "" .sha256 .sig .meta .meta.sig; do rm -f -- "$out/$n$i"; done
  done
}
now=$(date -u +%s)
own_signers="$signing_key.allowed_signers"
[ -f "$own_signers" ] || allowed_signers_line "$signing_key.pub" > "$own_signers"
prune

printf '%s\n' "$final"
