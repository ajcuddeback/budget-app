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

-- ---------------------------------------------------------------------------------------------
-- 2. The last-owner rule checks reality, not only its own counter.
--
-- V3 built the rule from two pieces: a counter on `households` maintained by a row trigger on
-- `household_members`, and a deferred constraint trigger that re-reads the counter at COMMIT.
-- The counter is what makes concurrent removals safe — both transactions must UPDATE the same
-- `households` row, so the second blocks and then recomputes `owner_count - 1` against the
-- committed tuple — and that reasoning is unchanged and still load-bearing.
--
-- What was missing is that the assertion trusted the counter as the whole truth. The counter is
-- an ordinary integer column, so:
--
--     UPDATE households SET owner_count = 5;
--     DELETE FROM household_members WHERE role = 'OWNER';   -- succeeds
--
-- left a household holding all of the financial data with no OWNER, and — worse than the one
-- household — left the rule silently switched off from then on, because the counter never
-- returns to the truth by itself. No JPA path reaches this today (`Household` does not map the
-- column), but a restored backup, a later slice, a psql session or a TRUNCATE does.
--
-- So the assertion now checks both: the counter (which serialises the transactions) AND the
-- membership rows (which are the actual invariant). The existence test is one index probe on
-- ix_household_members_household_id_role, at COMMIT only.
--
-- Note on `REVOKE UPDATE (owner_count)`: it would be the right complement and it is deliberately
-- NOT here. The runtime pool currently connects as the database owner, which means column
-- privileges, triggers, TRUNCATE and `session_replication_role` are all bypassable anyway.
-- Splitting the migrating role from the runtime role is a deployment change; see the deployment
-- notes in docs/features/authentication-and-households.md. A REVOKE that a superuser ignores
-- would read like protection and provide none.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION assert_household_has_owner() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    remaining integer;
BEGIN
    -- Re-read rather than trusting NEW: a deferred constraint trigger carries the tuple as it was
    -- when the event was queued, and the counter has usually moved since.
    SELECT owner_count INTO remaining FROM households WHERE id = NEW.id;

    IF NOT FOUND THEN
        -- The household was deleted later in this transaction. Nothing left to protect.
        RETURN NULL;
    END IF;

    -- The counter first, because it is the piece that made the two transactions conflict; the
    -- membership rows second, because they are what the rule is actually about. Either one
    -- failing is a household without an owner.
    IF remaining < 1
       OR NOT EXISTS (SELECT 1
                        FROM household_members
                       WHERE household_id = NEW.id
                         AND role = 'OWNER') THEN
        RAISE EXCEPTION 'household % would be left without an OWNER', NEW.id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_households_at_least_one_owner',
                  TABLE = 'households',
                  HINT = 'transfer ownership before removing, demoting, or deleting the last OWNER';
    END IF;

    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. A plaintext password that happens to start with '$' is still a plaintext password.
--
-- The V2 constraint asked for 20 characters and a leading '{id}' or '$'. It is the backstop
-- against the worst defect this table could have — a user's typed password written into the
-- column in the clear — and '$uperSecretPassw0rd!' satisfied it. So did any other passphrase
-- somebody chose to start with a dollar sign.
--
-- A leading '$' is not evidence of anything. What is evidence is the algorithm naming itself:
-- DelegatingPasswordEncoder writes '{bcrypt}$2a$12$...' and a bare encoder writes '$2a$...' or
-- '$argon2id$...'. The accepted set is deliberately the encoders this application registers or
-- could plausibly register, and nothing else; a new one arrives with a migration, which is the
-- right amount of friction for a change to how credentials are stored.
--
-- The length floor stays: it is what stops a short string that merely looks like a prefix.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE users
    DROP CONSTRAINT ck_users_password_hash_encoded;

ALTER TABLE users
    ADD CONSTRAINT ck_users_password_hash_encoded
        CHECK (password_hash IS NULL
               OR (length(password_hash) >= 20
                   AND (
                       -- DelegatingPasswordEncoder's form: a registered algorithm id, then that
                       -- encoder's own output. Pbkdf2 emits bare hex, so no '$' is required here.
                       password_hash ~ '^\{(bcrypt|pbkdf2|argon2|scrypt|sha256)\}[^{}[:space:]]+$'
                       -- A bare hash from a single encoder. It has to name its algorithm.
                       OR password_hash ~ '^\$(2[abxy]?|argon2(id|i|d))\$')));

-- ---------------------------------------------------------------------------------------------
-- 4. Bounds on the text an attacker can choose the length of.
--
-- `text` in PostgreSQL is up to a gigabyte. `display_name`, `device_label`, the household's
-- `name` and both `citext` email columns had no ceiling at all, so the only thing standing
-- between a 100 MB display name and the table was Bean Validation at the edge — which does hold
-- today (every @RequestBody is @Valid and the DTOs carry @Size), and which is exactly the kind of
-- guarantee that a new endpoint, a CLI, an import or a future OIDC provisioning path silently
-- opts out of. Invitation acceptance in particular is UNAUTHENTICATED.
--
-- The numbers match the DTOs on purpose: 100 for a human-chosen label, 254 for an email address
-- (RFC 5321's limit on a forward path). The database and the edge agreeing is the point; the
-- database is the half that cannot be bypassed by adding a caller.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE users
    ADD CONSTRAINT ck_users_display_name_length CHECK (length(display_name) BETWEEN 1 AND 100),
    ADD CONSTRAINT ck_users_email_length CHECK (length(email) BETWEEN 3 AND 254);

ALTER TABLE households
    ADD CONSTRAINT ck_households_name_length CHECK (length(name) BETWEEN 1 AND 100);

ALTER TABLE household_invitations
    ADD CONSTRAINT ck_household_invitations_email_length CHECK (length(email) BETWEEN 3 AND 254);

ALTER TABLE auth_tokens
    ADD CONSTRAINT ck_auth_tokens_device_label_length CHECK (length(device_label) BETWEEN 1 AND 100);
