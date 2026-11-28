#!/usr/bin/env bash
# Test a backup by restoring it into a SEPARATE copy of Budget Owl, beside the real one.
#
#   deploy/test-restore.sh BACKUP                         # restore into the test copy
#   deploy/test-restore.sh BACKUP.age --age-identity KEY  # an encrypted backup
#   deploy/test-restore.sh --clean                        # delete the test copy afterwards
#
# Options:
#   --age-identity FILE   the age private key, for an .age backup.
#   --signers FILE        the allowed_signers line(s) to check the backup against (default: this
#                         server's own). Exactly as for restore.sh.
#   --allow-unsigned      as for restore.sh: only for a file you know you made yourself.
#   --port N              [8481] the test copy answers on http://localhost:N.
#   --clean               stop the test copy and delete its database. Your real one is untouched.
#   -h, --help            this text.
#
# The test copy is its own Compose project, "budgetowl-restore-test", with its own database
# volume, on 127.0.0.1 only, and its OWN throwaway passwords (it never reads your .env's): a
# restore needs none of the old ones. Your real instance keeps running and is never touched: the
# project name is fixed in this script, so neither the restore nor --clean can reach it. The
# backup is checked exactly as a real restore checks it — signature first.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

export COMPOSE_PROJECT_NAME=budgetowl-restore-test
export BIND_ADDRESS=127.0.0.1
state="${XDG_STATE_HOME:-$HOME/.local/state}/budgetowl"
port=8481
clean=0
restore_args=()

while [ $# -gt 0 ]; do
  case "$1" in
    --age-identity) restore_args+=(--age-identity "${2:?--age-identity needs a file}"); shift 2 ;;
    --signers) restore_args+=(--signers "${2:?--signers needs a file}"); shift 2 ;;
    --allow-unsigned) restore_args+=(--allow-unsigned); shift ;;
    --port) port="${2:?--port needs a number}"; shift 2 ;;
    --clean) clean=1; shift ;;
    -h|--help) print_help "$0"; exit 0 ;;
    -*) die 2 "unknown option: $1 (see --help)" ;;
    *) restore_args+=("$1"); shift ;;
  esac
done
[[ "$port" =~ ^[0-9]{2,5}$ ]] || die 2 "--port takes a port number"
require_docker

# The test copy's own settings: random passwords made here, and the image tag your install uses
# (the only thing taken from your .env, and read as a value, not run).
envfile="$state/restore-test.env"
export COMPOSE_ENV_FILES="$envfile"

leftover() { # what is still there of the test copy: containers, then its volume
  docker ps -a -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
  docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
}
if [ "$clean" -eq 1 ]; then
  # Compose needs DB_PASSWORD and friends just to read the file, even to take things down. If
  # the test copy's own settings are gone (deleted, or a different user), make throwaway ones:
  # nothing is started with them, they only let `down -v` run.
  if [ ! -f "$envfile" ]; then
    tmp_env=$(mktemp)
    printf 'DB_PASSWORD=%s\nWEB_PORT=%s\n' "$(openssl rand -hex 24)" "$port" > "$tmp_env"
    export COMPOSE_ENV_FILES="$tmp_env"
  fi
  dc down -v --remove-orphans >&2 || true
  rm -f -- "$envfile" "${tmp_env:-}"
  # Say what is true, not what was hoped.
  if [ -n "$(leftover)" ]; then
    die 1 "the test copy is NOT fully gone; these are still there:
$(leftover | sed 's/^/         /')
       Remove them with: docker rm -f <container> and docker volume rm <volume>"
  fi
  log "The test copy and its database are gone. Your real instance was not touched."
  exit 0
fi
[ ${#restore_args[@]} -gt 0 ] || die 2 "say which backup to test (see --help)"

if [ -f "$envfile" ] || [ -n "$(leftover)" ]; then
  die 1 "a test copy already exists. Remove it first: deploy/test-restore.sh --clean"
fi
( umask 077 && mkdir -p -- "$state" )
version=""
if [ -f .env ]; then
  version=$(sed -n 's/^BUDGET_OWL_VERSION=//p' .env | tail -1 | tr -cd 'A-Za-z0-9._-')
fi
( umask 077
  {
    printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'DB_APP_USER=budgetowl_app\n'
    printf 'DB_APP_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'WEB_PORT=%s\n' "$port"
    printf 'BIND_ADDRESS=127.0.0.1\n'
    [ -z "${BUDGET_OWL_VERSION:-$version}" ] || printf 'BUDGET_OWL_VERSION=%s\n' "${BUDGET_OWL_VERSION:-$version}"
  } > "$envfile" )
export WEB_PORT="$port"

dc up -d "$DB_SERVICE" >&2
# The test copy is new and empty, so no --replace: restore.sh will refuse if that is not so.
"$HERE/restore.sh" "${restore_args[@]}"

cat >&2 <<EOF

The test copy is running at http://localhost:$port on this machine.
Sign in there as you normally do and check your accounts and recent transactions.
(Signing in needs the browser on this machine, or an SSH tunnel: ssh -L $port:localhost:$port <server>.)

When you are done:  deploy/test-restore.sh --clean
EOF
