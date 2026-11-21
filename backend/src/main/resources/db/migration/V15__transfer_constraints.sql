-- V15 — the rules that make a transfer a PAIR rather than two rows that happen to agree.
--
-- `transfer_group_id`, its partial index and its comment have been on `transactions` since V12.
-- What V12 could not express is the shape of the group, and the application alone cannot be the
-- guarantee: a bug, a future import, or a half-finished transaction would leave money that left
-- one account and arrived nowhere, which is the one outcome a ledger must never show
-- (ADR-0033, docs/features/transfers.md).
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- A transfer is not spending, so it is not filed. The API refuses a category on a transfer; this
-- is the control, and it also catches the reverse mistake — filing an existing transfer later.
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_transfer_has_no_category
        CHECK (transfer_group_id IS NULL OR category_id IS NULL);

-- Likewise a payee: the other side of a transfer is your own account, not a third party.
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_transfer_has_no_payee
        CHECK (transfer_group_id IS NULL OR payee_id IS NULL);

-- ---------------------------------------------------------------------------------------------
-- The shape of a group.
--
-- DEFERRABLE INITIALLY DEFERRED, unlike the categories trigger (V11), which is INITIALLY
-- IMMEDIATE. The difference is the rule: a category's parentage can be judged the moment the row
-- lands, but a transfer's first leg is ALWAYS invalid on its own — the second one does not exist
-- yet. Checking at COMMIT is the only time the question can be answered, and it is the same shape
-- the at-least-one-owner rule uses in V3.
--
-- What is asserted is the part that holds in EVERY case, including across currencies (ADR-0033):
-- exactly two rows, two different accounts, opposite signs. The amounts are deliberately NOT
-- compared: £200 leaving a GBP account and €228 arriving in a EUR one is a correct transfer, and
-- a constraint demanding they match would forbid the honest record and force a fabricated rate.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION transactions_enforce_transfer_pair() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    group_id      uuid;
    leg_count     integer;
    account_count integer;
    positive_legs integer;
    negative_legs integer;
    household_cnt integer;
BEGIN
    group_id := COALESCE(NEW.transfer_group_id, OLD.transfer_group_id);
    IF group_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*),
           count(DISTINCT account_id),
           count(*) FILTER (WHERE amount > 0),
           count(*) FILTER (WHERE amount < 0),
           count(DISTINCT household_id)
      INTO leg_count, account_count, positive_legs, negative_legs, household_cnt
      FROM transactions
     WHERE transfer_group_id = group_id;

    -- Deleting both legs is how a transfer is removed, so an empty group is not a violation.
    IF leg_count = 0 THEN
        RETURN NULL;
    END IF;

    IF leg_count <> 2 THEN
        RAISE EXCEPTION 'transfer % has % legs, and a transfer has exactly two',
            group_id, leg_count
            USING ERRCODE = 'check_violation';
    END IF;

    -- Two rows in one account is not a movement between accounts; it is two entries that cancel.
    IF account_count <> 2 THEN
        RAISE EXCEPTION 'transfer % does not span two different accounts', group_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- One out, one in. Two negatives would be two withdrawals wearing a transfer's badge, and
    -- would quietly understate spending by the whole amount.
    IF positive_legs <> 1 OR negative_legs <> 1 THEN
        RAISE EXCEPTION
            'transfer % must have one leg leaving and one arriving, not % and %',
            group_id, negative_legs, positive_legs
            USING ERRCODE = 'check_violation';
    END IF;

    -- Scoping, in the database as well as in the service (ADR-0017). Without it a transfer would
    -- be a way to link a row in another household's account to one in yours — and the ledger
    -- would then name their account on your screen.
    IF household_cnt <> 1 THEN
        RAISE EXCEPTION 'transfer % spans more than one household', group_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_transactions_transfer_pair
    AFTER INSERT OR UPDATE OF transfer_group_id, account_id, amount OR DELETE ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION transactions_enforce_transfer_pair();

COMMENT ON CONSTRAINT ck_transactions_transfer_has_no_category ON transactions IS
    'A transfer is not spending, so it is never filed under a category (ADR-0033).';

COMMENT ON FUNCTION transactions_enforce_transfer_pair() IS
    'Exactly two rows, two accounts, opposite signs, one household. Deferred to COMMIT because a '
    'transfer''s first leg is always invalid on its own. Amounts are NOT compared: a '
    'cross-currency pair does not sum to zero and must not be forced to (ADR-0033).';
