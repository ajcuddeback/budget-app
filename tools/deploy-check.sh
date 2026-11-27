#!/usr/bin/env bash
# Prove a backup restores. Brings the real Compose stack up from nothing, sets up a household
# through the API, backs it up, DESTROYS the database volume, restores, and checks the data came
# back — then checks the two refusals (a database with data in it, a checksum that does not match)
# and an encrypted --replace round trip. A restore nobody has tested is a hope.
#
#   tools/deploy-check.sh                 # build the images if they are not there, then check
#   tools/deploy-check.sh --keep-images   # leave the images afterwards (default: leave them too)
#   tools/deploy-check.sh --remove-images # remove this run's two app images when done
#
# It runs as its OWN Compose project (COMPOSE_PROJECT_NAME, default budgetowl-deploycheck) with
# its own env file of throwaway random passwords, so it never reads your .env and never touches
# your real stack or its volume. It refuses any project name without "check" in it, because it
# deletes that project's volumes. Published on 127.0.0.1:${WEB_PORT:-8480}.
#
# Images: ghcr.io/ajcuddeback/budget-owl-{api,web}:${BUDGET_OWL_VERSION:-deploycheck}. If they
# are not present locally they are built with `docker compose build`.

# Commands passed to the db container are single-quoted on purpose; its shell expands them.
# shellcheck disable=SC2016
set -euo pipefail
cd "$(dirname "$0")/.."

remove_images=0
while [ $# -gt 0 ]; do
  case "$1" in
    --remove-images) remove_images=1; shift ;;
    --keep-images) remove_images=0; shift ;;
    -h|--help) sed -n '2,/^[^#]/{/^#/!q;s/^# \{0,1\}//;p;}' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-budgetowl-deploycheck}"
case "$COMPOSE_PROJECT_NAME" in
  *check*) ;;
  *) echo "refusing: COMPOSE_PROJECT_NAME '$COMPOSE_PROJECT_NAME' has no 'check' in it, and this" \
          "deletes that project's volumes" >&2; exit 2 ;;
esac
port="${WEB_PORT:-8480}"
version="${BUDGET_OWL_VERSION:-deploycheck}"
base="http://127.0.0.1:$port"

# Nothing from the caller's shell or .env leaks in: only the variables written below apply.
unset DB_NAME DB_USER DB_PASSWORD DB_APP_USER DB_APP_PASSWORD BIND_ADDRESS WEB_PORT \
      BUDGET_OWL_VERSION BACKUP_DIR
work=$(mktemp -d)
chmod 700 "$work"
export COMPOSE_ENV_FILES="$work/check.env"
# Throwaway credentials, generated per run; nothing secret is in this file or the repository.
{
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'DB_APP_USER=budgetowl_app\n'
  printf 'DB_APP_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'WEB_PORT=%s\n' "$port"
  printf 'BUDGET_OWL_VERSION=%s\n' "$version"
} > "$COMPOSE_ENV_FILES"
chmod 600 "$COMPOSE_ENV_FILES"

# shellcheck disable=SC2329  # invoked by the EXIT trap
cleanup() {
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  if [ "$remove_images" -eq 1 ]; then
    docker rmi "ghcr.io/ajcuddeback/budget-owl-api:$version" \
               "ghcr.io/ajcuddeback/budget-owl-web:$version" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work"
}
trap cleanup EXIT

failed=0
ok()   { printf '  ok   %s\n' "$1"; }
bad()  { printf '  FAIL %s\n' "$1"; failed=1; }
check() { if [ "$2" = ok ]; then ok "$1"; else bad "$1"; fi; }
step() { printf '\n%s\n' "$1"; }
quiet() { "$@" >"$work/last.log" 2>&1 || { sed 's/^/       /' "$work/last.log" | tail -30; return 1; }; }

