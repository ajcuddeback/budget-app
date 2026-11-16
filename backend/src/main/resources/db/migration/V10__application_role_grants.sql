-- V10 — what the application role is allowed to do (ADR-0030).
--
-- The runtime used to connect as DB_USER, which is POSTGRES_USER, which OWNS every table here.
-- (Written without the shell's braces on purpose: Flyway substitutes placeholders of that shape
-- in migration text, COMMENTS INCLUDED, and fails the migration when it has no value for one.)
-- An owner can TRUNCATE past row triggers, ALTER TABLE ... DROP CONSTRAINT, and ALTER TABLE ...
-- DISABLE TRIGGER, so every CHECK in V8 and V9 and the DEFERRABLE last-owner rule in V3 were
-- advisory against the one process most likely to have a bug in it. That was security finding F8,
-- carried since slice 2.
--
-- The split is two roles: the OWNER runs Flyway, and the application connects as a role with
-- SELECT, INSERT, UPDATE, DELETE and nothing else. This migration is the privileges half.
--
-- The ROLE ITSELF IS NOT CREATED HERE, and that is deliberate (ADR-0030): a role needs a password,
-- a password in a migration file is a secret in the repository (non-negotiable #1), and roles are
-- cluster-level objects while a migration runs against one database. The role is created in
-- deployment — deploy/db-init/10-create-app-role.sh, run by the Postgres entrypoint on a fresh
-- volume, or by hand on an existing instance.
--
-- So this whole migration is CONDITIONAL on the role existing. A developer's database, a
-- Testcontainers database, and any instance that has not been reconfigured yet simply have no such
-- role, and this does nothing at all. A migration that failed because deployment had not caught up
-- would turn a hardening change into an outage.
--
-- The role NAME is hardcoded. It is a name, not a secret, and it has to match what the init script
-- creates. An operator who renames it has to grant the privileges themselves; the init script says
-- so out loud when the two disagree.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix anything here with a new migration, never an edit.

DO $$
DECLARE
    -- Must match DB_APP_USER's default in docker-compose.yml and deploy/db-init/10-create-app-role.sh.
    app_role  constant text := 'budgetowl_app';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE NOTICE
            'ADR-0030: role "%" does not exist, so no grants were applied. The runtime is '
            'connecting as the schema owner and the database constraints are advisory against '
            'it. Create the role (deploy/db-init/10-create-app-role.sh) and grant it by hand — '
            'this migration has already been recorded and will not run again.', app_role;
        RETURN;
    END IF;

    -- Reach the schema, and nothing more. No CREATE: that is the whole point.
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app_role);

    -- PostgreSQL 15 and later already withhold CREATE on `public` from PUBLIC, so this is usually
    -- a no-op. It is stated anyway because "the application cannot create a table" should be a
    -- property of this schema rather than an inherited default that a future `GRANT CREATE ON
    -- SCHEMA public TO PUBLIC` in somebody's tuning script would quietly undo. The owner keeps
    -- CREATE through ownership; PUBLIC is everyone else.
    EXECUTE 'REVOKE CREATE ON SCHEMA public FROM PUBLIC';

    -- Rows, not structure. Note what is NOT in this list: TRUNCATE, REFERENCES and TRIGGER are
    -- table privileges in their own right, and omitting them is how they stay out of reach. The
    -- grants are stated positively for the same reason — a REVOKE list fails open for anything
    -- nobody thought of.
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app_role);

    -- Flyway's own bookkeeping is not application data. The application never reads it, and a
    -- connection that can DELETE from it can make the schema's history lie about itself —
    -- `validate-on-migrate` is only worth something if the table it validates against is beyond
    -- the reach of the process being validated. The blanket grant above included it, so take it
    -- back explicitly.
    IF EXISTS (SELECT 1 FROM pg_tables
                WHERE schemaname = 'public' AND tablename = 'flyway_schema_history') THEN
        EXECUTE format('REVOKE ALL ON TABLE flyway_schema_history FROM %I', app_role);
    END IF;

    -- Every table a LATER migration creates, without that migration having to remember. Default
    -- privileges are keyed to the role that creates the object, and migrations run as the owner —
    -- which is `current_user` right now, by definition of how this file is being executed. If
    -- migrations ever move to a different role, its defaults need stating too.
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', current_user, app_role);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'GRANT USAGE, SELECT ON SEQUENCES TO %I', current_user, app_role);

    RAISE NOTICE 'ADR-0030: DML granted to "%"; it cannot change the schema.', app_role;
END
$$;

-- Why there is still no `REVOKE UPDATE (owner_count) ON households` here, which V8 said it was
-- waiting for: the owner-count column is maintained by `sync_household_owner_count()`, an ordinary
-- (invoker-rights) trigger function. It runs as whoever inserted the membership row, so revoking
-- the column from the application role would break every legitimate membership write rather than
-- only the illegitimate direct UPDATE. Making that revoke meaningful needs the trigger function to
-- become SECURITY DEFINER with a pinned search_path, which is a change to the function and so a
-- migration of its own, with its own test. Recorded rather than half-done.
