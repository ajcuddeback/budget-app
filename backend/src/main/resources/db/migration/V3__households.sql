-- V3 — households and household membership, with the last-owner rule enforced in the database.
--
-- ADR-0026: an instance holds exactly ONE household, and its OWNER is the operator.
-- ADR-0017: the household is the ownership root for financial data; `household_members` is the
-- membership graph rather than financial data, which is why it carries `user_id` and is exempt
-- from the "every financial table has a direct household_id" rule's reasoning (it has one anyway).
--
-- Migrations are APPEND-ONLY (ADR-0007).

CREATE TABLE households (
    id             uuid         PRIMARY KEY,
    name           text         NOT NULL,
    base_currency  varchar(3)   NOT NULL,
    -- Denormalised on purpose. See the last-owner rule below: this column is what turns two
    -- concurrent "remove the other owner" transactions into a row-level lock conflict, and a
    -- count(*) in a trigger cannot do that. Maintained only by trg_household_members_owner_count;
    -- the application never writes it and deliberately does not map it.
    owner_count    integer      NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_households_name_present CHECK (btrim(name) <> ''),
    CONSTRAINT ck_households_base_currency CHECK (base_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_households_owner_count_not_negative CHECK (owner_count >= 0)
);

-- ADR-0026 in the schema, not merely in a service. A unique index on a constant expression
-- permits exactly one row in this table for the lifetime of the instance, so a second household
-- cannot be created by an API bug, a migration, or a psql session. It is also the reason two
-- concurrent /api/setup/first-user callers cannot both succeed: both insert a household.
CREATE UNIQUE INDEX uq_households_singleton ON households ((true));

CREATE TABLE household_members (
    id                uuid         PRIMARY KEY,
    household_id      uuid         NOT NULL,
    user_id           uuid         NOT NULL,
    role              varchar(16)  NOT NULL,
    joined_at         timestamptz  NOT NULL DEFAULT now(),
    -- Per-member display preference (ADR-0022). NULL means "fall back to the household's base
    -- currency" — a real meaning, not an unknown. Amounts are always stored in the account's own
    -- currency; this changes what a member sees, never what is recorded.
    display_currency  varchar(3),
    -- Per-member locale (ADR-0023). NULL means "fall back to the platform locale".
    locale            varchar(35),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT fk_household_members_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    -- Deleting a user withdraws their membership. If that user is the last OWNER the deferred
    -- check below aborts the whole delete, which is the database's half of "a user cannot delete
    -- their own account while they own a household".
    CONSTRAINT fk_household_members_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT uq_household_members_household_id_user_id UNIQUE (household_id, user_id),
    CONSTRAINT ck_household_members_role CHECK (role IN ('OWNER', 'MEMBER', 'VIEWER')),
    CONSTRAINT ck_household_members_display_currency
        CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_household_members_locale
        CHECK (locale IS NULL OR locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
);

-- uq_household_members_household_id_user_id already indexes (household_id, ...).
CREATE INDEX ix_household_members_user_id ON household_members (user_id);
CREATE INDEX ix_household_members_household_id_role ON household_members (household_id, role);

-- ---------------------------------------------------------------------------------------------
-- The last-owner rule.
--
-- "A household always has at least one OWNER" must hold when two owners remove each other at the
-- same instant (threat model: *Two owners remove each other simultaneously, leaving the household
-- with no administrator*). Application logic cannot do this, and neither can the obvious trigger:
-- under READ COMMITTED two transactions deleting *different* rows never conflict, and a
-- `SELECT count(*)` inside each one sees a snapshot in which the other's delete has not happened.
-- Both see "one owner will remain", both commit, and the household has none. Classic write skew.
--
-- So the rule is built from two pieces that together cannot race:
--
--   1. A counter on `households`, adjusted by an AFTER trigger on every membership change. Both
--      transactions must UPDATE THE SAME ROW, so the second blocks on a row lock. When the first
--      commits, PostgreSQL re-evaluates the second's `owner_count = owner_count - 1` against the
--      newly committed tuple, so it computes 0 rather than 1. The arithmetic is what makes this
--      correct; a re-read would still see the stale snapshot.
--   2. A DEFERRABLE INITIALLY DEFERRED constraint trigger that re-reads the counter at COMMIT and
--      refuses a household left with no OWNER. Deferred because a household is legitimately
--      created with owner_count 0 and given its OWNER a statement later in the same transaction.
--
-- Net effect: of two concurrent last-owner removals, exactly one commits. CHECK constraints
-- cannot be deferred in PostgreSQL, which is why this is a constraint trigger.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION sync_household_owner_count() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.role = 'OWNER' THEN
            UPDATE households SET owner_count = owner_count + 1 WHERE id = NEW.household_id;
        END IF;
        RETURN NULL;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.role = 'OWNER' THEN
            -- No rows when the household itself is being deleted in this transaction, which is
            -- the correct outcome rather than an error.
            UPDATE households SET owner_count = owner_count - 1 WHERE id = OLD.household_id;
        END IF;
        RETURN NULL;
    ELSE
        IF OLD.household_id <> NEW.household_id THEN
            RAISE EXCEPTION 'a membership cannot be moved between households'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_household_members_household_immutable';
        END IF;
        IF OLD.role = 'OWNER' AND NEW.role <> 'OWNER' THEN
            UPDATE households SET owner_count = owner_count - 1 WHERE id = NEW.household_id;
        ELSIF OLD.role <> 'OWNER' AND NEW.role = 'OWNER' THEN
            UPDATE households SET owner_count = owner_count + 1 WHERE id = NEW.household_id;
        END IF;
        RETURN NULL;
    END IF;
END;
$$;

CREATE TRIGGER trg_household_members_owner_count
    AFTER INSERT OR UPDATE OR DELETE ON household_members
    FOR EACH ROW EXECUTE FUNCTION sync_household_owner_count();

CREATE FUNCTION assert_household_has_owner() RETURNS trigger
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

    IF remaining < 1 THEN
        RAISE EXCEPTION 'household % would be left without an OWNER', NEW.id
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_households_at_least_one_owner',
                  TABLE = 'households',
                  HINT = 'transfer ownership before removing, demoting, or deleting the last OWNER';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_households_at_least_one_owner
    AFTER INSERT OR UPDATE ON households
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_household_has_owner();
