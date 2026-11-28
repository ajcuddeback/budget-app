# shellcheck shell=bash disable=SC2016
# Shared by deploy/backup.sh, deploy/restore.sh and deploy/upgrade.sh. Sourced, never run.
#
# THE ONE RULE THIS FILE EXISTS TO KEEP: no database credential ever appears on this host's
# command line, in a process listing, or in shell history. Every database command runs INSIDE the
# `db` container through `docker compose exec`, over the container's local socket, as the role the
# container's own POSTGRES_USER names. The official postgres image trusts local-socket connections,
# so no password is involved at all, and the scripts that use this never read one.
#
# Script bodies passed to the container are single-quoted on this side, so "$POSTGRES_USER" and
# friends are expanded by the container's shell from the container's environment, never by ours.

DB_SERVICE="${DB_SERVICE:-db}"
API_SERVICE="${API_SERVICE:-api}"
WEB_SERVICE="${WEB_SERVICE:-web}"

log() { printf '%s\n' "$*" >&2; }
die() { # die <exit-code> <message...>
  local code="$1"; shift
  printf 'error: %s\n' "$*" >&2
  exit "$code"
}

dc() { docker compose "$@"; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die 1 "docker is not installed or not on PATH"
  docker compose version >/dev/null 2>&1 \
    || die 1 "the Docker Compose plugin is missing ('docker compose version' failed)"
  [ -f docker-compose.yml ] || die 1 "no docker-compose.yml in $(pwd)"
}

service_running() { dc ps --status running --services 2>/dev/null | grep -qx "$1"; }

# db_sh <script> [args...] — run a POSIX sh script in the db container; args arrive as $1...
db_sh() {
  local script="$1"; shift
  dc exec -T "$DB_SERVICE" sh -c "$script" sh "$@"
}

# db_psql <database-or-empty> [psql args...] — psql as the owning role. Empty means POSTGRES_DB.
db_psql() {
  local db="$1"; shift
  dc exec -T -e BO_DB="$db" -e PGOPTIONS='-c client_min_messages=warning' "$DB_SERVICE" \
    sh -c 'exec psql -X -q -w -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${BO_DB:-$POSTGRES_DB}" "$@"' \
    sh "$@"
}

# db_admin_sql — SQL on stdin, run in the maintenance database `postgres`, with this install's
# database name available as :'target' (from the container's own POSTGRES_DB).
db_admin_sql() {
  dc exec -T -e PGOPTIONS='-c client_min_messages=warning' "$DB_SERVICE" \
    sh -c 'exec psql -X -q -w -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres --set=target="$POSTGRES_DB"'
}

# ---------------------------------------------------------------- settings shared with the timer
# /etc/budgetowl/backup.env is what budgetowl-backup.service reads. backup.sh, restore.sh and
# upgrade.sh read it too, so a backup taken by hand or before an upgrade is encrypted and signed
# exactly like the nightly one. It is PARSED, never sourced: only these keys, only KEY=value.
# Anything already in the environment wins, EVEN WHEN EMPTY: `BACKUP_AGE_RECIPIENT= backup.sh`
# really does mean "no recipient", rather than quietly taking the file's.
#
# It decides where backups go, which key encrypts them and which signs them, so it is a trust
# anchor: it must belong to root or to you, and nobody else may be able to change it.
BACKUP_ENV_FILE="${BACKUP_ENV_FILE:-/etc/budgetowl/backup.env}"
load_backup_env() {
  local key value
  [ -e "$BACKUP_ENV_FILE" ] || return 0
  check_trust_file "$BACKUP_ENV_FILE" "the backup settings file" 1
  for key in BACKUP_DIR BACKUP_KEEP BACKUP_KEEP_WEEKLY BACKUP_KEEP_MONTHLY \
             BACKUP_AGE_RECIPIENT BACKUP_GPG_RECIPIENT BACKUP_SIGNING_KEY; do
    [ -z "${!key+set}" ] || continue
    value=$(sed -n "s/^${key}=//p" "$BACKUP_ENV_FILE" | tail -1)
    value="${value%\"}"; value="${value#\"}"
    [ -z "$value" ] || printf -v "$key" '%s' "$value"
  done
}

# ---------------------------------------------------------------- who may own what
# owned_by_us_or_root <path> — root, or the account running this.
owned_by_us_or_root() {
  local owner
  owner=$(stat -L -c %u -- "$1")
  [ "$owner" = 0 ] || [ "$owner" = "$(id -u)" ]
}

