-- V18 — goals, their monthly plan, and the contributions recorded against them.
--
-- A goal is money a household is putting aside for something, with a target and, optionally, a
-- month it wants it by (docs/features/goals.md). Three tables:
--
--   * goals                    — the goal itself. No `saved`, no `remaining`, no `reached`: all
--                                three are derived on read from the rows below, because a stored
--                                copy needs something to keep it true when a contribution is
--                                edited or a withdrawal recorded, and nothing would.
--   * goal_contribution_steps  — the monthly plan, EFFECTIVE-DATED (decision 4). The amount in
--                                force for a month is the latest step at or before it. A change
--                                applies from the current month forwards, so raising the plan in
--                                September cannot rewrite what August's left-over figure said.
--   * goal_contributions       — what the household recorded setting aside, signed (decision 6):
--                                a negative row is a withdrawal. A PLAN record, not a transaction
--                                (ADR-0035's reasoning, unchanged): it writes no ledger row and
--                                moves no account balance. transaction_id is evidence, not truth.
--
-- Scoping is declarative, as in slices 5–10: every foreign key is composite on
-- (id, household_id), so a goal cannot reference another household's account and a contribution
-- cannot reference another household's transaction (ADR-0017). `accounts` and `transactions`
-- already carry UNIQUE (id, household_id) from V12 and V13.
--
-- No `provenance` and no `created_by`. Every row in this slice is typed by a person; the check-in
-- (slice 14) adds provenance with existing rows defaulted to USER_LOGGED, which loses nothing.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

CREATE TABLE goals (
    id              uuid           PRIMARY KEY,
    household_id    uuid           NOT NULL,
    name            text           NOT NULL,
    -- Fixed at creation. A goal with contributions in pounds cannot become a goal in euros without
    -- every figure being reinterpreted — the reasoning behind account-currency-immutable.
    currency        text           NOT NULL,
    target_amount   numeric(19,4)  NOT NULL,
    -- A month, not a day (decision 5): the first of the month, or NULL for "no date", which is
    -- normal. An emergency fund has none.
    target_month    date,
    -- What was already set aside when tracking started. Counts towards saved; NEVER towards any
    -- month's left-over figure, because that money was set aside before this month.
    starting_amount numeric(19,4)  NOT NULL DEFAULT 0,
    -- Where the household keeps the money. Informational only (decision 1): one savings account
    -- often holds several goals, so its balance cannot say how much belongs to each.
    account_id      uuid,
    archived        boolean        NOT NULL DEFAULT false,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_goals_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    -- The column-list form: a bare SET NULL would try to null household_id too, and the delete of
    -- the account would then fail on the NOT NULL rather than detach the goal.
    CONSTRAINT fk_goals_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE SET NULL (account_id),
    -- Bounded raw as well as trimmed: the V8 / accounts lesson. A trimmed-only bound lets a
    -- client store a megabyte of spaces around one letter.
    CONSTRAINT ck_goals_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 80 AND length(name) <= 80),
    CONSTRAINT ck_goals_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_goals_target_amount_positive
        CHECK (target_amount > 0),
    CONSTRAINT ck_goals_starting_amount_not_negative
        CHECK (starting_amount >= 0),
    CONSTRAINT ck_goals_target_month_is_first_of_month
        CHECK (target_month IS NULL OR date_part('day', target_month) = 1),
    -- The composite FK target for both child tables. Created HERE rather than altered on later:
    -- V17 had to ALTER one onto bill_occurrences after the fact.
    CONSTRAINT uq_goals_id_household
        UNIQUE (id, household_id)
);

-- Backs 409 goal-name-taken. Among UNARCHIVED goals only, so a retired "Holiday" does not stop
-- next year's; case-insensitive, because the check-in's "put the surplus in Holiday" and Owl's
-- "Japan trip" name a goal and could not tell "holiday" from "Holiday".
CREATE UNIQUE INDEX ux_goals_household_name_unarchived
    ON goals (household_id, lower(name))
    WHERE NOT archived;

CREATE INDEX ix_goals_household_archived
    ON goals (household_id, archived);

CREATE INDEX ix_goals_account_id
    ON goals (account_id);

COMMENT ON TABLE goals IS
    'Money a household is putting aside for something. saved, remaining and reached are DERIVED '
    'from starting_amount and goal_contributions on every read and stored nowhere.';
