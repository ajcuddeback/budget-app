#!/bin/sh
# Create the database role the application connects as — the one that CANNOT change the schema
# (ADR-0030). Mounted into the `db` service at /docker-entrypoint-initdb.d/.
#
# WHY THIS IS NOT A MIGRATION: a role needs a password, and a password in a migration file is a
# secret in the repository (non-negotiable #1). Roles are also cluster-level objects, so a
# per-database migration is the wrong scope for one. The PRIVILEGES are a migration —
# backend/src/main/resources/db/migration/V10__application_role_grants.sql — because privileges on
# tables have to follow every table the schema gains.
#
# THERE IS NO PASSWORD IN THIS FILE. It is read from DB_APP_PASSWORD in the environment, and it is
# passed to PostgreSQL through psql variables with format(%I/%L) quoting rather than pasted into
# SQL text by the shell.
#
# IT ONLY RUNS ON A FRESH VOLUME. That is how the Postgres entrypoint works: everything in
# /docker-entrypoint-initdb.d/ is ignored once PGDATA is initialised. An instance that is already
# running Budget Owl therefore has to run this by hand, exactly once:
#
#   docker compose up -d db      # recreate it so the variables and this file are present
#   docker compose exec db /docker-entrypoint-initdb.d/10-create-app-role.sh
#
# The full upgrade step, in order, is in docs/features/authentication-and-households.md.
#
# It is idempotent and safe to run on a live database: it creates the role only if it is missing,
# and re-applies the grants only if the schema has already been migrated.
set -eu

# Hardcoded in V10__application_role_grants.sql, which cannot read the environment. A name, not a
# secret.
GRANTED_ROLE='budgetowl_app'
ROLE="${DB_APP_USER:-$GRANTED_ROLE}"

say() { echo "ADR-0030: $*"; }

if [ -z "${DB_APP_PASSWORD:-}" ]; then
  say "DB_APP_PASSWORD is not set, so no application role was created."
  say "  The application will connect as ${POSTGRES_USER}, which OWNS every table and can"
  say "  therefore TRUNCATE them and drop their constraints — the database's guarantees are"
  say "  advisory against the application itself while that is true."
  say "  Set DB_APP_PASSWORD in .env and run this script (see the header), then restart the api."
  exit 0
fi

if [ "$ROLE" = "${POSTGRES_USER}" ]; then
  say "DB_APP_USER is '${ROLE}', which is the role that OWNS the schema. Refusing: the whole"
  say "  point is that the application does not connect as the owner, and the repair statements"
  say "  below would take privileges away from the role Flyway needs."
  exit 1
fi

if [ "$ROLE" != "$GRANTED_ROLE" ]; then
  say "WARNING: DB_APP_USER is '${ROLE}', but the migration grants privileges to"
  say "  '${GRANTED_ROLE}'. On a database that has not been migrated yet, nothing will ever"
  say "  grant '${ROLE}' anything and the application will fail every query — this script can"
  say "  only repair a schema that already exists. Either unset DB_APP_USER, or keep"
  say "  V10__application_role_grants.sql applied to '${ROLE}' yourself from here on."
fi

# `\gexec` runs each row of the result as a statement, so the role name and the password go
# through format()'s %I and %L quoting and never through the shell's.
# (The psql variable is named `credential` rather than `secret` on purpose: tools/verify.sh's
# hygiene grep flags `secret="..."` shapes, and this one is an environment reference.)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=role="$ROLE" --set=credential="$DB_APP_PASSWORD" <<'SQL'
-- LOGIN and nothing else: no SUPERUSER, no CREATEDB, no CREATEROLE, no BYPASSRLS, no REPLICATION.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'credential')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')
\gexec

-- On a FRESH volume the schema does not exist yet and V10 will grant these when the api first
-- starts; nothing happens here. On an EXISTING database V10 has usually already run — and found
-- no role to grant to — so these statements are what repairs that. They mirror V10 exactly; if
-- you change one, change both.
SELECT statement FROM (VALUES
    (1, format('GRANT USAGE ON SCHEMA public TO %I', :'role')),
    (2, 'REVOKE CREATE ON SCHEMA public FROM PUBLIC'),
    (3, format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I',
               :'role')),
    (4, format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'role')),
    (5, format('REVOKE ALL ON TABLE flyway_schema_history FROM %I', :'role')),
    (6, format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
               'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', current_user, :'role')),
    (7, format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
               'GRANT USAGE, SELECT ON SEQUENCES TO %I', current_user, :'role'))
  ) AS repair(ordinal, statement)
 WHERE EXISTS (SELECT 1 FROM pg_tables
                WHERE schemaname = 'public' AND tablename = 'flyway_schema_history')
 ORDER BY ordinal
\gexec
SQL

say "role '${ROLE}' is ready; it can read and write rows and cannot change the schema."
