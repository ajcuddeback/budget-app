-- V8 — constraints that the slice-2 security audit found to be weaker than they read.
--
-- Nothing here is new behaviour. Every statement tightens a rule V2–V6 already meant to state, in
-- a place where the earlier wording permitted a state the feature does not allow. V1–V7 are merged
-- and frozen (ADR-0007), so the corrections arrive as a new migration rather than as edits.
--
-- Where a constraint is dropped and recreated it keeps its NAME. The rule is the same rule; only
-- its predicate is stricter. The name is what `RedactedThrowable.violatedConstraint` surfaces and
-- what `ApiExceptionHandler` branches on, and renaming it would break that for no gain. Read the
-- original definition in V2/V6 for the intent and this file for what is actually enforced.
--
-- Migrations are APPEND-ONLY (ADR-0007).

-- ---------------------------------------------------------------------------------------------
-- 1. The instance must always have at least one way to log in.
--
-- The V6 constraint read `password_login_enabled OR oidc_owner_login_at IS NOT NULL`. It checked
-- the timestamp and never `oidc_enabled`, so this sequence was legal:
--
--     enable OIDC -> an OWNER signs in through it -> disable password login (correct)
--                 -> disable OIDC                 (permitted, and catastrophic)
--
-- leaving `password_login_enabled = false, oidc_enabled = false`: no login route at all, on a
-- self-hosted box holding the household's entire financial record, recoverable only with psql on
-- the host. `InstanceSettings.disableOidc()` reached exactly that state.
--
-- The proof of "OIDC works here" is only a proof while OIDC is still switched on, so the timestamp
-- is no longer sufficient on its own.
-- ---------------------------------------------------------------------------------------------

-- Repair before constraining: an instance that is already in the locked-out state must come back
-- up with a login route rather than fail to migrate. Password login is the permanent capability
-- (ADR-0018), so it is the one to restore.
UPDATE instance_settings
   SET password_login_enabled = true
 WHERE NOT password_login_enabled
   AND NOT (oidc_enabled AND oidc_owner_login_at IS NOT NULL);

ALTER TABLE instance_settings
    DROP CONSTRAINT ck_instance_settings_password_login_lockout;

ALTER TABLE instance_settings
    ADD CONSTRAINT ck_instance_settings_password_login_lockout
        CHECK (password_login_enabled
               OR (oidc_enabled AND oidc_owner_login_at IS NOT NULL));
