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
    sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${BO_DB:-$POSTGRES_DB}" "$@"' \
    sh "$@"
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
# Empty if the image is not present locally (nothing is pulled or built to find out).
image_schema_version() {
  dc run --rm --no-deps -T --pull never --entrypoint sh "$API_SERVICE" -c 'unzip -l app.jar' \
      2>/dev/null \
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