# whose <path> — who owns it, said so that the fix is obvious. Run as root (or under sudo), the
# usual mistake is the user, not the file: the operator's own files are "another user's" to root.
# operator_name — the account this install is run as: BUDGETOWL_OPERATOR, else the VM's
# BUDGETOWL_USER (/etc/budgetowl/bootstrap.conf, parsed), else whoever owns the signing key.
operator_name() {
  local n="${BUDGETOWL_OPERATOR:-}" key
  if [ -z "$n" ] && [ -r /etc/budgetowl/bootstrap.conf ]; then
    n=$(sed -n 's/^BUDGETOWL_USER=//p' /etc/budgetowl/bootstrap.conf | tail -1)
  fi
  if [ -z "$n" ]; then
    key=$(signing_key_path)
    [ ! -e "$key" ] || n=$(stat -L -c %U -- "$key" 2>/dev/null || true)
  fi
  printf '%s' "$n"
}

whose() {
  local name uid op
  name=$(stat -L -c %U -- "$1" 2>/dev/null || echo '?')
  uid=$(stat -L -c %u -- "$1" 2>/dev/null || echo '?')
  op=$(operator_name)
  if { [ "$(id -u)" = 0 ] || [ -n "${SUDO_USER:-}" ]; } && [ -n "$op" ] && [ "$name" = "$op" ]; then
    # Only then is "you are the wrong user" the likely mistake, and the fix safe to suggest.
    printf 'It belongs to %s. Run this as the operator (%s), not root: sudo -u %s %s' \
      "$name" "$name" "$name" "${0##*/}"
  elif [ -n "$op" ] && [ "$name" != "$op" ]; then
    printf 'It belongs to %s (uid %s), which is neither root nor this install'"'"'s operator (%s).' \
      "$name" "$uid" "$op"
  else
    printf 'It belongs to another user, %s (uid %s), not to you.' "$name" "$uid"
  fi
}

# Filesystems whose owners and modes are whatever the far end says they are. BUDGETOWL_EXTRA_NETWORK_FS
# can ADD types (space separated, as `stat -f -c %T` prints them); nothing can remove one.
# on_network_fs <path> [strict] — prints the type and succeeds if it is one of them. With
# "strict" (trust files), a type stat cannot name ("UNKNOWN (0x…)") counts as one too: a trust
# anchor has to be somewhere we know owners mean something.
on_network_fs() {
  local t extra
  t=$(stat -f -c %T -- "$1" 2>/dev/null || echo unknown)
  case "$t" in nfs*|cifs|smb*|fuse*|9p|ceph|glusterfs|afs|lustre|ocfs2|gfs2|gfs2meta)
    printf '%s' "$t"; return 0 ;; esac
  if [ "${2:-}" = strict ]; then
    case "$t" in UNKNOWN*|unknown) printf '%s' "$t"; return 0 ;; esac
  fi
  for extra in ${BUDGETOWL_EXTRA_NETWORK_FS:-}; do
    [ "$t" = "$extra" ] && { printf '%s' "$t"; return 0; }
  done
  return 1
}

# check_parents <path> <what> <exit-code> — as sshd's StrictModes does: every folder above must be
# root's or yours, and writable by nobody else unless it is root's and sticky (/tmp). Whoever can
# rename entries in a parent can put their own file or folder in the place of yours.
check_parents() {
  local path="$1" what="$2" code="${3:-1}" up
  up=$(dirname "$path")
  while :; do
    owned_by_us_or_root "$up" || die "$code" "$up, which holds $what ($path), is not root's or yours.
       $(whose "$up") Its owner could move $what aside and put their own in its place."
    if writable_by_others "$up" && ! root_sticky "$up"; then
      die "$code" "$up, which holds $what ($path), is writable by other users (mode $(stat -c %a -- "$up")).
       They could move it aside and put their own in its place. Fix it: chmod go-w $up"
    fi
    [ "$up" = / ] && break
    up=$(dirname "$up")
  done
}

# check_trust_file <file> <what> <exit-code> — a file that decides what is trusted (backup.env,
# an allowed_signers file) must be root's or yours, writable by nobody else, in folders nobody
# else can rearrange, and on a local disk: on NFS and the like a file's owner is whatever its
# writer claimed, so an owner check there proves nothing. Otherwise whoever can write it chooses
# which signatures a restore accepts.
check_trust_file() {
  local f="$1" what="$2" code="${3:-1}" t real
  [ -r "$f" ] || die "$code" "cannot read $what ($f)"
  # The whole path resolved, symlinks included, so a symlinked file is judged by where it
  # really is, and so are the folders above it.
  real=$(readlink -f -- "$f") || die "$code" "cannot resolve $what ($f)"
  if t=$(on_network_fs "$real" strict); then
    die "$code" "$what ($f) is on a network filesystem ($t), where who owns a file is whatever the
       machine that wrote it said. It decides what is trusted, so keep it on this machine's own
       disk (your home folder, or /etc/budgetowl)."
  fi
  owned_by_us_or_root "$real" || die "$code" "$what ($f) is not root's or yours. $(whose "$real")
       It decides what is trusted, so it must be root's or yours."
  if [ -n "$(find -L "$real" -maxdepth 0 -perm /022 2>/dev/null)" ]; then
    die "$code" "$what ($f) can be changed by other users (mode $(stat -L -c %a -- "$real")).
       It decides what is trusted. Fix it: chmod go-w $f"
  fi
  check_parents "$real" "$what" "$code"
}

