-- V14 — bills, their occurrences, and income sources.
--
-- The predictable half of a household's money: what arrives on a schedule and what leaves on a
-- date (docs/features/bills-and-income.md).
--
-- Two things this schema deliberately does NOT have:
--
--   * no generated `transactions`. A bill is a PLAN (ADR-0025). Only a real transaction is ever
--     income or expense; this app is not the authority on whether money moved, and anything it
--     invents and writes into the ledger is a claim it made up.
--   * no row per future occurrence. Occurrences are PROJECTED from the bill when a period is read
--     and written only when somebody records something (ADR-0032). There is no job, so an
--     instance that was switched off for a month is not wrong when it comes back.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

CREATE TABLE bills (
    id              uuid           PRIMARY KEY,
    household_id    uuid           NOT NULL,
    name            text           NOT NULL,
    category_id     uuid,
    -- ADR-0006. What is budgeted, in the household's base currency. Never negative: a bill is
    -- money leaving, and the direction is the table, not the sign.
    expected_amount numeric(19,4)  NOT NULL,

    -- A small closed set, not a recurrence rule (ADR-0032). Projection has to be total and cheap:
    -- for any period a bill must say which dates it falls on without consulting anything.
    schedule        text           NOT NULL,
    -- MONTHLY: day of month, clamped to the month's last day. YEARLY: with schedule_month.
    day_of_month    smallint,
    -- YEARLY: 1–12.
    schedule_month  smallint,
    -- WEEKLY and FORTNIGHTLY: ISO day of week, 1 = Monday.
    day_of_week     smallint,
    -- FORTNIGHTLY: the date the every-other-week count starts from. Without it "every other
    -- Wednesday" names two different sets of Wednesdays and nothing says which.
    anchor_date     date,

    -- "About £120, give or take £22" — so a £134 payment is not a surprise and a £300 one is.
    amount_varies   boolean        NOT NULL DEFAULT false,
    tolerance       numeric(19,4),

    active          boolean        NOT NULL DEFAULT true,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    version         bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_bills_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    CONSTRAINT fk_bills_category
        FOREIGN KEY (category_id, household_id) REFERENCES categories (id, household_id)
        ON DELETE SET NULL (category_id),

    CONSTRAINT ck_bills_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 60 AND length(name) <= 60),

    CONSTRAINT ck_bills_amount_not_negative
        CHECK (expected_amount >= 0),

    CONSTRAINT ck_bills_schedule
        CHECK (schedule IN ('MONTHLY', 'YEARLY', 'WEEKLY', 'FORTNIGHTLY', 'IRREGULAR')),

    -- Each schedule carries exactly the parameters it needs and none of the others. Written out
    -- rather than left to the application because a MONTHLY bill with a day_of_week and no
    -- day_of_month projects nothing, silently, and looks like a bill nobody set up.
    CONSTRAINT ck_bills_schedule_parameters
        CHECK (
            CASE schedule
                WHEN 'MONTHLY' THEN
                    day_of_month IS NOT NULL AND schedule_month IS NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                WHEN 'YEARLY' THEN
                    day_of_month IS NOT NULL AND schedule_month IS NOT NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                WHEN 'WEEKLY' THEN
                    day_of_week IS NOT NULL AND day_of_month IS NULL
                    AND schedule_month IS NULL AND anchor_date IS NULL
                WHEN 'FORTNIGHTLY' THEN
                    day_of_week IS NOT NULL AND anchor_date IS NOT NULL
                    AND day_of_month IS NULL AND schedule_month IS NULL
                WHEN 'IRREGULAR' THEN
                    day_of_month IS NULL AND schedule_month IS NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                ELSE false
            END
        ),

    CONSTRAINT ck_bills_day_of_month
        CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 31),

    CONSTRAINT ck_bills_schedule_month
        CHECK (schedule_month IS NULL OR schedule_month BETWEEN 1 AND 12),

    CONSTRAINT ck_bills_day_of_week
        CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),

    -- A tolerance belongs to a bill that varies, and a varying bill needs one to mean anything.
    CONSTRAINT ck_bills_tolerance
        CHECK (
            (amount_varies AND tolerance IS NOT NULL AND tolerance >= 0)
            OR (NOT amount_varies AND tolerance IS NULL)
        ),

    CONSTRAINT uq_bills_id_household
        UNIQUE (id, household_id)
);

