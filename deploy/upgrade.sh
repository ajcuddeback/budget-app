#!/usr/bin/env bash
# Upgrade Budget Owl: back up, get the new version, rebuild, start, and wait until it is healthy.
#
#   deploy/upgrade.sh --backup-dir /mnt/backups/budgetowl
#   deploy/upgrade.sh --backup-dir DIR --ref v0.2.0      # a specific tag, branch or commit
#   deploy/upgrade.sh --backup-dir DIR --no-pull         # you already updated the code yourself
#   deploy/upgrade.sh --backup-dir DIR --age-recipient age1...   # encrypt the pre-upgrade backup
#
# Options:
#   --backup-dir DIR        where the pre-upgrade backup goes. Required (or BACKUP_DIR in
#                           /etc/budgetowl/backup.env): there is no upgrade without one. It is
#                           encrypted and signed exactly like the nightly backups.
#   --ref REF               check out REF (fetched from origin) instead of pulling this branch.
#   --no-pull               do not touch the code; rebuild and restart what is checked out.
#   --no-build              do not build; use the images already on this machine (you built or
#                           loaded them yourself).
#   --age-recipient KEY     passed to deploy/backup.sh,
#   --gpg-recipient KEY     ...as is this.
#   -h, --help              this text.
#
# It never rolls back by itself: whether to go back is a decision about your data, so it prints
# the exact commands instead. Migrations cannot be undone, so going back always means the
# previous code AND the pre-upgrade backup, together.

# shellcheck disable=SC2016
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"
# The nightly backup's settings (folder, encryption, signing key) apply to the pre-upgrade one.
load_backup_env

backup_dir="${BACKUP_DIR:-}"
ref=""
pull=1
build=1
backup_args=()

while [ $# -gt 0 ]; do
  case "$1" in
    --backup-dir) backup_dir="${2:?--backup-dir needs a directory}"; shift 2 ;;
    --ref) ref="${2:?--ref needs a tag, branch or commit}"; shift 2 ;;
    --no-pull) pull=0; shift ;;
    --no-build) build=0; shift ;;
    --age-recipient|--gpg-recipient) backup_args+=("$1" "${2:?$1 needs a key}"); shift 2 ;;
    -h|--help) print_help "$0"; exit 0 ;;
    *) die 2 "unknown option: $1 (see --help)" ;;
  esac
done
[ -n "$backup_dir" ] || die 2 "say where the pre-upgrade backup goes: --backup-dir DIR"
# A ref reaches git as an argument; hold it to the shape of a branch, tag or commit so it can
# never be read as an option.
if [ -n "$ref" ] && { ! [[ "$ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || [[ "$ref" == *..* ]]; }; then
  die 2 "--ref must be a branch, tag or commit name"
fi

require_docker
if [ "$pull" -eq 1 ]; then
  git rev-parse --git-dir >/dev/null 2>&1 \
    || die 1 "this folder is not a git checkout. Update the code yourself and use --no-pull."
  # Your .env is ignored by git; anything else changed here would be overwritten or merged into.
  [ -z "$(git status --porcelain --untracked-files=no)" ] \
    || die 1 "files in this checkout have been changed locally (git status). Commit or undo them first."
fi
old_rev=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
old_schema=""

# 1. The backup. If it fails, nothing else happens.
ensure_db_up
old_schema=$(schema_version "")
log "Backing up before the upgrade..."
backup=$("$HERE/backup.sh" --out "$backup_dir" --label pre-upgrade "${backup_args[@]}" | tail -1) \
  || die 1 "the backup failed, so nothing was upgraded"

rollback_help() {
  log ""
  log "To go back to the version you had (commit $old_rev, schema ${old_schema:-none}):"
  log "  git checkout $old_rev"
  log "  docker compose build"
  case "$backup" in
    *.age) log "  deploy/restore.sh --replace $backup --age-identity <your age private key>" ;;
    *) log "  deploy/restore.sh --replace $backup" ;;
  esac
  log "Restoring the backup is not optional: the new version may have changed the database in ways"
  log "the old one does not understand, and migrations cannot be run backwards."
}

# 2. The new code.
if [ "$pull" -eq 1 ]; then
  if [ -n "$ref" ]; then
    git fetch --tags origin
    if git rev-parse --verify --quiet --end-of-options "refs/remotes/origin/$ref" >/dev/null; then
      target="origin/$ref"
    else
      target=$(git rev-parse --verify --quiet --end-of-options "$ref^{commit}") \
        || die 1 "no branch, tag or commit called '$ref' in origin"
    fi
    git -c advice.detachedHead=false checkout --detach --end-of-options "$target"
  else
    git pull --ff-only
  fi
fi
new_rev=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
log "Code: $old_rev -> $new_rev"

# 3. Images. Budget Owl's images are built here from the checkout; they are not published to a
#    registry yet. --pull fetches patched base images (postgres, nginx, the JRE) as it goes.
if [ "$build" -eq 1 ]; then
  log "Building..."
  dc build --pull >&2 || { log "The build failed. Nothing is running the new version."; rollback_help; exit 1; }
fi
dc pull "$DB_SERVICE" >&2 || log "warning: could not pull a newer database image; keeping the one you have"

# 4. Start. Flyway applies any new migrations as the api starts; it is healthy only after they
#    have all succeeded.
dc up -d >&2
log "Waiting for the app to become healthy (migrations run now)..."
if wait_healthy "$API_SERVICE" 300; then
  log "Upgraded: $old_rev (schema ${old_schema:-none}) -> $new_rev (schema $(schema_version ""))."
  log "Pre-upgrade backup: $backup"
  rollback_help
else
  log "The app did not become healthy. Its log: docker compose logs $API_SERVICE"
  log "A migration that fails rolls back on its own, but migrations before it in the same upgrade"
  log "stay applied, so the database may now be between versions."
  rollback_help
  exit 1
fi
