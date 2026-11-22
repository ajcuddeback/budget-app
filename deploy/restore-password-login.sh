#!/usr/bin/env bash
# Re-enable email + password sign-in, from the machine.
#
#   deploy/restore-password-login.sh            # against the Compose stack
#   deploy/restore-password-login.sh --dry-run  # show what it would change
#
# WHY THIS EXISTS
#
# An administrator may hide the password login route once their identity provider is working
# (docs/architecture/security-model.md). If that provider then breaks — endpoints move, a
# certificate expires, a container is restored from a backup with stale configuration — the
# browser route back in is the one thing that is unavailable. The security model therefore
# requires a path that does not need the browser:
#
#   > A self-hoster has shell access to their own box, so an administrative command that
#   > re-enables password login must exist and be documented.
#
# This is that command. It grants nothing that shell access did not already grant: the operator
# owns the machine and the database (ADR-0026), and this only saves them writing the SQL
# correctly at 11pm.
#
# It does NOT touch passwords, accounts or any financial data. It turns one boolean back on.
set -uo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

DB_NAME="${DB_NAME:-budgetowl}"
DB_USER="${DB_USER:-budgetowl}"
SERVICE="${DB_SERVICE:-db}"

# Run psql inside the database container, so the operator needs no client installed and no
# password on the command line where a shell history would keep it.
psql() {
  docker compose exec -T "$SERVICE" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
}

if ! docker compose ps --status running --services 2>/dev/null | grep -qx "$SERVICE"; then
  echo "The '$SERVICE' service is not running. Start the stack first: docker compose up -d" >&2
  exit 1
fi

before=$(psql -At -c "SELECT password_login_enabled || ' ' || oidc_enabled FROM instance_settings WHERE id = 1") || {
  echo "Could not read instance_settings. Is this the right database?" >&2
  exit 1
}
set -- $before
password_login="$1"; oidc="$2"

echo "Before: password login ${password_login}, OIDC ${oidc}"

if [ "$password_login" = "t" ]; then
  echo "Password login is already enabled. Nothing to do."
  exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "Would set password_login_enabled = true. Re-run without --dry-run to apply."
  exit 0
fi

# Only this column. Not the OIDC configuration: the operator may well want their provider back
# once it is fixed, and silently tearing it down would turn a recovery into a second outage.
#
# version and updated_at move with it. They are not decoration: instance_settings carries an
# optimistic lock (V16) so that a change made in one place is visible as a change everywhere else,
# and this is the one writer that reaches the row from outside the API. Leaving them alone would
# let an admin console tab opened before the recovery keep passing the version check, and show a
# last-changed time for a row that has changed. The point of a lock the out-of-band writer opts
# out of is hard to defend.
psql -c "UPDATE instance_settings
            SET password_login_enabled = true,
                version = version + 1,
                updated_at = now()
          WHERE id = 1" >/dev/null || {
  echo "The update failed. The database is unchanged." >&2
  exit 1
}

echo "After:  password login enabled."
echo
echo "Sign in with your email and password at your instance's address."
echo "Your identity provider is untouched — fix it and hide password login again when you are"
echo "ready, from Admin in the app."
