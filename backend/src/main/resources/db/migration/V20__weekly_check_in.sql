-- V20 — the weekly check-in, and the two kinds of rule it writes (docs/features/weekly-check-in.md).
--
-- Four tables:
--
--   * check_ins         — one per household per ISO week. A DRAFT holds nothing but who started it
--                         and the window it looks at; a COMPLETED row also carries the snapshot of
--                         what the week came to. NOT_STARTED is computed on read and never stored,
--                         as a projected bill occurrence is (ADR-0032).
--   * check_in_answers  — what a member answered, one row per item. ITEMS ARE DERIVED from the
--                         ledger on every read; only answers are stored, each with the version of
--                         the row it was given against, so completion can refuse an answer whose
--                         subject has moved (decision 9).
--   * payee_rules       — payee -> category. Created only by completing a check-in (ADR-0041).
--   * charge_rules      — (payee, account) -> bill. Created only by completing a check-in.
--
-- And two changes to existing tables, for decision 5: goal_contributions gains provenance, and
-- debt_payments' provenance check gains CHECK_IN.
--
-- Scoping is declarative, as in slices 5-13: every reference is a composite foreign key on
-- (id, household_id), so an answer cannot name another household's transaction, category, bill,
-- goal or debt, and a rule cannot name another household's payee, category, bill or account
-- (ADR-0017). The referenced tables already carry UNIQUE (id, household_id).
--
-- User references are ON DELETE SET NULL in the COLUMN-LIST form: a bare SET NULL would null
-- household_id too, and the delete of the user would then fail on the NOT NULL rather than
-- anonymise the row (docs/guides/database-style.md, Deletes).
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- --------------------------------------------------------------------------------- check_ins

CREATE TABLE check_ins (
    id               uuid           PRIMARY KEY,
    household_id     uuid           NOT NULL,
    -- The ISO week's Monday. A check-in is for a week, and a week is named by its Monday.
    week_start       date           NOT NULL,
    status           text           NOT NULL,
    -- The first day steps 1-3 look at: the day after the last completed check-in's Sunday, and
    -- never more than 28 days before this week's Sunday (decision 8). Fixed when the draft starts.
    window_start     date           NOT NULL,
    started_by       uuid,
    completed_by     uuid,
    completed_at     timestamptz,
    -- The completion snapshot. All NULL while DRAFT; written once, at completion, and never again.
    currency         text,
    week_target      numeric(19,4),
    week_spending    numeric(19,4),
    rolled_over      numeric(19,4),
    next_week_target numeric(19,4),
    debt_free_month  date,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    version          bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_check_ins_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_check_ins_started_by
        FOREIGN KEY (started_by) REFERENCES users (id) ON DELETE SET NULL (started_by),
    CONSTRAINT fk_check_ins_completed_by
        FOREIGN KEY (completed_by) REFERENCES users (id) ON DELETE SET NULL (completed_by),
    CONSTRAINT ck_check_ins_status
        CHECK (status IN ('DRAFT', 'COMPLETED')),
    CONSTRAINT ck_check_ins_week_start_is_monday
        CHECK (extract(isodow from week_start) = 1),
    -- The 28-day bound, in the database: this week plus the three before it.
    CONSTRAINT ck_check_ins_window
        CHECK (window_start <= week_start AND window_start >= week_start - 21),
    CONSTRAINT ck_check_ins_completed_at
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    -- A COMPLETED row has its snapshot; a DRAFT has none of it. A draft that carried figures would
    -- be a second, unconfirmed copy of numbers the read path recomputes anyway.
    CONSTRAINT ck_check_ins_snapshot
        CHECK (
            (status = 'COMPLETED'
                AND currency IS NOT NULL
                AND week_spending IS NOT NULL
                AND next_week_target IS NOT NULL)
            OR (status = 'DRAFT'
                AND currency IS NULL
                AND week_target IS NULL
                AND week_spending IS NULL
                AND rolled_over IS NULL
                AND next_week_target IS NULL
                AND debt_free_month IS NULL
                AND completed_by IS NULL)),
    CONSTRAINT ck_check_ins_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_check_ins_rolled_over_positive
        CHECK (rolled_over IS NULL OR rolled_over > 0),
    CONSTRAINT ck_check_ins_debt_free_month_is_first
        CHECK (debt_free_month IS NULL OR extract(day from debt_free_month) = 1),
    CONSTRAINT ck_check_ins_amount_scale
        CHECK (scale(coalesce(week_target, 0)) <= 4
           AND scale(coalesce(week_spending, 0)) <= 4
           AND scale(coalesce(rolled_over, 0)) <= 4
           AND scale(coalesce(next_week_target, 0)) <= 4),
    CONSTRAINT uq_check_ins_household_week
        UNIQUE (household_id, week_start),
    -- The composite FK target for check_in_answers.
    CONSTRAINT uq_check_ins_id_household
        UNIQUE (id, household_id)
);