# name_epoch <backup name> — the UTC time in a backup's name, as seconds; nothing if the name
# does not hold a real one. Never fails: a hostile name must not stop a nightly job.
name_epoch() {
  local n="${1##*/}" t
  [[ "$n" =~ ^budgetowl-([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z- ]] || return 0
  t="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]}T${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]}Z"
  date -u -d "$t" +%s 2>/dev/null || true
}

# ---------------------------------------------------------------- folders and private keys
writable_by_others() { [ -n "$(find "$1" -maxdepth 0 -perm /022 2>/dev/null)" ]; }
root_sticky() {
  [ -n "$(find "$1" -maxdepth 0 -perm -1000 2>/dev/null)" ] && [ "$(stat -c %u -- "$1")" = 0 ]
}

# check_backup_folder <dir> — <dir> must already be absolute and physical (pwd -P).
# Anyone who can write to the folder, or to any folder above it, can replace a backup or its
# checksum, or swap the whole folder for one of their own. /tmp-style folders (root's, sticky)
# are the one safe exception above it: nobody can rename another user's entry in them.
check_backup_folder() {
  local dir="$1" up fstype
  if writable_by_others "$dir"; then
    die 1 "$dir is writable by other users (mode $(stat -c %a -- "$dir")). Fix it: chmod 700 $dir"
  fi
  owned_by_us_or_root "$dir" || die 1 "$dir is not root's or yours. $(whose "$dir")
       Whoever owns it can change its permissions back at any time."
  check_parents "$dir" "the backup folder" 1
  if fstype=$(on_network_fs "$dir"); then
    log "warning: $dir is on a network filesystem ($fstype). Its permissions are enforced by the"
    log "         server, if at all: limit the share to this machine's address, and encrypt."
  fi
}

# refuse_private_keys <dir> — the key that undoes encryption, or the key that signs, must never
# sit beside the backups. Every small file is read, whatever it is called; only the dumps
# themselves (large, binary) are skipped.
refuse_private_keys() {
  local leaked
  leaked=$(find "$1" -maxdepth 1 -type f -size -64k \
      ! -name '*.dump' ! -name '*.dump.age' ! -name '*.dump.gpg' \
      -exec grep -l -s -e 'AGE-SECRET-KEY-' -e 'BEGIN PGP PRIVATE KEY BLOCK' \
                       -e 'BEGIN OPENSSH PRIVATE KEY' {} + 2>/dev/null || true)
  [ -z "$leaked" ] || die 1 "a PRIVATE key is in the backup folder:
$(printf '         %s\n' "$leaked")
       Move it somewhere else. With the age key beside them, anyone who can read the backups
       can read the household's whole history; with the signing key, anyone can forge one."
}

# ---------------------------------------------------------------- signing (ssh-keygen -Y)
# Every backup is signed by a key only this server holds. A restore runs the backup's contents
# as the database SUPERUSER, so "who made this file" matters as much as "is it intact": a
# checksum beside the file proves only the second, and an age file proves neither — anyone can
# encrypt to a public key. The operator keeps the public half (one allowed_signers line) in
# their password manager, next to the age key.
SIGNING_NAMESPACE=budgetowl-backup
SIGNING_PRINCIPAL=budgetowl-backup
# A .meta is signed in a namespace of its own, so a .meta and its .sig can never pass for a dump
# and its .sig: renamed, they fail verification as a backup.
META_NAMESPACE=budgetowl-backup-meta

signing_key_path() {
  if [ -n "${BACKUP_SIGNING_KEY:-}" ]; then printf '%s\n' "$BACKUP_SIGNING_KEY"
  elif [ -e /etc/budgetowl/backup-signing-key ]; then printf '%s\n' /etc/budgetowl/backup-signing-key
  else printf '%s\n' "${XDG_CONFIG_HOME:-$HOME/.config}/budgetowl/backup-signing-key"
  fi
}

require_ssh_keygen() {
  command -v ssh-keygen >/dev/null 2>&1 \
    || die 1 "ssh-keygen is needed to sign and check backups (Debian/Ubuntu: apt install openssh-client)"
}

# check_private_file <file> — ours, and readable by nobody else.
check_private_file() {
  local f="$1" mode
  [ "$(stat -L -c %u -- "$f")" = "$(id -u)" ] || die 1 "$f must be yours. $(whose "$f")"
  mode=$(stat -L -c %a -- "$f")
  case "$mode" in 600|400) ;; *) die 1 "$f is mode $mode; it must be readable by you only: chmod 600 $f" ;; esac
}

