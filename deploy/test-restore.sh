#!/usr/bin/env bash
# Test a backup by restoring it into a SEPARATE copy of Budget Owl, beside the real one.
#
#   deploy/test-restore.sh BACKUP                         # restore into the test copy
#   deploy/test-restore.sh BACKUP.age --age-identity KEY  # an encrypted backup
#   deploy/test-restore.sh --clean                        # delete the test copy afterwards
#
# Options:
#   --age-identity FILE   the age private key, for an .age backup.
#   --port N              [8481] the test copy answers on http://localhost:N.
#   --clean               stop the test copy and delete its database. Your real one is untouched.
#   -h, --help            this text.
#
# The test copy is its own Compose project, "budgetowl-restore-test", with its own database
# volume, on 127.0.0.1 only. Your real instance keeps running and is never touched: the project
# name is fixed in this script, so neither the restore nor --clean can reach it.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

export COMPOSE_PROJECT_NAME=budgetowl-restore-test
export BIND_ADDRESS=127.0.0.1
port=8481
clean=0
restore_args=()

while [ $# -gt 0 ]; do
  case "$1" in
    --age-identity) restore_args+=(--age-identity "${2:?--age-identity needs a file}"); shift 2 ;;
    --port) port="${2:?--port needs a number}"; shift 2 ;;
    --clean) clean=1; shift ;;
    -h|--help) print_help "$0"; exit 0 ;;
    -*) die 2 "unknown option: $1 (see --help)" ;;
    *) restore_args+=("$1"); shift ;;
  esac
done
[[ "$port" =~ ^[0-9]{2,5}$ ]] || die 2 "--port takes a port number"
export WEB_PORT="$port"
require_docker

if [ "$clean" -eq 1 ]; then
  dc down -v --remove-orphans >&2
  log "The test copy and its database are gone. Your real instance was not touched."
  exit 0
fi
[ ${#restore_args[@]} -gt 0 ] || die 2 "say which backup to test (see --help)"

if [ -n "$(dc ps -a -q 2>/dev/null)" ] \
   || docker volume inspect "${COMPOSE_PROJECT_NAME}_db-data" >/dev/null 2>&1; then
  die 1 "a test copy already exists. Remove it first: deploy/test-restore.sh --clean"
fi

dc up -d "$DB_SERVICE" >&2
# The test copy is new and empty, so no --replace: restore.sh will refuse if that is not so.
"$HERE/restore.sh" "${restore_args[@]}"

cat >&2 <<EOF

The test copy is running at http://localhost:$port on this machine.
Sign in there as you normally do and check your accounts and recent transactions.
(Signing in needs the browser on this machine, or an SSH tunnel: ssh -L $port:localhost:$port <server>.)

When you are done:  deploy/test-restore.sh --clean
EOF