COMMENT ON COLUMN goals.account_id IS
    'Where the money is kept. INFORMATIONAL, never the source of progress: one account often '
    'holds several goals, and its balance cannot say how much belongs to each.';

CREATE TABLE goal_contribution_steps (
    id             uuid           PRIMARY KEY,
    household_id   uuid           NOT NULL,
    goal_id        uuid           NOT NULL,
    -- The first month this amount applies to. A label, as envelopes.period is (ADR-0031).
    from_period    date           NOT NULL,
    -- Zero is a valid step and means paused.
    monthly_amount numeric(19,4)  NOT NULL,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT fk_goal_contribution_steps_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_goal_contribution_steps_goal
        FOREIGN KEY (goal_id, household_id) REFERENCES goals (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_goal_contribution_steps_monthly_amount_not_negative
        CHECK (monthly_amount >= 0),
    CONSTRAINT ck_goal_contribution_steps_from_period_is_first_of_month
        CHECK (date_part('day', from_period) = 1),
    -- One step per month, which with the service's refusal of past months bounds the plan to at
    -- most one new row a month.
    CONSTRAINT uq_goal_contribution_steps_goal_from_period
        UNIQUE (goal_id, from_period)
);

CREATE INDEX ix_goal_contribution_steps_household_goal
    ON goal_contribution_steps (household_id, goal_id, from_period);

COMMENT ON TABLE goal_contribution_steps IS
    'The monthly plan, effective-dated: the amount in force for a month is the latest step whose '
    'from_period is at or before it, and zero before the first. Changed from the current month '
    'forwards only, so a past month''s left-over figure is never rewritten.';

CREATE TABLE goal_contributions (
    id             uuid           PRIMARY KEY,
    household_id   uuid           NOT NULL,
    goal_id        uuid           NOT NULL,
    contributed_on date           NOT NULL,
    -- The month it counts towards: a LABEL (ADR-0031), set from contributed_on's calendar month in
    -- slice 11. Stored, so a configurable month start changes where new rows land and rewrites
    -- none.
    period         date           NOT NULL,
    -- Signed and never zero (decision 6). Negative is a withdrawal: an emergency fund exists to
    -- be spent. saved is not clamped.
    amount         numeric(19,4)  NOT NULL,
    note           text,
    transaction_id uuid,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    version        bigint         NOT NULL DEFAULT 0,
    CONSTRAINT fk_goal_contributions_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_goal_contributions_goal
        FOREIGN KEY (goal_id, household_id) REFERENCES goals (id, household_id)
        ON DELETE CASCADE,
    -- Deleting the transaction leaves the contribution standing with the link cleared: the
    -- household said it set this money aside, and that stays true when the evidence is removed.
    CONSTRAINT fk_goal_contributions_transaction
        FOREIGN KEY (transaction_id, household_id) REFERENCES transactions (id, household_id)
        ON DELETE SET NULL (transaction_id),
    CONSTRAINT ck_goal_contributions_amount_not_zero
        CHECK (amount <> 0),
    CONSTRAINT ck_goal_contributions_period_is_first_of_month
        CHECK (date_part('day', period) = 1),
    CONSTRAINT ck_goal_contributions_note
        CHECK (note IS NULL OR (length(btrim(note)) BETWEEN 1 AND 500 AND length(note) <= 500))
);

-- Serves recorded(g, P) and savedBefore(P): both are a sum over one goal's rows by period.
CREATE INDEX ix_goal_contributions_household_goal_period
    ON goal_contributions (household_id, goal_id, period);

-- The history's order: newest first, ties broken by insertion time and then id, because a random
-- UUID alone is not a tiebreak (docs/memory/gotchas.md).
CREATE INDEX ix_goal_contributions_goal_history
    ON goal_contributions (goal_id, contributed_on DESC, created_at DESC, id DESC);

-- One transaction is evidence for at most one contribution.
CREATE UNIQUE INDEX ux_goal_contributions_transaction_id
    ON goal_contributions (transaction_id)
    WHERE transaction_id IS NOT NULL;

COMMENT ON TABLE goal_contributions IS
    'Money a household recorded setting aside for a goal, or taking out of it. A PLAN record, not '
    'a transaction (ADR-0035): it moves no account balance. transaction_id is evidence only.';