# allowed_signers_line <public key file>
allowed_signers_line() {
  printf '%s namespaces="%s,%s" %s\n' "$SIGNING_PRINCIPAL" "$SIGNING_NAMESPACE" "$META_NAMESPACE" \
    "$(cut -d' ' -f1,2 -- "$1")"
}

# ensure_signing_key — the key's path; made on first use (mode 0600, no passphrase, so the nightly
# timer can use it unattended).
ensure_signing_key() {
  local key
  key=$(signing_key_path)
  require_ssh_keygen
  if [ ! -e "$key" ]; then
    ( umask 077 && mkdir -p -- "$(dirname "$key")" )
    ssh-keygen -q -t ed25519 -N '' -C "budgetowl-backup@$(hostname)" -f "$key" </dev/null >/dev/null
    chmod 600 -- "$key"
    allowed_signers_line "$key.pub" > "$key.allowed_signers"
    chmod 644 -- "$key.pub" "$key.allowed_signers"
    log "Made this server's backup-signing key: $key"
    log "Keep this line in your password manager, next to your age key. A restore on a new"
    log "server needs it (restore.sh --signers):"
    log "  $(cat -- "$key.allowed_signers")"
  fi
  check_private_file "$key"
  printf '%s\n' "$key"
}

# verify_signature <file> <allowed-signers file> [namespace] — 0 only for a good signature by a
# listed key, in that namespace (a backup's, unless told otherwise).
verify_signature() {
  ssh-keygen -Y verify -f "$2" -I "$SIGNING_PRINCIPAL" -n "${3:-$SIGNING_NAMESPACE}" -s "$1.sig" \
    < "$1" >/dev/null 2>&1
}

# wait_healthy <service> [timeout-seconds] — 0 once the container reports healthy.
wait_healthy() {
  local svc="$1" timeout="${2:-240}" waited=0 id status
  while :; do
    id=$(dc ps -q "$svc" 2>/dev/null || true)
    if [ -n "$id" ]; then
      status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$id" 2>/dev/null || echo unknown)
      [ "$status" = healthy ] && return 0
    fi
    [ "$waited" -ge "$timeout" ] && return 1
    sleep 3
    waited=$((waited + 3))
  done
}

ensure_db_up() {
  if ! service_running "$DB_SERVICE"; then
    log "Starting the '$DB_SERVICE' service..."
    dc up -d "$DB_SERVICE" >&2
  fi
  wait_healthy "$DB_SERVICE" 120 || die 1 "the '$DB_SERVICE' service did not become healthy"
}

# schema_version <database-or-empty> — the last successful Flyway version, or nothing.
schema_version() {
  local present
  present=$(db_psql "$1" -At -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
  [ "$present" = t ] || return 0
  db_psql "$1" -At -c "SELECT coalesce(max(version::int)::text, '') FROM flyway_schema_history
                        WHERE success AND version ~ '^[0-9]+\$'"
}

# image_schema_version — the highest migration shipped in the api image, without starting it.
# Empty if it cannot be read, including when the image is not on this machine: nothing is pulled
# or built to find out. (No `run --pull never`: Debian 13's Compose 2.26 does not accept it, and
# a caller that treated the resulting empty answer as "fine" is how this check once failed open.)
image_schema_version() {
  local image
  image=$(dc config --images 2>/dev/null | grep -m1 'budget-owl-api' || true)
  if [ -n "$image" ] && ! docker image inspect "$image" >/dev/null 2>&1; then
    return 0
  fi
  dc run --rm --no-deps -T --entrypoint sh "$API_SERVICE" -c 'unzip -l app.jar' 2>/dev/null \
    | grep -o 'db/migration/V[0-9]*__' | sed 's#db/migration/V##; s#__##' | sort -n | tail -1 \
    || true
}

# app_version — a filename-safe name for the code that is running: BUDGET_OWL_VERSION's tag on
# the api image if it is not `latest`, else the git commit of this checkout.
app_version() {
  local v
  v=$(dc config --images 2>/dev/null | grep -m1 'budget-owl-api' | sed 's/.*://' || true)
  if [ -z "$v" ] || [ "$v" = latest ]; then
    v=$(git describe --tags --always --dirty 2>/dev/null || true)
  fi
  printf '%s' "${v:-unknown}" | tr -c 'A-Za-z0-9._' '_'
}

# print_help <script> — the comment block at the top of a script, up to its first code line.
print_help() { sed -n '2,/^[^#]/{/^#/!q;s/^# \{0,1\}//;p;}' "$1"; }
