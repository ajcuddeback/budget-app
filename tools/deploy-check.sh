#!/usr/bin/env bash
# Prove a backup restores, and that a restore refuses what it must. Brings the real Compose stack
# up from nothing, sets up a household through the API, backs it up, DESTROYS the database
# volume, restores onto a "new server" with its own signing key, and checks the data came back.
# Then the refusals: data present without --replace, a damaged checksum, a forged or re-signed
# backup, a backup newer than this version, a database left by an interrupted restore. Then
# rotation, encryption of safety backups, and test-restore.sh. A restore nobody has tested is a
# hope.
#
#   tools/deploy-check.sh                 # build the images if they are not there, then check
#   tools/deploy-check.sh --remove-images # remove this run's two app images when done
#
# It runs as its OWN Compose project (COMPOSE_PROJECT_NAME, default budgetowl-deploycheck) with
# its own env file of throwaway random passwords and its own signing keys, so it never reads your
# .env or /etc/budgetowl and never touches your real stack or its volume. It refuses any project
# name without "check" in it, because it deletes that project's volumes. Published on
# 127.0.0.1:${WEB_PORT:-8480}; test-restore.sh's copy on the next port up.
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
test_port=$((port + 1))
version="${BUDGET_OWL_VERSION:-deploycheck}"
base="http://127.0.0.1:$port"
for tool in age age-keygen ssh-keygen python3 curl openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "deploy check needs $tool" >&2; exit 2; }
done

# Nothing from the caller's shell, .env or /etc/budgetowl leaks in: only what is written below.
unset DB_NAME DB_USER DB_PASSWORD DB_APP_USER DB_APP_PASSWORD BIND_ADDRESS WEB_PORT \
      BUDGET_OWL_VERSION BACKUP_DIR BACKUP_KEEP BACKUP_KEEP_WEEKLY BACKUP_KEEP_MONTHLY \
      BACKUP_AGE_RECIPIENT BACKUP_GPG_RECIPIENT BACKUP_SIGNING_KEY
work=$(mktemp -d)
chmod 700 "$work"
export BACKUP_ENV_FILE="$work/no-backup.env"   # does not exist: no settings file
# Everything the scripts make with mktemp (restore.sh's private copy) lands here, where it can be seen.
mkdir -m 700 "$work/tmp"; export TMPDIR="$work/tmp"
export XDG_STATE_HOME="$work/state"
# "Server 1" made the backups. Its key is made on first use, as on any non-VM install.
export BACKUP_SIGNING_KEY="$work/server1/backup-signing-key"
server1_signers="$work/server1/backup-signing-key.allowed_signers"
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
  [ -z "${netfs:-}" ] || rm -rf -- "$netfs"
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  # test-restore.sh's copy, whatever state it was left in: compose only needs a DB_PASSWORD to
  # read the file, not the right one.
  printf 'DB_PASSWORD=teardown-only\n' > "$work/teardown.env"
  COMPOSE_PROJECT_NAME=budgetowl-restore-test COMPOSE_ENV_FILES="$work/teardown.env" \
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  if [ "$remove_images" -eq 1 ]; then
    docker rmi "ghcr.io/ajcuddeback/budget-owl-api:$version" \
               "ghcr.io/ajcuddeback/budget-owl-web:$version" >/dev/null 2>&1 || true
  fi
  chmod -R u+w "$work" 2>/dev/null || true
  rm -rf -- "$work"
}
trap cleanup EXIT

failed=0
ok()   { printf '  ok   %s\n' "$1"; }
bad()  { printf '  FAIL %s\n' "$1"; failed=1; }
check() { if [ "$2" = ok ]; then ok "$1"; else bad "$1"; fi; }
step() { printf '\n%s\n' "$1"; }
quiet() { "$@" >"$work/last.log" 2>&1 || { sed 's/^/       /' "$work/last.log" | tail -30; return 1; }; }
# expect <exit> <log> <command...> — run it, keep its output, and say whether it exited <exit>.
expect() {
  local want="$1" logf="$2" code; shift 2
  set +e; "$@" >"$logf" 2>&1; code=$?; set -e
  [ "$code" = "$want" ] && return 0
  { echo "       | exit $code, wanted $want: $*"; tail -12 "$logf" | sed 's/^/       | /'; } >&2
  return 1
}