CREATE UNIQUE INDEX ux_bills_household_name
    ON bills (household_id, lower(name));

CREATE INDEX ix_bills_household_active_name
    ON bills (household_id, active, lower(name), id);

CREATE INDEX ix_bills_category_id
    ON bills (category_id);

COMMENT ON TABLE bills IS
    'A recurring obligation. A PLAN, not a transaction (ADR-0025): marking one paid records what '
    'the household says happened, and only the real transaction is ever an expense.';

-- ---------------------------------------------------------------------------------------------
-- Occurrences.
--
-- One row per period that somebody RECORDED something against. A period nobody has touched has no
-- rows at all and reads exactly like one where every occurrence is still DUE (ADR-0032). That
-- equivalence is the property worth having, and it is what makes the scheduler unnecessary.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE bill_occurrences (
    id              uuid           PRIMARY KEY,
    household_id    uuid           NOT NULL,
    bill_id         uuid           NOT NULL,
    -- The first day of the labelled month, as in `envelopes.period` (ADR-0031): a label.
    period          date           NOT NULL,
    -- Copied from the projection when the row is written, so a settled period keeps the date it
    -- was actually settled against even if the bill's due day later changes.
    due_date        date           NOT NULL,
    -- Likewise: what was budgeted AT THE TIME. The bill's current figure is not retrospective.
    expected_amount numeric(19,4)  NOT NULL,

    status          text           NOT NULL,
    -- What was really paid. Null unless PAID; it may differ from expected, and the difference is
    -- the useful number (ADR-0025).
    actual_amount   numeric(19,4),
    paid_date       date,
    transaction_id  uuid,
    -- MANUAL or AUTO_MATCHED. Always MANUAL until slice 16, and present now so that a figure the
    -- household asserted never becomes indistinguishable from one the app inferred.
    status_source   text           NOT NULL DEFAULT 'MANUAL',

    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    version         bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_bill_occurrences_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Composite, so an occurrence cannot belong to another household's bill. CASCADE because an
    -- occurrence has no meaning without its bill.
    CONSTRAINT fk_bill_occurrences_bill
        FOREIGN KEY (bill_id, household_id) REFERENCES bills (id, household_id)
        ON DELETE CASCADE,

    -- Deleting the transaction detaches the occurrence and leaves what the household recorded.
    -- The plan does not stop being true because the record of the payment was removed.
    CONSTRAINT fk_bill_occurrences_transaction
        FOREIGN KEY (transaction_id, household_id) REFERENCES transactions (id, household_id)
        ON DELETE SET NULL (transaction_id),

    CONSTRAINT ck_bill_occurrences_period_is_first_of_month
        CHECK (date_part('day', period) = 1),

    -- `period` is the month `due_date` falls in, and the two cannot disagree. Without this the
    -- denormalised label could drift from the date it labels, and a month's query would miss a
    -- row that is plainly in it.
    CONSTRAINT ck_bill_occurrences_period_holds_due_date
        CHECK (date_trunc('month', due_date)::date = period),

    CONSTRAINT ck_bill_occurrences_status
        CHECK (status IN ('DUE', 'PAID', 'SKIPPED')),

    CONSTRAINT ck_bill_occurrences_status_source
        CHECK (status_source IN ('MANUAL', 'AUTO_MATCHED')),

    CONSTRAINT ck_bill_occurrences_amounts_not_negative
        CHECK (expected_amount >= 0 AND (actual_amount IS NULL OR actual_amount >= 0)),

    -- The paid fields belong to a PAID occurrence and to no other. A DUE row carrying an actual
    -- amount would make "what did we really pay this month" quietly wrong.
    CONSTRAINT ck_bill_occurrences_paid_fields
        CHECK (
            (status = 'PAID' AND actual_amount IS NOT NULL AND paid_date IS NOT NULL)
            OR (status <> 'PAID' AND actual_amount IS NULL AND paid_date IS NULL
                AND transaction_id IS NULL)
        )
);

-- One row per bill per DUE DATE. This is the identity an occurrence actually has (ADR-0032) — the
-- surrogate key exists for Hibernate, and the due date is what the API addresses.
--
-- Not (bill_id, period), which was the first shape of this index and was wrong: a WEEKLY bill
-- falls four or five times in one month, and a uniqueness rule per month would have let a
-- household tick off one of them and no more. A month is how occurrences are LISTED; a date is
-- what one IS.
CREATE UNIQUE INDEX ux_bill_occurrences_bill_due_date
    ON bill_occurrences (bill_id, due_date);