wait_api() {
  local id status
  for _ in $(seq 1 100); do
    id=$(docker compose ps -q api 2>/dev/null || true)
    status=$([ -n "$id" ] && docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo none)
    [ "$status" = healthy ] && return 0
    sleep 3
  done
  docker compose logs --tail 40 api >&2 || true
  return 1
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
token() {
  curl -fsS -H 'Content-Type: application/json' \
    -d '{"email":"owner@example.com","password":"deploy-check-not-a-real-password","deviceLabel":"deploy-check"}' \
    "$base/api/auth/token" | json 'd["token"]'
}
account_names() { # account_names <token>
  curl -fsS -H "Authorization: Bearer $1" "$base/api/accounts" \
    | json '",".join(sorted(a["name"] + "=" + a["balance"] for a in d["content"]))'
}
add_account() { # add_account <token> <name> <balance>
  curl -fsS -o /dev/null -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"type\":\"CHECKING\",\"currency\":\"USD\",\"openingBalance\":\"$3\"}" \
    "$base/api/accounts"
}
db_query() { # db_query <<SQL — one value, as the owning role, inside the db container
  docker compose exec -T db sh -c 'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}
pool_role() { # the role(s) the api's connection pool is connected as right now
  db_query <<'SQL'
SELECT coalesce(string_agg(DISTINCT usename, ','), 'none') FROM pg_stat_activity
 WHERE datname = current_database() AND backend_type = 'client backend'
   AND pid <> pg_backend_pid();
SQL
}

step "Stack from nothing (project $COMPOSE_PROJECT_NAME, $base)"
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
if docker image inspect "ghcr.io/ajcuddeback/budget-owl-api:$version" >/dev/null 2>&1 \
   && docker image inspect "ghcr.io/ajcuddeback/budget-owl-web:$version" >/dev/null 2>&1; then
  ok "images $version present"
else
  if quiet docker compose build; then ok "images built"; else bad "images could not be built"; exit 1; fi
fi
if quiet docker compose up -d --no-build; then ok "docker compose up"; else bad "docker compose up"; exit 1; fi
if wait_api; then ok "api healthy"; else bad "api never became healthy"; exit 1; fi

step "A household, through the API"
curl -fsS -c "$work/jar" -o /dev/null "$base/api/setup/status"
xsrf=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$work/jar")
code=$(curl -sS -o /dev/null -w '%{http_code}' -b "$work/jar" -H "X-XSRF-TOKEN: $xsrf" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","displayName":"Deploy Check","password":"deploy-check-not-a-real-password","householdName":"Deploy Check","baseCurrency":"USD"}' \
  "$base/api/setup/first-user")
check "first account created (got $code)" "$([ "$code" = 201 ] && echo ok)"
tok=$(token)
add_account "$tok" "Everyday Checking" "2480.15"
add_account "$tok" "Rainy Day" "-17.0001"
before=$(account_names "$tok")
check "two accounts recorded ($before)" "$([ "$before" = 'Everyday Checking=2480.1500,Rainy Day=-17.0001' ] && echo ok)"
role=$(pool_role)
check "the api's pool connects as budgetowl_app, not the owner ($role)" \
  "$([ "$role" = budgetowl_app ] && echo ok)"

# A browser sign-in, so there is a live web session for the backup to leave out.
code=$(curl -sS -o /dev/null -w '%{http_code}' -b "$work/jar" -c "$work/jar" -H "X-XSRF-TOKEN: $xsrf" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"deploy-check-not-a-real-password"}' "$base/api/auth/login")
check "browser sign-in (got $code)" "$([ "$code" = 200 ] || [ "$code" = 204 ] && echo ok)"
sessions=$(db_query <<<'SELECT count(*) FROM spring_session;')

step "Back up"
mkdir -m 700 "$work/backups"
plain=$(deploy/backup.sh --out "$work/backups" 2>"$work/backup.log" | tail -1) \
  || { sed 's/^/       /' "$work/backup.log"; bad "backup.sh"; exit 1; }
ok "backup.sh wrote $(basename "$plain")"
check "backup is mode 0600 ($(stat -c %a "$plain"))" "$([ "$(stat -c %a "$plain")" = 600 ] && echo ok)"
check "checksum is mode 0600" "$([ "$(stat -c %a "$plain.sha256")" = 600 ] && echo ok)"
check "name carries UTC time, version and schema" \
  "$(basename "$plain" | grep -qE "^budgetowl-[0-9]{8}T[0-9]{6}Z-${version}-schema[0-9]+\.dump$" && echo ok)"
check "the live database holds a web session ($sessions)" "$([ "$sessions" -ge 1 ] && echo ok)"
check "  ... and the backup holds no session rows" "$(docker compose exec -T db pg_restore --list \
  < "$plain" | grep -q 'TABLE DATA public spring_session ' && echo no || echo ok)"

encrypted=""
if command -v age >/dev/null 2>&1 && command -v age-keygen >/dev/null 2>&1; then
  mkdir -m 700 "$work/keys"
  age-keygen -o "$work/keys/identity.txt" 2>/dev/null
  recipient=$(age-keygen -y "$work/keys/identity.txt")
  encrypted=$(deploy/backup.sh --out "$work/backups" --age-recipient "$recipient" 2>>"$work/backup.log" \
    | tail -1) || { bad "encrypted backup"; encrypted=""; }
  if [ -n "$encrypted" ]; then
    check "age backup written ($(basename "$encrypted"))" "$([ -s "$encrypted" ] && echo ok)"
    check "  ... and it is not a readable dump without the key" \
      "$(head -c 5 "$encrypted" | grep -q PGDMP && echo no || echo ok)"
  fi
  cp "$work/keys/identity.txt" "$work/backups/stray-key.txt"
  if deploy/backup.sh --out "$work/backups" --age-recipient "$recipient" >/dev/null 2>&1; then
    bad "backup refuses a folder holding a private key"
  else
    ok "backup refuses a folder holding a private key"
  fi
  rm -f "$work/backups/stray-key.txt"
else
  printf '  -    age not installed: the encrypted round trip is not checked\n'
fi

step "Refusals: nothing is changed"
set +e
deploy/restore.sh "$plain" >"$work/r1.log" 2>&1; code=$?
set -e
check "restore over a database with data in it, without --replace, refuses (exit $code)" \
  "$([ "$code" = 4 ] && echo ok)"
check "  ... and says to use --replace" "$(grep -q -- '--replace' "$work/r1.log" && echo ok)"
check "  ... and the app is still up with the same data" \
  "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"

tampered="$work/backups/tampered.dump"
cp "$plain" "$tampered"; cp "$plain.sha256" "$tampered.sha256"
printf 'X' | dd of="$tampered" bs=1 seek=4096 conv=notrunc status=none
set +e
deploy/restore.sh "$tampered" --replace >"$work/r2.log" 2>&1; code=$?
set -e
check "a changed backup is refused on its checksum, even with --replace (exit $code)" \
  "$([ "$code" = 3 ] && grep -q 'CHECKSUM MISMATCH' "$work/r2.log" && echo ok)"
check "  ... and no safety backup was needed, because nothing was touched" \
  "$(find "$work/backups" -name '*pre-restore*' | grep -q . && echo no || echo ok)"

step "Disaster: the database volume is destroyed"
if quiet docker compose down -v; then ok "down -v (volume deleted)"; else bad "down -v (volume deleted)"; fi
check "volume is gone" "$(docker volume ls -q | grep -qx "${COMPOSE_PROJECT_NAME}_db-data" && echo no || echo ok)"
if quiet docker compose up -d db; then ok "a new, empty database"; else bad "a new, empty database"; fi
if deploy/restore.sh "$plain" >"$work/r3.log" 2>&1; then ok "restore.sh onto the empty install"
else sed 's/^/       /' "$work/r3.log" | tail -30; bad "restore.sh onto the empty install"; fi
after=$(account_names "$(token)" || echo "unreadable")
check "the accounts are back, to the fourth decimal ($after)" "$([ "$after" = "$before" ] && echo ok)"
role=$(pool_role)
check "the api still connects as budgetowl_app after a restore ($role)" \
  "$([ "$role" = budgetowl_app ] && echo ok)"

if [ -n "$encrypted" ]; then
  step "Encrypted --replace over live data"
  tok=$(token)
  add_account "$tok" "Added After Backup" "1.00"
  set +e
  deploy/restore.sh "$encrypted" --replace --age-identity "$work/keys/identity.txt" \
    >"$work/r4.log" 2>&1; code=$?
  set -e
  if [ "$code" = 0 ]; then ok "restore.sh --replace from the age backup"
  else sed 's/^/       /' "$work/r4.log" | tail -30; bad "restore.sh --replace from the age backup (exit $code)"; fi
  check "the data is the backup's again ($(account_names "$(token)"))" \
    "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"
  safety=$(find "$work/backups" -name '*-pre-restore.dump' | head -1)
  check "a safety backup of what was replaced exists, mode 0600" \
    "$([ -n "$safety" ] && [ "$(stat -c %a "$safety")" = 600 ] && echo ok)"
  check "  ... and it holds the account added after the backup" "$([ -n "$safety" ] && \
    docker compose exec -T db sh -c 'pg_restore --data-only -t accounts -f - 2>/dev/null' < "$safety" \
      | grep -q 'Added After Backup' && echo ok)"
fi

step "Rollback guard: a backup from a newer version is refused"
# Pretend a later version ran migration 9999, back that up, and try to restore it with this one.
db_query >/dev/null <<'SQL'
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT max(installed_rank) + 1, '9999', 'from the future', 'SQL', 'V9999__from_the_future.sql', 0,
       current_user, 0, true FROM flyway_schema_history;
SQL
future=$(deploy/backup.sh --out "$work/backups" --label future 2>>"$work/backup.log" | tail -1) || future=""
check "the backup's name says schema 9999 ($(basename "${future:-none}"))" \
  "$(basename "${future:-none}" | grep -q -- '-schema9999-future\.dump$' && echo ok)"
db_query >/dev/null <<<"DELETE FROM flyway_schema_history WHERE version = '9999';"
add_account "$(token)" "Still Here" "3.00"
expected=$(account_names "$(token)")
set +e
deploy/restore.sh "$future" --replace >"$work/r5.log" 2>&1; code=$?
set -e
check "restore refuses a backup newer than this version (exit $code)" \
  "$([ "$code" = 5 ] && grep -q 'NEWER Budget Owl' "$work/r5.log" && echo ok)"
# The app was stopped for the restore and is started again on the old database; let it come up.
wait_api || true
check "  ... before replacing anything: the data is as it was" \
  "$([ "$(account_names "$(token)" || true)" = "$expected" ] && echo ok)"

step "Resources"
mapfile -t ids < <(docker compose ps -q)
docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}}' "${ids[@]}" 2>/dev/null || true
printf '  database on disk: %s\n' \
  "$(docker compose exec -T db du -sh /var/lib/postgresql/data 2>/dev/null | cut -f1)"
printf '  backup size: %s\n' "$(du -h "$plain" | cut -f1)"

echo
if [ "$failed" -eq 0 ]; then echo "deploy check PASSED"; else echo "deploy check FAILED"; fi
exit "$failed"