wait_api() { # wait_api [base-url]
  local url="${1:-$base}"
  for _ in $(seq 1 100); do
    curl -fsS -o /dev/null "$url/api/setup/status" 2>/dev/null && return 0
    sleep 3
  done
  return 1
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
token() { # token [base-url]
  curl -fsS -H 'Content-Type: application/json' \
    -d '{"email":"owner@example.com","password":"deploy-check-not-a-real-password","deviceLabel":"deploy-check"}' \
    "${1:-$base}/api/auth/token" | json 'd["token"]'
}
account_names() { # account_names <token> [base-url]
  curl -fsS -H "Authorization: Bearer $1" "${2:-$base}/api/accounts" \
    | json '",".join(sorted(a["name"] + "=" + a["balance"] for a in d["content"]))'
}
add_account() { # add_account <token> <name> <balance>
  curl -fsS -o /dev/null -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"type\":\"CHECKING\",\"currency\":\"USD\",\"openingBalance\":\"$3\"}" \
    "$base/api/accounts"
}
db_query() { # db_query <<SQL — as the owning role, inside the db container
  docker compose exec -T db sh -c 'exec psql -X -At -w -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}
admin_query() { # admin_query <<SQL — in the maintenance database, with :'target'
  docker compose exec -T db sh -c 'exec psql -X -At -w -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres --set=target="$POSTGRES_DB"'
}
pool_role() { # the role(s) the api's connection pool is connected as right now
  db_query <<'SQL'
SELECT coalesce(string_agg(DISTINCT usename, ','), 'none') FROM pg_stat_activity
 WHERE datname = current_database() AND backend_type = 'client backend'
   AND pid <> pg_backend_pid();
SQL
}
in_toc() { # in_toc <dump> <table> — whether the dump holds that table's rows. The listing is read
  # whole before grep sees it: grep -q stopping early would fail the pipe under pipefail.
  local toc
  toc=$(docker compose exec -T db pg_restore --list < "$1")
  grep -q "TABLE DATA public $2 " <<<"$toc"
}
meta_get() { sed -n "s/^$2=//p" "$1"; }

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

step "Rotation never runs for an install with nobody in it"
mkdir -m 700 "$work/empty-rotation"
old="budgetowl-20260101T000000Z-old-schema20.dump"
touch "$work/empty-rotation/$old"; printf 'instance=0123456789abcdef\npeople=yes\n' > "$work/empty-rotation/$old.meta"
if expect 0 "$work/b0.log" deploy/backup.sh --out "$work/empty-rotation" --keep 1; then
  ok "backup of the empty install"; else bad "backup of the empty install"; fi
check "  ... keeps the older real backup (--keep 1 did not remove it)" \
  "$([ -f "$work/empty-rotation/$old" ] && grep -q 'nobody in it' "$work/b0.log" && echo ok)"
check "the signing key was made on first use, mode 0600, with its allowed_signers line" \
  "$([ "$(stat -c %a "$BACKUP_SIGNING_KEY")" = 600 ] && grep -q '^budgetowl-backup namespaces="budgetowl-backup,budgetowl-backup-meta" ssh-ed25519 ' "$server1_signers" && echo ok)"
printed=$(deploy/backup.sh --print-signing-key 2>/dev/null)
check "backup.sh --print-signing-key prints that line" \
  "$([ "$printed" = "$(cat "$server1_signers")" ] && echo ok)"

step "A household, through the API"
curl -fsS -c "$work/jar" -o /dev/null "$base/api/setup/status"
xsrf=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$work/jar")
code=$(curl -sS -o /dev/null -w '%{http_code}' -b "$work/jar" -H "X-XSRF-TOKEN: $xsrf" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","displayName":"Deploy Check","password":"deploy-check-not-a-real-password","householdName":"Deploy Check","baseCurrency":"USD"}' \
  "$base/api/setup/first-user")
check "first account created (got $code)" "$([ "$code" = 201 ] && echo ok)"
tok=$(token) || tok=""
phone_token="$tok"
add_account "$tok" "Everyday Checking" "2480.15" || bad "could not add an account"
add_account "$tok" "Rainy Day" "-17.0001" || bad "could not add an account"
before=$(account_names "$tok" || true)
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
tokens=$(db_query <<<'SELECT count(*) FROM auth_tokens;')
# A pending invitation, so there is one for the backup to leave out.
curl -fsS -o /dev/null -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
  -d '{"email":"invited@example.com","role":"MEMBER"}' "$base/api/households/current/invitations" || true
invitations=$(db_query <<<'SELECT count(*) FROM household_invitations;')

step "Back up"
mkdir -m 700 "$work/backups"
plain=$(deploy/backup.sh --out "$work/backups" 2>"$work/backup.log" | tail -1) \
  || { sed 's/^/       /' "$work/backup.log"; bad "backup.sh"; exit 1; }
ok "backup.sh wrote $(basename "$plain")"
check "backup, .sha256, .sig and .meta are all mode 0600" "$(for x in "" .sha256 .sig .meta; do \
  [ "$(stat -c %a "$plain$x")" = 600 ] || echo no; done | grep -q no && echo no || echo ok)"
check "name carries UTC time, version and schema" \
  "$(basename "$plain" | grep -qE "^budgetowl-[0-9]{8}T[0-9]{6}Z-${version}-schema[0-9]+\.dump$" && echo ok)"
check "the signature verifies against this server's line" "$(ssh-keygen -Y verify -f "$server1_signers" \
  -I budgetowl-backup -n budgetowl-backup -s "$plain.sig" < "$plain" >/dev/null 2>&1 && echo ok)"
check "the live database holds a web session ($sessions) and phone tokens ($tokens)" \
  "$([ "$sessions" -ge 1 ] && [ "$tokens" -ge 1 ] && echo ok)"
check "  ... and the backup holds no session rows" "$(in_toc "$plain" spring_session && echo no || echo ok)"
check "  ... and no phone-token rows" "$(in_toc "$plain" auth_tokens && echo no || echo ok)"
check "  ... and no invitation rows (the live database holds $invitations)" \
  "$([ "$invitations" -ge 1 ] && ! in_toc "$plain" household_invitations && echo ok)"
check "the .meta is signed in its own namespace, names its backup and carries its SHA-256" \
  "$(ssh-keygen -Y verify -f "$server1_signers" \
  -I budgetowl-backup -n budgetowl-backup-meta -s "$plain.meta.sig" < "$plain.meta" >/dev/null 2>&1 \
  && grep -qx "backup=$(basename "$plain")" "$plain.meta" \
  && grep -qx "sha256=$(sha256sum < "$plain" | awk '{print $1}')" "$plain.meta" && echo ok)"
check "  ... but does hold the accounts" "$(in_toc "$plain" accounts && echo ok)"

mkdir -m 700 "$work/keys"
age-keygen -o "$work/keys/identity.txt" 2>/dev/null
recipient=$(age-keygen -y "$work/keys/identity.txt")
encrypted=$(deploy/backup.sh --out "$work/backups" --age-recipient "$recipient" 2>>"$work/backup.log" \
  | tail -1) || { bad "encrypted backup"; encrypted=""; }
check "age backup written and signed ($(basename "$encrypted"))" "$([ -s "$encrypted" ] && [ -s "$encrypted.sig" ] && echo ok)"
check "  ... and it is not a readable dump without the key" \
  "$(head -c 5 "$encrypted" | grep -q PGDMP && echo no || echo ok)"

if expect 1 "$work/b1.log" deploy/backup.sh --out "$work/backups"; then
  check "a plaintext backup into a folder of encrypted ones is refused" \
    "$(grep -q 'already holds encrypted backups' "$work/b1.log" && echo ok)"
else bad "a plaintext backup into a folder of encrypted ones is refused"; fi
mkdir -m 700 "$work/mixed"; cp "$encrypted" "$work/mixed/"
check "  ... unless --allow-plaintext" "$(expect 0 "$work/b2.log" deploy/backup.sh --out "$work/mixed" --allow-plaintext && echo ok)"

cp "$work/keys/identity.txt" "$work/backups/budgetowl-backup-key.txt"
check "backup refuses a folder holding an age key, even one named budgetowl-backup-key.txt" \
  "$(expect 1 "$work/b3.log" deploy/backup.sh --out "$work/backups" --age-recipient "$recipient" && grep -q 'PRIVATE key' "$work/b3.log" && echo ok)"
rm -f "$work/backups/budgetowl-backup-key.txt"
cp "$BACKUP_SIGNING_KEY" "$work/backups/stray-signing-key"
check "  ... or a copy of the signing key" \
  "$(expect 1 "$work/b4.log" deploy/backup.sh --out "$work/backups" --age-recipient "$recipient" && grep -q 'PRIVATE key' "$work/b4.log" && echo ok)"
rm -f "$work/backups/stray-signing-key"

# Two backups in the same second would share a name. A `date` that never moves forces it.
mkdir -m 700 "$work/fakebin" "$work/same-second"
printf '#!/bin/sh\ncase "$*" in *%%Y%%m%%dT*) echo 20260101T000000Z ;; *) exec /bin/date "$@" ;; esac\n' \
  > "$work/fakebin/date"; chmod 700 "$work/fakebin/date"
PATH="$work/fakebin:$PATH" deploy/backup.sh --out "$work/same-second" >/dev/null 2>&1 || true
firstsum=$(sha256sum "$work/same-second/"*.dump 2>/dev/null | awk '{print $1}')
check "a second backup with the same name is refused, and the first is not overwritten" \
  "$(expect 1 "$work/b7.log" env PATH="$work/fakebin:$PATH" deploy/backup.sh --out "$work/same-second" \
     && grep -q 'not overwriting' "$work/b7.log" \
     && [ -n "$firstsum" ] && [ "$(sha256sum "$work/same-second/"*.dump | awk '{print $1}')" = "$firstsum" ] && echo ok)"

mkdir -m 777 "$work/open"; mkdir -m 700 "$work/open/backups"
check "backup refuses a folder inside one that others can write to" \
  "$(expect 1 "$work/b5.log" deploy/backup.sh --out "$work/open/backups" && grep -q 'writable by other users' "$work/b5.log" && echo ok)"
mkdir -m 700 "$work/real"; ln -s "$work/real" "$work/link"
check "a symlinked --out works" "$(expect 0 "$work/b6.log" deploy/backup.sh --out "$work/link" && echo ok)"
check "--keep 0 is refused, not taken as 'delete them all'" \
  "$(expect 2 "$work/b8.log" deploy/backup.sh --out "$work/real" --keep 0 && echo ok)"
# backup.env: read by every script, a trust anchor, and an empty variable really does override it.
mkdir -m 700 "$work/settings" "$work/from-env" "$work/override"
printf 'BACKUP_AGE_RECIPIENT=%s\n' "$recipient" > "$work/settings/backup.env"; chmod 600 "$work/settings/backup.env"
check "backup.env's recipient is used when nothing overrides it" \
  "$(BACKUP_ENV_FILE="$work/settings/backup.env" deploy/backup.sh --out "$work/from-env" 2>/dev/null | tail -1 | grep -q '\.dump\.age$' && echo ok)"
check "  ... and an EMPTY BACKUP_AGE_RECIPIENT in the environment really does override it" \
  "$(BACKUP_ENV_FILE="$work/settings/backup.env" BACKUP_AGE_RECIPIENT='' deploy/backup.sh --out "$work/override" 2>/dev/null | tail -1 | grep -q '\.dump$' && echo ok)"
chmod 666 "$work/settings/backup.env"
check "a backup.env that others can change is refused" \
  "$(expect 1 "$work/b9.log" env BACKUP_ENV_FILE="$work/settings/backup.env" deploy/backup.sh --out "$work/override" \
     && grep -q 'can be changed by other users' "$work/b9.log" && echo ok)"
chmod 600 "$work/settings/backup.env"
if [ "$(id -u)" = 0 ]; then
  mkdir -m 755 "$work/foreign"; chown 65534 "$work/foreign"; mkdir -m 700 "$work/foreign/backups"
  check "a backup folder inside one owned by another user is refused" \
    "$(expect 1 "$work/b10.log" deploy/backup.sh --out "$work/foreign/backups" && grep -q "is not root's or yours" "$work/b10.log" && echo ok)"
  check "  ... and, as its owner is not this install's operator, does not tell root to become them" \
    "$(grep -q "neither root nor this install's operator" "$work/b10.log" \
       && ! grep -q 'sudo -u nobody' "$work/b10.log" && echo ok)"
  check "  ... but when the owner IS the operator, says to run it as them instead of root" \
    "$(expect 1 "$work/b10b.log" env BUDGETOWL_OPERATOR=nobody deploy/backup.sh --out "$work/foreign/backups" \
       && grep -q 'Run this as the operator (nobody), not root: sudo -u nobody' "$work/b10b.log" && echo ok)"
else
  printf '  -    not root: the folder-owned-by-another-user case needs chown, so it is not checked\n'
fi

step "Refusals: nothing is changed"
check "restore over a database with data in it, without --replace, refuses (exit 4)" \
  "$(expect 4 "$work/r1.log" deploy/restore.sh "$plain" && grep -q -- '--replace' "$work/r1.log" && echo ok)"
check "  ... and the app is still up with the same data" \
  "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"

pname=$(basename "$plain")
forge() { # forge <case> — a folder of its own, and the path a forgery of $plain gets in it
  [ -d "$work/forged" ] || mkdir -m 700 "$work/forged"
  [ -d "$work/forged/$1" ] || mkdir -m 700 "$work/forged/$1"
  printf '%s\n' "$work/forged/$1/${2:-$pname}"
}
f=$(forge damaged)
cp "$plain" "$f"; cp "$plain.sig" "$f.sig"
# Change the checksum's first hex digit, to a value it is not (1 time in 16 it already was 0).
awk '{ c = substr($1, 1, 1); print (c == "0" ? "1" : "0") substr($1, 2) "  " $2 }' "$plain.sha256" > "$f.sha256"
check "a signed backup whose checksum does not match is refused (exit 3)" \
  "$(expect 3 "$work/r2.log" deploy/restore.sh "$f" --replace && grep -q 'CHECKSUM MISMATCH' "$work/r2.log" && echo ok)"

f=$(forge changed)
cp "$plain" "$f"; cp "$plain.sig" "$f.sig"
printf 'X' | dd of="$f" bs=1 seek=4096 conv=notrunc status=none
sha256sum "$f" > "$f.sha256"
check "a changed backup with a recomputed checksum is refused on its signature (exit 6)" \
  "$(expect 6 "$work/r3.log" deploy/restore.sh "$f" --replace && grep -q 'BAD SIGNATURE' "$work/r3.log" && echo ok)"

f=$(forge age "$(basename "$encrypted")")
age -r "$recipient" -o "$f" "$plain"; sha256sum "$f" > "$f.sha256"
check "an age file anyone could encrypt to the operator's key, with no signature, is refused (exit 6)" \
  "$(expect 6 "$work/r4.log" deploy/restore.sh "$f" --replace --age-identity "$work/keys/identity.txt" && echo ok)"
cp "$encrypted.sig" "$f.sig"
check "  ... and with a real backup's signature beside it (exit 6)" \
  "$(expect 6 "$work/r5.log" deploy/restore.sh "$f" --replace --age-identity "$work/keys/identity.txt" && echo ok)"

mkdir -m 700 "$work/attacker-key"
ssh-keygen -q -t ed25519 -N '' -C attacker -f "$work/attacker-key/key" </dev/null
f=$(forge attacker)
attacker_dump="$f"
cp "$plain" "$f"; sha256sum "$f" > "$f.sha256"
ssh-keygen -Y sign -q -f "$work/attacker-key/key" -n budgetowl-backup "$f" </dev/null
check "a backup signed by another key is refused (exit 6)" \
  "$(expect 6 "$work/r6.log" deploy/restore.sh "$f" --replace && grep -q 'BAD SIGNATURE' "$work/r6.log" && echo ok)"

# The trust anchor itself: whoever can change the signers file chooses what is trusted.
cp "$server1_signers" "$work/attacker-key/open.allowed_signers"; chmod 666 "$work/attacker-key/open.allowed_signers"
check "a signers file others can change is refused (exit 6)" \
  "$(expect 6 "$work/r6b.log" deploy/restore.sh "$plain" --replace --signers "$work/attacker-key/open.allowed_signers" \
     && grep -q 'can be changed by other users' "$work/r6b.log" && echo ok)"
cp "$server1_signers" "$work/backups/trusted.allowed_signers"
check "a signers file in the backup's own folder is refused (exit 6)" \
  "$(expect 6 "$work/r6c.log" deploy/restore.sh "$plain" --replace --signers "$work/backups/trusted.allowed_signers" \
     && grep -q 'is in the backup folder' "$work/r6c.log" && echo ok)"
rm -f "$work/backups/trusted.allowed_signers"
mkdir -m 700 "$work/open/signers"; cp "$server1_signers" "$work/open/signers/trusted.allowed_signers"
check "a signers file in a folder others can rearrange is refused (exit 6)" \
  "$(expect 6 "$work/r6e.log" deploy/restore.sh "$plain" --replace --signers "$work/open/signers/trusted.allowed_signers" \
     && grep -q 'is writable by other users' "$work/r6e.log" && echo ok)"
# A network filesystem: who owns a file there is whatever its writer said. /dev/shm (tmpfs) stands
# in for one, through BUDGETOWL_EXTRA_NETWORK_FS, which can only ADD types to the list.
netfs=$(mktemp -d -p /dev/shm budgetowl-deploycheck.XXXXXX)
cp "$server1_signers" "$netfs/trusted.allowed_signers"
check "a signers file on a network filesystem is refused (exit 6)" \
  "$(expect 6 "$work/r6f.log" env BUDGETOWL_EXTRA_NETWORK_FS=tmpfs deploy/restore.sh "$plain" --replace \
       --signers "$netfs/trusted.allowed_signers" && grep -q 'is on a network filesystem' "$work/r6f.log" && echo ok)"
check "  ... while the same file from a local disk is accepted (the stand-in, not the file, is the cause)" \
  "$(expect 4 "$work/r6g.log" deploy/restore.sh "$plain" --signers "$netfs/trusted.allowed_signers" && echo ok)"
f=$(forge metaswap)
cp "$plain.meta" "$f"; cp "$plain.meta.sig" "$f.sig"; sha256sum "$f" > "$f.sha256"
check "a .meta and its signature, renamed to look like a backup, are refused (exit 6)" \
  "$(expect 6 "$work/r6h.log" deploy/restore.sh "$f" --replace && grep -q 'BAD SIGNATURE' "$work/r6h.log" && echo ok)"
# TMPDIR: never on a network filesystem, never in the backup folder; stale copies are cleared.
check "a TMPDIR on a network filesystem is refused" \
  "$(expect 1 "$work/r6i.log" env TMPDIR="$netfs" BUDGETOWL_EXTRA_NETWORK_FS=tmpfs deploy/restore.sh "$plain" --replace \
     && grep -q 'TMPDIR .* is on a network filesystem' "$work/r6i.log" && echo ok)"
check "a TMPDIR inside the backup folder is refused" \
  "$(expect 1 "$work/r6j.log" env TMPDIR="$work/backups" deploy/restore.sh "$plain" --replace \
     && grep -q 'TMPDIR .* is inside the backup folder' "$work/r6j.log" && echo ok)"
check "a TMPDIR others can write to (and not sticky) is refused" \
  "$(expect 1 "$work/r6k.log" env TMPDIR="$work/open" deploy/restore.sh "$plain" --replace \
     && grep -q 'TMPDIR .* is writable by other users' "$work/r6k.log" && echo ok)"
mkdir -m 700 "$work/links"
ln -s "$work/open/signers/trusted.allowed_signers" "$work/links/trusted.allowed_signers"
check "a signers file reached through a symlink is judged by where it really is (exit 6)" \
  "$(expect 6 "$work/r6l.log" deploy/restore.sh "$plain" --replace --signers "$work/links/trusted.allowed_signers" \
     && grep -q 'is writable by other users' "$work/r6l.log" && echo ok)"

# The name is not signed; the archive's own creation time is. An old backup renamed to a later
# date must not pass for a newer one.
later=$(date -u -d '+3 days' +%Y%m%dT%H%M%SZ)
f=$(forge renamed "${pname/#budgetowl-????????T??????Z/budgetowl-$later}")
for x in "" .sig .sha256; do cp "$plain$x" "$f$x"; done
check "a signed backup renamed to a date three days later is refused (exit 8)" \
  "$(expect 8 "$work/r6d.log" deploy/restore.sh "$f" --replace && grep -q 'but its name says' "$work/r6d.log" && echo ok)"
check "  ... and says when it was really made" "$(grep -q '^Made at 20' "$work/r6d.log" && echo ok)"
check "  ... and no safety backup was needed for any of these, because nothing was touched" \
  "$(find "$work/backups" "$work/forged" -name '*pre-restore*' | grep -q . && echo no || echo ok)"
check "  ... and the data is as it was" "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"

f=$(forge unsigned)
cp "$plain" "$f"; sha256sum "$f" > "$f.sha256"
check "a restore whose safety backup would be plaintext among encrypted ones replaces nothing" \
  "$(expect 1 "$work/r7a.log" deploy/restore.sh "$f" --replace --allow-unsigned --safety-out "$work/backups" \
     && grep -q 'already holds encrypted backups' "$work/r7a.log" && grep -q 'nothing was replaced' "$work/r7a.log" && echo ok)"
wait_api || true
mkdir -m 700 "$work/plain-safety"
check "an unsigned backup restores with --allow-unsigned" \
  "$(expect 0 "$work/r7.log" deploy/restore.sh "$f" --replace --allow-unsigned --safety-out "$work/plain-safety" && echo ok)"
check "  ... and says plainly that it runs as the database superuser" \
  "$(grep -q 'WARNING: --allow-unsigned' "$work/r7.log" && grep -q 'SUPERUSER' "$work/r7.log" && echo ok)"
check "  ... and the database from before was dropped once the app was healthy" \
  "$([ "$(admin_query <<<"SELECT count(*) FROM pg_database WHERE datname LIKE 'budgetowl_restore_%';")" = 0 ] && echo ok)"

step "The share cannot show one file to the check and another to the restore"
# A forgery with a row the real backup does not have, then a restore of the real backup during
# which, as soon as the signature has been checked, the file on the share is swapped for it.
add_account "$(token)" "Forged Row" "999.00" || bad "could not add an account"
mkdir -m 700 "$work/forgery-src"
forgery=$(deploy/backup.sh --out "$work/forgery-src" 2>/dev/null | tail -1) || forgery=""
mkdir -m 700 "$work/share"
shared="$work/share/$pname"
for x in "" .sig .sha256; do cp "$plain$x" "$shared$x"; done
# A private copy left by a restore that was killed: a dead pid, and a plaintext dump inside.
mkdir -m 700 "$TMPDIR/budgetowl-restore.STALE1"; cp "$plain" "$TMPDIR/budgetowl-restore.STALE1/"
printf '999999999\n' > "$TMPDIR/budgetowl-restore.STALE1/pid"
# One whose pid has been reused: alive (this script), but started at a different time.
mkdir -m 700 "$TMPDIR/budgetowl-restore.REUSED"; printf '%s 1\n' "$$" > "$TMPDIR/budgetowl-restore.REUSED/pid"
# And one that really is in use: this script's pid and its real start time.
my_start=$(stat_line=$(cat /proc/$$/stat); stat_line="${stat_line##*) }"; awk '{print $20}' <<<"$stat_line")
mkdir -m 700 "$TMPDIR/budgetowl-restore.LIVE"; printf '%s %s\n' "$$" "$my_start" > "$TMPDIR/budgetowl-restore.LIVE/pid"
deploy/restore.sh "$shared" --replace --safety-out "$work/plain-safety" >"$work/r9a.log" 2>&1 &
restore_pid=$!
for _ in $(seq 1 600); do grep -q '^Signature good' "$work/r9a.log" && break; sleep 0.1; done
kill -0 "$restore_pid" 2>/dev/null && swapped_live=yes || swapped_live=no
mv "$shared" "$work/share-original.dump"; cp "$forgery" "$shared"
set +e; wait "$restore_pid"; code=$?; set -e
check "  (the forgery holds the extra row, and the swap happened while the restore was running)" \
  "$([ -s "$forgery" ] && [ "$swapped_live" = yes ] && grep -q '^Signature good' "$work/r9a.log" \
     && docker compose exec -T db sh -c 'pg_restore --data-only -t accounts -f - 2>/dev/null' < "$forgery" \
        | grep 'Forged Row' >/dev/null && echo ok)"
wait_api || true
check "a restore whose file is swapped on the share after its signature was checked restores the real one (exit $code)" \
  "$([ "$code" = 0 ] && [ "$(account_names "$(token)" || true)" = "$before" ] && echo ok)"
[ "$code" = 0 ] || tail -8 "$work/r9a.log" | sed 's/^/       | /' >&2
check "  ... and a stale private copy from a killed restore was removed at the start" \
  "$([ ! -e "$TMPDIR/budgetowl-restore.STALE1" ] && grep -q 'Removed a private copy left behind' "$work/r9a.log" && echo ok)"
check "  ... and so was one whose pid now belongs to another process (a different start time)" \
  "$([ ! -e "$TMPDIR/budgetowl-restore.REUSED" ] && echo ok)"
check "  ... but one belonging to a running process was left alone" \
  "$([ -f "$TMPDIR/budgetowl-restore.LIVE/pid" ] && echo ok)"
rm -rf "$TMPDIR/budgetowl-restore.LIVE"
check "  ... and the restore's private copy is gone afterwards" \
  "$(find "$TMPDIR" -maxdepth 1 -name 'budgetowl-restore.*' | grep -q . && echo no || echo ok)"

renamed=$(find "$work/forged/renamed" -name 'budgetowl-*.dump' | head -1)
check "the renamed backup restores with --accept-date-mismatch" \
  "$(expect 0 "$work/r9b.log" deploy/restore.sh "$renamed" --replace --accept-date-mismatch --safety-out "$work/plain-safety" && echo ok)"

step "An interrupted restore's leftovers are found, not overwritten"
docker compose stop api web >/dev/null 2>&1
admin_query >/dev/null <<'SQL'
SELECT format('ALTER DATABASE %I RENAME TO budgetowl_restore_previous', :'target') \gexec
SQL
check "restore refuses when the live database is missing but the previous one is there (exit 7)" \
  "$(expect 7 "$work/r8.log" deploy/restore.sh "$plain" --replace && grep -q 'RENAME TO' "$work/r8.log" && echo ok)"
admin_query >/dev/null <<'SQL'
SELECT format('ALTER DATABASE budgetowl_restore_previous RENAME TO %I', :'target') \gexec
SQL
docker compose start api web >/dev/null 2>&1; wait_api || true
check "  ... and after putting it back, the data is there" "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"
admin_query >/dev/null <<<'CREATE DATABASE budgetowl_restore_failed;'
check "restore refuses while a set-aside budgetowl_restore_failed is still there (exit 7)" \
  "$(expect 7 "$work/r8b.log" deploy/restore.sh "$plain" --replace && grep -q 'budgetowl_restore_failed is still here' "$work/r8b.log" && echo ok)"
admin_query >/dev/null <<<'DROP DATABASE budgetowl_restore_failed;'

step "Disaster: the database volume is destroyed, and the new server has a new signing key"
if quiet docker compose down -v; then ok "down -v (volume deleted)"; else bad "down -v (volume deleted)"; fi
check "volume is gone" "$(docker volume ls -q | grep -qx "${COMPOSE_PROJECT_NAME}_db-data" && echo no || echo ok)"
if quiet docker compose up -d db; then ok "a new, empty database"; else bad "a new, empty database"; fi
export BACKUP_SIGNING_KEY="$work/server2/backup-signing-key"
deploy/backup.sh --print-signing-key >/dev/null 2>&1
check "the old server's backup is refused by default on the new server (exit 6)" \
  "$(expect 6 "$work/r9.log" deploy/restore.sh "$plain" && echo ok)"
if expect 0 "$work/r10.log" deploy/restore.sh "$plain" --signers "$server1_signers"; then
  ok "restore.sh --signers <old server's line> onto the empty install"
else bad "restore.sh --signers <old server's line> onto the empty install"; fi
after=$(account_names "$(token)" || echo "unreadable")
check "the accounts are back, to the fourth decimal ($after)" "$([ "$after" = "$before" ] && echo ok)"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $phone_token" "$base/api/accounts")
check "a phone token from before the backup no longer works (got $code)" "$([ "$code" = 401 ] && echo ok)"
role=$(pool_role)
check "the api still connects as budgetowl_app after a restore ($role)" \
  "$([ "$role" = budgetowl_app ] && echo ok)"

step "Encrypted --replace over live data"
add_account "$(token)" "Added After Backup" "1.00" || bad "could not add an account"
if expect 0 "$work/r11.log" deploy/restore.sh "$encrypted" --replace --signers "$server1_signers" \
     --age-identity <(cat "$work/keys/identity.txt"); then
  ok "restore.sh --replace from the age backup, key given as <(cat …)"
else bad "restore.sh --replace from the age backup, key given as <(cat …)"; fi
check "the data is the backup's again ($(account_names "$(token)"))" \
  "$([ "$(account_names "$(token)")" = "$before" ] && echo ok)"
safety=$(find "$work/backups" -name '*-pre-restore.dump*' ! -name '*.sha256' ! -name '*.sig' ! -name '*.meta' -newer "$encrypted" | sort | tail -1)
check "the safety backup is encrypted, like the backup restored ($(basename "${safety:-none}"))" \
  "$(case "$safety" in *.age) echo ok ;; esac)"
check "  ... is mode 0600 and signed by this server" "$([ -n "$safety" ] && [ "$(stat -c %a "$safety")" = 600 ] \
  && ssh-keygen -Y verify -f "$work/server2/backup-signing-key.allowed_signers" -I budgetowl-backup \
       -n budgetowl-backup -s "$safety.sig" < "$safety" >/dev/null 2>&1 && echo ok)"
check "  ... and holds the account added after the backup" "$([ -n "$safety" ] && \
  age -d -i "$work/keys/identity.txt" "$safety" \
  | docker compose exec -T db sh -c 'pg_restore --data-only -t accounts -f - 2>/dev/null' \
  | grep -q 'Added After Backup' && echo ok)"

step "Rollback guard: a backup from a newer version is refused"
# Pretend a later version ran migration 9999, back that up, and try to restore it with this one.
db_query >/dev/null <<'SQL'
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT max(installed_rank) + 1, '9999', 'from the future', 'SQL', 'V9999__from_the_future.sql', 0,
       current_user, 0, true FROM flyway_schema_history;
SQL
mkdir -m 700 "$work/future"
future=$(deploy/backup.sh --out "$work/future" --label future 2>>"$work/backup.log" | tail -1) || future=""
check "the backup's name says schema 9999 ($(basename "${future:-none}"))" \
  "$(basename "${future:-none}" | grep -q -- '-schema9999-future\.dump$' && echo ok)"
db_query >/dev/null <<<"DELETE FROM flyway_schema_history WHERE version = '9999';"
add_account "$(token)" "Still Here" "3.00" || bad "could not add an account"
expected=$(account_names "$(token)" || true)
check "restore refuses a backup newer than this version (exit 5)" \
  "$(expect 5 "$work/r12.log" deploy/restore.sh "$future" --replace && grep -q 'NEWER Budget Owl' "$work/r12.log" && echo ok)"
wait_api || true
check "  ... before replacing anything: the data is as it was" \
  "$([ "$(account_names "$(token)" || true)" = "$expected" ] && echo ok)"

step "test-restore.sh: a separate copy, with its own passwords"
# The renamed copy of $plain, so --accept-date-mismatch has to reach restore.sh (and
# --skip-version-check with it) for this to work at all.
if expect 0 "$work/t1.log" env BUDGET_OWL_VERSION="$version" deploy/test-restore.sh "$renamed" \
     --signers "$server1_signers" --port "$test_port" --accept-date-mismatch --skip-version-check; then
  ok "test-restore.sh brings the backup up on :$test_port"
else bad "test-restore.sh brings the backup up on :$test_port"; fi
check "  ... with the backup's accounts there" \
  "$([ "$(account_names "$(token "http://127.0.0.1:$test_port")" "http://127.0.0.1:$test_port" || true)" = "$before" ] && echo ok)"
# Compared inside this script only; neither password is printed.
test_pw=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' budgetowl-restore-test-db-1 2>/dev/null \
  | sed -n 's/^POSTGRES_PASSWORD=//p')
real_pw=$(sed -n 's/^DB_PASSWORD=//p' "$COMPOSE_ENV_FILES")
check "  ... and with a database password of its own, not this install's" \
  "$([ -n "$test_pw" ] && [ "$test_pw" != "$real_pw" ] && echo ok)"
unset test_pw real_pw
check "  ... while the real one keeps its own data" \
  "$([ "$(account_names "$(token)")" = "$expected" ] && echo ok)"
rm -f "$work/state/budgetowl/restore-test.env"
expect 0 "$work/t3.log" deploy/test-restore.sh --clean || true
check "test-restore.sh --clean takes the copy down even when its settings file has gone" \
  "$(docker ps -a -q --filter label=com.docker.compose.project=budgetowl-restore-test | grep -q . && echo no || echo ok)"
check "  ... and its volume, and only then says so" \
  "$(docker volume ls -q | grep -q '^budgetowl-restore-test_' && echo no || grep -q 'are gone' "$work/t3.log" && echo ok)"
check "test-restore.sh refuses a forged backup too (exit 6)" \
  "$(expect 6 "$work/t2.log" env BUDGET_OWL_VERSION="$version" deploy/test-restore.sh "$attacker_dump" --port "$test_port" && echo ok)"
quiet deploy/test-restore.sh --clean || true

step "Rotation: daily, weekly and monthly, this install's backups only"
mkdir -m 700 "$work/rot"
first=$(deploy/backup.sh --out "$work/rot" 2>/dev/null | tail -1)
instance=$(meta_get "$first.meta" instance)
fake() { # fake <yyyymmdd> <instance> [foreign] — a small stand-in for a nightly backup
  local n="budgetowl-${1}T030000Z-old-schema20.dump" d="${work}/${dir:-rot}"
  printf 'stand-in %s\n' "$1" > "$d/$n"; sha256sum "$d/$n" > "$d/$n.sha256"
  local sha meta_ns=budgetowl-backup-meta key="$BACKUP_SIGNING_KEY"
  sha=$(sha256sum < "$d/$n" | awk '{print $1}')
  # badsha: properly signed, but its .meta vouches for some other file's bytes.
  [ "${3:-}" != badsha ] || sha=$(printf 'some other file' | sha256sum | awk '{print $1}')
  printf 'backup=%s\nsha256=%s\ninstance=%s\npeople=yes\n' "$n" "$sha" "$2" > "$d/$n.meta"
  # oldformat: round 3's .meta, signed in the backup namespace and with no sha256.
  if [ "${3:-}" = oldformat ]; then
    printf 'backup=%s\ninstance=%s\npeople=yes\n' "$n" "$2" > "$d/$n.meta"; meta_ns=budgetowl-backup
  fi
  # "foreign": signed, but by a key that is not this server's, so a .sig is there to be fooled by.
  [ "${3:-}" != foreign ] || key="$work/attacker-key/key"
  ssh-keygen -Y sign -q -f "$key" -n budgetowl-backup "$d/$n" </dev/null
  ssh-keygen -Y sign -q -f "$key" -n "$meta_ns" "$d/$n.meta" </dev/null
}
for d in 20261120 20261119 20261110 20261015 20260915 20260815 20260701 20260702; do fake "$d" "$instance"; done
fake 20260501 0000000000000000            # another install's, signed by this server
fake 20260601 "$instance" foreign        # claims to be ours, signed by another key
fake 20260602 "$instance" badsha         # signed by us, but its .meta is for different bytes
fake 20260603 "$instance" oldformat      # a round-3 backup: never pruned
fake 20261399 "$instance"                 # no such date
if expect 0 "$work/rot.log" deploy/backup.sh --out "$work/rot" --keep 2 --keep-weekly 3 --keep-monthly 4; then
  rot_ok=ok; else rot_ok=no; fi
# Exit 0 AND the backup's path as the last line: "Done" alone is printed before rotation starts.
check "a nightly run with an impossible date in the folder still succeeds, rotation included" \
  "$([ "$rot_ok" = ok ] && tail -1 "$work/rot.log" | grep -q '/rot/budgetowl-.*\.dump$' && echo ok)"
survivors=$(find "$work/rot" -name 'budgetowl-*.dump' -printf '%f\n' | sort | tr '\n' ' ')
# The policy, computed independently from the names the check made (all existed before the prune).
expected_survivors=$(python3 - "$instance" "$first" "$(sed -n 's/^Backing up to //p' "$work/rot.log")" <<'PY'
import datetime, os, sys
inst, first, second = sys.argv[1], os.path.basename(sys.argv[2]), os.path.basename(sys.argv[3])
fakes = ["20261120", "20261119", "20261110", "20261015", "20260915", "20260815", "20260701", "20260702"]
mine = sorted([first, second] + ["budgetowl-%sT030000Z-old-schema20.dump" % d for d in fakes], reverse=True)
keep, weeks, months = set(mine[:2]), [], []
for n in mine:
    day = datetime.date(int(n[10:14]), int(n[14:16]), int(n[16:18]))
    w, m = day.isocalendar()[:2], n[10:16]
    if w not in weeks and len(weeks) < 3: weeks.append(w); keep.add(n)
    if m not in months and len(months) < 4: months.append(m); keep.add(n)
# Never touched: another install's, one this server did not sign, one with no real date.
for d in ("20260501", "20260601", "20260602", "20260603", "20261399"):
    keep.add("budgetowl-%sT030000Z-old-schema20.dump" % d)
print(" ".join(sorted(keep)) + " ")
PY
)
check "keeps exactly the newest 2, one a week for 3 weeks, one a month for 4 months, and nothing it cannot vouch for" \
  "$([ "$survivors" = "$expected_survivors" ] && echo ok)"
[ "$survivors" = "$expected_survivors" ] || printf '       got:    %s\n       wanted: %s\n' "$survivors" "$expected_survivors"
check "  ... and removed each pruned backup's .sha256, .sig, .meta and .meta.sig with it" \
  "$([ ! -e "$work/rot/budgetowl-20260701T030000Z-old-schema20.dump.meta" ] \
     && [ ! -e "$work/rot/budgetowl-20260701T030000Z-old-schema20.dump.meta.sig" ] \
     && [ ! -e "$work/rot/budgetowl-20260701T030000Z-old-schema20.dump.sig" ] && echo ok)"

mkdir -m 700 "$work/rot2"
dir=rot2 fake 20261101 ffffffffffffffff
dir=rot2 fake 20261001 "$instance"
expect 0 "$work/rot2.log" deploy/backup.sh --out "$work/rot2" --keep 1 || true
check "nothing is rotated away when the newest earlier backup is from a different install" \
  "$([ -f "$work/rot2/budgetowl-20261001T030000Z-old-schema20.dump" ] && grep -q 'different' "$work/rot2.log" && echo ok)"

mkdir -m 700 "$work/rot3"
dir=rot3 fake 20261101 "$instance" foreign
dir=rot3 fake 20261001 "$instance"
expect 0 "$work/rot3.log" deploy/backup.sh --out "$work/rot3" --keep 1 || true
check "  ... or when it is one this server did not sign" \
  "$([ -f "$work/rot3/budgetowl-20261001T030000Z-old-schema20.dump" ] && grep -q 'not signed by this one' "$work/rot3.log" && echo ok)"

mkdir -m 700 "$work/rot4"
dir=rot4 fake 20991231 ffffffffffffffff
dir=rot4 fake 20261001 "$instance"
expect 0 "$work/rot4.log" deploy/backup.sh --out "$work/rot4" --keep 1 || true
check "  ... and a name dated in the future is neither counted nor removed" \
  "$([ -f "$work/rot4/budgetowl-20991231T030000Z-old-schema20.dump" ] && [ ! -f "$work/rot4/budgetowl-20261001T030000Z-old-schema20.dump" ] && echo ok)"

mkdir -m 700 "$work/rot5"
# Someone who can write to the key's folder swaps the .pub for their own and signs two backups.
cp "$BACKUP_SIGNING_KEY.pub" "$work/real-key.pub"; cp "$work/attacker-key/key.pub" "$BACKUP_SIGNING_KEY.pub"
dir=rot5 fake 20261101 "$instance" foreign
dir=rot5 fake 20261002 "$instance" foreign
# ssh-keygen -Y sign itself refuses a .pub that does not match the key, so the backup stops before
# rotation is reached; rotation also derives the public half from the private key (belt and braces).
expect 1 "$work/rot5.log" deploy/backup.sh --out "$work/rot5" --keep 1 || true
cp "$work/real-key.pub" "$BACKUP_SIGNING_KEY.pub"
check "  ... and a .pub swapped beside the signing key stops the backup, and rotates nothing away" \
  "$(grep -q "doesn't match" "$work/rot5.log" && [ -f "$work/rot5/budgetowl-20261002T030000Z-old-schema20.dump" ] \
     && [ -f "$work/rot5/budgetowl-20261101T030000Z-old-schema20.dump" ] && echo ok)"

step "Resources"
mapfile -t ids < <(docker compose ps -q)
docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}}' "${ids[@]}" 2>/dev/null || true
printf '  database on disk: %s\n' \
  "$(docker compose exec -T db du -sh /var/lib/postgresql/data 2>/dev/null | cut -f1)"
printf '  backup size: %s\n' "$(du -h "$plain" | cut -f1)"

echo
if [ "$failed" -eq 0 ]; then echo "deploy check PASSED"; else echo "deploy check FAILED"; fi
exit "$failed"