CREATE INDEX ix_bill_occurrences_household_period
    ON bill_occurrences (household_id, period);

-- UNIQUE: the same payment cannot settle two bills, for the reason ADR-0031 gives about envelope
-- entries. Partial, because most occurrences are settled by hand and have no transaction.
CREATE UNIQUE INDEX ux_bill_occurrences_transaction_id
    ON bill_occurrences (transaction_id)
    WHERE transaction_id IS NOT NULL;

COMMENT ON COLUMN bill_occurrences.expected_amount IS
    'What was budgeted AT THE TIME this was recorded, copied from the projection. Not the bill''s '
    'current figure: changing a bill is not retrospective for a period already settled.';

-- ---------------------------------------------------------------------------------------------
-- Income.
--
-- Money arriving on a schedule. No occurrences: nobody ticks their salary off, they notice it
-- arrived. The period summary sums the sources that fall in the period.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE income_sources (
    id              uuid           PRIMARY KEY,
    household_id    uuid           NOT NULL,
    name            text           NOT NULL,
    amount          numeric(19,4)  NOT NULL,

    schedule        text           NOT NULL,
    day_of_month    smallint,
    schedule_month  smallint,
    day_of_week     smallint,
    anchor_date     date,

    -- Which account it lands in. Optional: a household may track income it cannot attribute.
    account_id      uuid,
    -- MANUAL_ESTIMATE or AUTO. Only MANUAL_ESTIMATE is written until reporting exists (slice 13):
    -- a number derived from six months of history means something different from one somebody
    -- typed, and the record has to say which.
    source_of_truth text           NOT NULL DEFAULT 'MANUAL_ESTIMATE',

    active          boolean        NOT NULL DEFAULT true,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    version         bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_income_sources_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    CONSTRAINT fk_income_sources_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE SET NULL (account_id),

    CONSTRAINT ck_income_sources_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 60 AND length(name) <= 60),

    CONSTRAINT ck_income_sources_amount_not_negative
        CHECK (amount >= 0),

    CONSTRAINT ck_income_sources_schedule
        CHECK (schedule IN ('MONTHLY', 'YEARLY', 'WEEKLY', 'FORTNIGHTLY', 'IRREGULAR')),

    -- The same parameter rules as a bill's, and for the same reason.
    CONSTRAINT ck_income_sources_schedule_parameters
        CHECK (
            CASE schedule
                WHEN 'MONTHLY' THEN
                    day_of_month IS NOT NULL AND schedule_month IS NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                WHEN 'YEARLY' THEN
                    day_of_month IS NOT NULL AND schedule_month IS NOT NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                WHEN 'WEEKLY' THEN
                    day_of_week IS NOT NULL AND day_of_month IS NULL
                    AND schedule_month IS NULL AND anchor_date IS NULL
                WHEN 'FORTNIGHTLY' THEN
                    day_of_week IS NOT NULL AND anchor_date IS NOT NULL
                    AND day_of_month IS NULL AND schedule_month IS NULL
                WHEN 'IRREGULAR' THEN
                    day_of_month IS NULL AND schedule_month IS NULL
                    AND day_of_week IS NULL AND anchor_date IS NULL
                ELSE false
            END
        ),

    CONSTRAINT ck_income_sources_day_of_month
        CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 31),

    CONSTRAINT ck_income_sources_schedule_month
        CHECK (schedule_month IS NULL OR schedule_month BETWEEN 1 AND 12),

    CONSTRAINT ck_income_sources_day_of_week
        CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),

    CONSTRAINT ck_income_sources_source_of_truth
        CHECK (source_of_truth IN ('MANUAL_ESTIMATE', 'AUTO')),

    CONSTRAINT uq_income_sources_id_household
        UNIQUE (id, household_id)
);

CREATE UNIQUE INDEX ux_income_sources_household_name
    ON income_sources (household_id, lower(name));

CREATE INDEX ix_income_sources_household_active_name
    ON income_sources (household_id, active, lower(name), id);

CREATE INDEX ix_income_sources_account_id
    ON income_sources (account_id);

COMMENT ON COLUMN income_sources.source_of_truth IS
    'MANUAL_ESTIMATE means somebody typed it. AUTO means it was derived from history, which needs '
    'reporting (slice 13) and is not written yet. A derived number and a typed one are different '
    'claims and the record has to say which.';