-- One draft per household, as a database fact rather than a look-before-you-leap in the service
-- (decision 8): two members pressing Start on two different weeks cannot both succeed.
CREATE UNIQUE INDEX ux_check_ins_one_draft
    ON check_ins (household_id)
    WHERE status = 'DRAFT';

-- The history, newest week first. uq_check_ins_household_week already serves (household, week)
-- equality; this one serves the ordered scan.
CREATE INDEX ix_check_ins_household_week_desc
    ON check_ins (household_id, week_start DESC);

CREATE INDEX ix_check_ins_started_by ON check_ins (started_by);
CREATE INDEX ix_check_ins_completed_by ON check_ins (completed_by);

COMMENT ON TABLE check_ins IS
    'A household''s check-in for one ISO week. NOT_STARTED is computed on read. Items are derived '
    'from the ledger on every read; only answers are stored. The snapshot is written once, at '
    'completion.';

-- -------------------------------------------------------------------------- check_in_answers

CREATE TABLE check_in_answers (
    id                  uuid           PRIMARY KEY,
    household_id        uuid           NOT NULL,
    check_in_id         uuid           NOT NULL,
    item_kind           text           NOT NULL,
    choice              text           NOT NULL,
    transaction_id      uuid,
    category_id         uuid,
    bill_id             uuid,
    due_date            date,
    goal_id             uuid,
    debt_id             uuid,
    amount              numeric(19,4),
    -- The payee rule on CATEGORIZE, the charge rule on BILL_CHARGE.
    remember            boolean        NOT NULL DEFAULT false,
    -- The debt plan's one-time prompt (debt-plan.md decision 3). Never assumed.
    record_debt_payment boolean        NOT NULL DEFAULT false,
    -- Written only at completion, for a pre-answer a rule supplied, so the summary can say "3 rows
    -- filed by your rules". A draft never holds one: a GET writes nothing (api-style.md).
    by_rule             boolean        NOT NULL DEFAULT false,
    -- The version of the row the answer was given against: the transaction for steps 1 and 2, the
    -- occurrence row for step 3, NULL when the occurrence was only projected.
    subject_version     bigint,
    answered_by         uuid,
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),
    version             bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_check_in_answers_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_check_in
        FOREIGN KEY (check_in_id, household_id) REFERENCES check_ins (id, household_id)
        ON DELETE CASCADE,
    -- Every subject and target CASCADES: an answer about a row that no longer exists means nothing,
    -- and the basis is what tells the draft (decision 9).
    CONSTRAINT fk_check_in_answers_transaction
        FOREIGN KEY (transaction_id, household_id) REFERENCES transactions (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_category
        FOREIGN KEY (category_id, household_id) REFERENCES categories (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_bill
        FOREIGN KEY (bill_id, household_id) REFERENCES bills (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_goal
        FOREIGN KEY (goal_id, household_id) REFERENCES goals (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_debt
        FOREIGN KEY (debt_id, household_id) REFERENCES debts (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_check_in_answers_answered_by
        FOREIGN KEY (answered_by) REFERENCES users (id) ON DELETE SET NULL (answered_by),
    CONSTRAINT ck_check_in_answers_item_kind
        CHECK (item_kind IN
               ('OVERDUE_BILL', 'UNUSUAL_CHARGE', 'CATEGORIZE', 'BILL_CHARGE', 'ALLOCATION')),
    CONSTRAINT ck_check_in_answers_amount_scale
        CHECK (amount IS NULL OR scale(amount) <= 4),
    -- One CHECK per item kind, naming exactly which references and choices it may carry, so a
    -- mis-shaped answer is unwritable and not merely unvalidated.
    CONSTRAINT ck_check_in_answers_overdue_bill
        CHECK (item_kind <> 'OVERDUE_BILL' OR (
            choice IN ('MARK_PAID', 'SKIP', 'LEAVE')
            AND bill_id IS NOT NULL AND due_date IS NOT NULL
            AND transaction_id IS NULL AND category_id IS NULL
            AND goal_id IS NULL AND debt_id IS NULL AND amount IS NULL
            AND NOT remember
            AND (choice = 'MARK_PAID' OR NOT record_debt_payment))),
    CONSTRAINT ck_check_in_answers_unusual_charge
        CHECK (item_kind <> 'UNUSUAL_CHARGE' OR (
            choice IN ('PREPAID', 'LEAVE')
            AND transaction_id IS NOT NULL
            AND category_id IS NULL AND goal_id IS NULL AND debt_id IS NULL AND amount IS NULL
            AND NOT remember AND NOT record_debt_payment
            AND ((choice = 'PREPAID') = (bill_id IS NOT NULL AND due_date IS NOT NULL))
            AND (choice = 'PREPAID' OR (bill_id IS NULL AND due_date IS NULL)))),
    CONSTRAINT ck_check_in_answers_categorize
        CHECK (item_kind <> 'CATEGORIZE' OR (
            choice IN ('CATEGORY', 'LEAVE')
            AND transaction_id IS NOT NULL
            AND bill_id IS NULL AND due_date IS NULL
            AND goal_id IS NULL AND debt_id IS NULL AND amount IS NULL
            AND NOT record_debt_payment
            AND ((choice = 'CATEGORY') = (category_id IS NOT NULL))
            AND (choice = 'CATEGORY' OR NOT remember))),
    CONSTRAINT ck_check_in_answers_bill_charge
        CHECK (item_kind <> 'BILL_CHARGE' OR (
            choice IN ('ATTACH', 'NOT_A_BILL', 'LEAVE')
            AND transaction_id IS NOT NULL
            AND category_id IS NULL AND goal_id IS NULL AND debt_id IS NULL AND amount IS NULL
            AND ((choice = 'ATTACH') = (bill_id IS NOT NULL AND due_date IS NOT NULL))
            AND (choice = 'ATTACH' OR (bill_id IS NULL AND due_date IS NULL
                                       AND NOT remember AND NOT record_debt_payment)))),
    CONSTRAINT ck_check_in_answers_allocation
        CHECK (item_kind <> 'ALLOCATION' OR (
            choice IN ('DEBT', 'GOAL', 'ROLL_OVER')
            AND amount IS NOT NULL AND amount > 0
            AND transaction_id IS NULL AND category_id IS NULL
            AND bill_id IS NULL AND due_date IS NULL
            AND NOT remember AND NOT record_debt_payment AND NOT by_rule
            AND ((choice = 'DEBT') = (debt_id IS NOT NULL))
            AND ((choice = 'GOAL') = (goal_id IS NOT NULL)))),
    CONSTRAINT ck_check_in_answers_choice
        CHECK (choice IN ('MARK_PAID', 'SKIP', 'PREPAID', 'CATEGORY', 'ATTACH', 'NOT_A_BILL',
                          'LEAVE', 'DEBT', 'GOAL', 'ROLL_OVER'))
);

-- One answer per item. The item's identity is its subject: the transaction for odd./cat./chg.,
-- the occurrence's (bill, date) for bill., and nothing for alloc. Partial indexes rather than one
-- NULLS NOT DISTINCT constraint over every reference: a chg. ATTACH also carries its TARGET's
-- bill_id and due_date, and a key over those would let one charge hold an ATTACH and a LEAVE at
-- once (one with a target, one without) — two answers to one question.
CREATE UNIQUE INDEX ux_check_in_answers_transaction_item
    ON check_in_answers (check_in_id, item_kind, transaction_id)
    WHERE item_kind IN ('UNUSUAL_CHARGE', 'CATEGORIZE', 'BILL_CHARGE');

CREATE UNIQUE INDEX ux_check_in_answers_overdue_item
    ON check_in_answers (check_in_id, bill_id, due_date)
    WHERE item_kind = 'OVERDUE_BILL';

CREATE UNIQUE INDEX ux_check_in_answers_allocation_item
    ON check_in_answers (check_in_id)
    WHERE item_kind = 'ALLOCATION';

-- Every foreign key indexed (database-style.md): the cascades from transactions, categories,
-- bills, goals and debts would otherwise each scan this table.
CREATE INDEX ix_check_in_answers_check_in ON check_in_answers (check_in_id);
CREATE INDEX ix_check_in_answers_transaction ON check_in_answers (transaction_id);
CREATE INDEX ix_check_in_answers_category ON check_in_answers (category_id);
CREATE INDEX ix_check_in_answers_bill ON check_in_answers (bill_id);
CREATE INDEX ix_check_in_answers_goal ON check_in_answers (goal_id);
CREATE INDEX ix_check_in_answers_debt ON check_in_answers (debt_id);
CREATE INDEX ix_check_in_answers_answered_by ON check_in_answers (answered_by);

COMMENT ON TABLE check_in_answers IS
    'One member''s answer to one derived item of a check-in. Nothing it says takes effect until the '
    'check-in is completed, and completion refuses an answer whose subject_version has moved.';

-- ------------------------------------------------------------------------------- payee_rules

CREATE TABLE payee_rules (
    id           uuid           PRIMARY KEY,
    household_id uuid           NOT NULL,
    payee_id     uuid           NOT NULL,
    category_id  uuid           NOT NULL,
    created_by   uuid,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    version      bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_payee_rules_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_payee_rules_payee
        FOREIGN KEY (payee_id, household_id) REFERENCES payees (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_payee_rules_category
        FOREIGN KEY (category_id, household_id) REFERENCES categories (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_payee_rules_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL (created_by),
    -- A payee files one way. A different answer replaces the rule.
    CONSTRAINT uq_payee_rules_household_payee
        UNIQUE (household_id, payee_id)
);

CREATE INDEX ix_payee_rules_category ON payee_rules (category_id);
CREATE INDEX ix_payee_rules_payee ON payee_rules (payee_id);
CREATE INDEX ix_payee_rules_created_by ON payee_rules (created_by);

COMMENT ON TABLE payee_rules IS
    'payee -> category. Written only by completing a check-in, and it only ever PRE-ANSWERS a later '
    'check-in: import and manual entry never read it (ADR-0041).';

-- ------------------------------------------------------------------------------ charge_rules

CREATE TABLE charge_rules (
    id           uuid           PRIMARY KEY,
    household_id uuid           NOT NULL,
    bill_id      uuid           NOT NULL,
    payee_id     uuid           NOT NULL,
    account_id   uuid           NOT NULL,
    created_by   uuid,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    version      bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_charge_rules_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_charge_rules_bill
        FOREIGN KEY (bill_id, household_id) REFERENCES bills (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_charge_rules_payee
        FOREIGN KEY (payee_id, household_id) REFERENCES payees (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_charge_rules_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_charge_rules_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL (created_by),
    -- A charge pays one bill.
    CONSTRAINT uq_charge_rules_household_payee_account
        UNIQUE (household_id, payee_id, account_id)
);

CREATE INDEX ix_charge_rules_bill ON charge_rules (bill_id);
CREATE INDEX ix_charge_rules_payee ON charge_rules (payee_id);
CREATE INDEX ix_charge_rules_account ON charge_rules (account_id);
CREATE INDEX ix_charge_rules_created_by ON charge_rules (created_by);

COMMENT ON TABLE charge_rules IS
    '(payee, account) -> bill. Written only by completing a check-in, and it only ever PRE-ANSWERS a '
    'later check-in: nothing matches at import or sync (ADR-0041; slice 16 owns that).';

-- ------------------------------------------------------------------- provenance (decision 5)

-- Every existing contribution was typed by a person, so the default loses nothing. goals.md (Data
-- model) said slice 14 would add exactly this.
ALTER TABLE goal_contributions
    ADD COLUMN provenance text NOT NULL DEFAULT 'USER_LOGGED';

ALTER TABLE goal_contributions
    ADD CONSTRAINT ck_goal_contributions_provenance
        CHECK (provenance IN ('USER_LOGGED', 'CHECK_IN'));

-- Dropped and re-added rather than altered: PostgreSQL has no ALTER for a CHECK's expression. The
-- new value is the surplus a completed check-in put towards a debt; every existing row still
-- satisfies the wider check, so nothing is rewritten.
ALTER TABLE debt_payments
    DROP CONSTRAINT ck_debt_payments_provenance;

ALTER TABLE debt_payments
    ADD CONSTRAINT ck_debt_payments_provenance
        CHECK (provenance IN ('USER_LOGGED', 'AUTO_MATCHED', 'STATEMENT_CORRECTION', 'CHECK_IN'));
