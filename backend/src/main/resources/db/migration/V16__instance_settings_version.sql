-- V16 — optimistic locking for the instance settings row.
--
-- `instance_settings` has one row and, by ADR-0029, exactly one person allowed to change it. That
-- sounds like a reason not to need a version column, and it is the opposite of one: "the same
-- person in two browser tabs" is precisely the lost-update that optimistic locking exists for,
-- and the field being lost here is login configuration — an OIDC issuer silently reverting to a
-- stale value is how somebody locks themselves out of their own server.
--
-- Every other entity in this schema carries `version`. This row was written before anything
-- edited it (V6 inserts it and the setup endpoint claims it with a conditional UPDATE), so it
-- never needed one until the admin console (docs/features/admin-console.md).
--
-- Migrations are APPEND-ONLY (ADR-0007).

ALTER TABLE instance_settings
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN instance_settings.version IS
    'Optimistic lock for the admin console. One row and one administrator still means two tabs.';
