-- V17 — debts, the payments recorded against them, and the payoff plan.
--
-- A debt is a balance you are reducing, with terms (docs/features/debt-plan.md). It is the one
-- planning record where arithmetic rather than a schedule decides when the number reaches zero,
-- so most of this file is about keeping the arithmetic's inputs honest.
--
-- Five things this schema deliberately does NOT have:
--
--   * no stored solved values. `apr`, `payment`, `months_left` and `payoff_month` hold ONLY what
--     the household entered; NULL is the entered/solved flag. A stored solved figure is a second
--     copy of a derivable fact, and the copy is what a reader would act on after the fact moved.
--   * no stored `balance`. The balance is an ANCHOR (`anchor_balance`, `anchor_date`,
--     `balance_source`) rolled forward by the payments after it. Same refusal as envelopes'
--     `remaining` and a bill's overdue flag.
--   * no `rung`. For AVALANCHE and SNOWBALL a debt's place in the ladder is derived from the
--     strategy; only CUSTOM stores an order. Two sources of truth for one ordering would disagree
--     the first time an APR changed (this corrects docs/domain/model.md).
--   * no generated `transactions`. A debt payment is a PLAN record (ADR-0035): logging one moves
--     no account balance and writes no ledger row. It carries a nullable `transaction_id` naming
--     the real movement when the household knows it, and that link is evidence, not authority.
--   * no monthly rate column. `apr` is the percentage a statement prints; the monthly rate is
--     `apr / 100 / 12` and lives in the solver, where it cannot drift from the figure it came from.
--
-- No GRANT block here, and that is not an omission: V10 set ALTER DEFAULT PRIVILEGES for the
-- schema owner, so every table a later migration creates is already SELECT/INSERT/UPDATE/DELETE
-- for the application role where that role exists, and a no-op where it does not — a developer's
-- database and Testcontainers have no such role (ADR-0030). V11 to V14 added nothing either.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- Needed as a composite FK target, so a debt payment can reference (bill_occurrence_id,
-- household_id) together. `bills`, `transactions`, `accounts`, `categories` and `payees` already
-- have one; nothing referenced an occurrence until now, so it is added here rather than by
-- editing V14, which is merged and therefore frozen.
ALTER TABLE bill_occurrences
    ADD CONSTRAINT uq_bill_occurrences_id_household UNIQUE (id, household_id);

-- ---------------------------------------------------------------------------------------------
-- Debts.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE debts (
    id               uuid           PRIMARY KEY,
    household_id     uuid           NOT NULL,
    name             text           NOT NULL,
    kind             text           NOT NULL,
    -- Who holds it. The sharpest disclosure in this table and the reason the whole row is
    -- redacted in logs: "Nelnet" says this household has student debt and a medical financier
    -- says something about somebody's health, with no amount attached at all.
    servicer         text,
    -- A debt is denominated in one currency, and the plan's totals are per currency (ADR-0022).
    -- Where the debt is linked to an account this MUST equal the account's currency; that cannot
    -- be expressed in SQL, so the service enforces it and `account_currency_immutable` (V9) is
    -- what keeps it true afterwards.
    currency         text           NOT NULL,

    -- A PERCENTAGE, e.g. 22.900 — what the statement prints, not a fraction. NULL means solved:
    -- the installment-loan case where a balance, a payment and a term are known and the rate is
    -- never named.
    apr              numeric(6,3),
    -- ADR-0006. Always entered, and never part of this debt's own projection: it is the floor the
    -- plan allocates to every debt below rung 1, and the second input to the "if you'd paid the
    -- minimum" comparison line.
    minimum_payment  numeric(19,4)  NOT NULL,
    -- A progress baseline and nothing else ("$4,470 paid off · 42% of $10,650"). Optional, and
    -- progress is simply absent when it is absent rather than rendered as 0%.
    original_balance numeric(19,4),

    -- The anchor: what was owed, when that was true, and how we know. The balance is NEVER
    -- solved — it is the one figure a household reads off a statement, and an app that derives
    -- what you owe from a rate and a payment schedule has replaced a fact with an inference and
    -- then put it in the headline position.
    anchor_balance   numeric(19,4)  NOT NULL,
    anchor_date      date           NOT NULL,
    balance_source   text           NOT NULL,

    -- The solver's three remaining slots, entered-only. `payment` is normally supplied by the
    -- plan's allocation and stored only when the household overrides it; `months_left` and
    -- `payoff_month` are two faces of ONE unknown, which is why the check below counts them once.
    payment          numeric(19,4),
    months_left      integer,
    -- The first day of the labelled month, as in `envelopes.period` and `bill_occurrences.period`
    -- (ADR-0031): a YearMonth, because monthly compounding cannot produce a day-accurate date and
    -- a `date` that pretended otherwise would be false precision in a column.
    payoff_month     date,

    -- CUSTOM strategy only. NULL everywhere else, where the order comes from the strategy.
    custom_order     integer,

    -- Optional links. A debt need not be held at an account this household tracks, and its
    -- minimum need not be a bill row.
    account_id       uuid,
    bill_id          uuid,

    -- Set when the balance reaches zero: it leaves the ladder, keeps its history, and writes to
    -- it are refused until a correction reopens it.
    paid_off_at      timestamptz,
    archived         boolean        NOT NULL DEFAULT false,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    version          bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_debts_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Composite, so a debt physically cannot be bound to another household's account, whatever
    -- the request body says and whatever a future query forgets to filter (ADR-0017). This is the
    -- second of the two layers the threat model names, and the one a new caller cannot forget.
    -- SET NULL (account_id) in the column-list form rather than RESTRICT: an account can be
    -- deleted while it is empty, and that must not require unpicking a debt first.
    CONSTRAINT fk_debts_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE SET NULL (account_id),

    -- Deleting the bill that pays this minimum nulls the link and leaves the debt alone. The
    -- consequence is correct and surprising, and the page says so: that minimum starts counting
    -- into the Bills & Income committed figure, so the committed total RISES when a bill is
    -- deleted.
    --
    -- The column list is load-bearing. A bare ON DELETE SET NULL would try to null `household_id`
    -- too, which is NOT NULL, so the bill delete would fail instead of detaching — the trap
    -- transactions.md already hit on `category_id`. Needs PostgreSQL 15+.
    CONSTRAINT fk_debts_bill
        FOREIGN KEY (bill_id, household_id) REFERENCES bills (id, household_id)
        ON DELETE SET NULL (bill_id),

    CONSTRAINT ck_debts_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 60 AND length(name) <= 60),

    -- Optional, and an empty string is not a servicer — it is a NULL that took a detour.
    CONSTRAINT ck_debts_servicer
        CHECK (servicer IS NULL
               OR (length(btrim(servicer)) BETWEEN 1 AND 80 AND length(servicer) <= 80)),

    CONSTRAINT ck_debts_kind
        CHECK (kind IN ('CREDIT_CARD', 'INSTALLMENT')),

    -- Three uppercase letters, like households.base_currency and accounts.currency.
    CONSTRAINT ck_debts_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    -- LINKED_STATEMENT needs a connected account (slice 16) and is unreachable today. The value
    -- is legal from day one so that nothing has to be backfilled, and so that a figure a
    -- statement confirmed never becomes indistinguishable from one the app extrapolated.
    CONSTRAINT ck_debts_balance_source
        CHECK (balance_source IN ('CONFIRMED', 'ESTIMATED', 'LINKED_STATEMENT')),

    -- THE DEGREES-OF-FREEDOM RULE. One equation relates the balance, the rate, the payment and
    -- the horizon, so at most two of `apr`, `payment` and the horizon may be entered — the third
    -- is what the solver works out. Three entered values over-determine the equation, and the
    -- answer to that is `422 terms-over-determined`, never silently dropping a number somebody
    -- typed.
    --
    -- `months_left` and `payoff_month` are ONE unknown with two faces, so the first clause
    -- refuses both being set even though that is only "two": they are the same question asked in
    -- different units, and two stored answers to it can disagree.
    --
    -- Bean Validation says the same thing at the edge, for the error message. This is the half
    -- that a new caller, an importer, or a hand-run UPDATE cannot bypass — and without it the
    -- solver has no defined solve target and would pick one, which is exactly the silent
    -- replacement of an entered figure the feature forbids.
    --
    -- There is no lower bound. Nothing entered at all is under-determined arithmetic, not a
    -- corrupt row — the projection is simply unavailable — and a floor here would refuse the
    -- transaction that releases two fields before re-entering one. The edge refuses an
    -- under-determined set naming the fields, which is where that message belongs.
    CONSTRAINT ck_debts_degrees_of_freedom
        CHECK (
            NOT (months_left IS NOT NULL AND payoff_month IS NOT NULL)
            AND (apr IS NOT NULL)::int
              + (payment IS NOT NULL)::int
              + (months_left IS NOT NULL OR payoff_month IS NOT NULL)::int <= 2
        ),

    -- Zero is a real promotional and car-loan rate, so the lower bound is inclusive: interest is
    -- zero, the recurrence still terminates, and nothing divides by a rate. The upper bound is
    -- 1000 rather than 100 because a payday rate above 100% is real, and a household that cannot
    -- record its worst debt is worse off than one looking at an alarming number.
    --
    -- The upper bound is where NUMERIC(6,3) already stops, so in practice the TYPE refuses 1000
    -- first and this half of the check never fires. It is stated anyway: a later migration that
    -- widened the column would otherwise silently remove the ceiling along with the overflow.
    CONSTRAINT ck_debts_apr_range
        CHECK (apr IS NULL OR (apr >= 0 AND apr < 1000)),

    -- Zero is legal: a card in a promotional period with no minimum due, and a debt whose
    -- minimum the household does not know yet, are both better recorded than refused.
    --
    -- There is deliberately NO equivalent check on `anchor_balance`. A confirmed zero is how a
    -- debt is paid off, and a NEGATIVE one is an overpayment the screen must be able to show —
    -- clamping it would hide a real error in somebody's favour. A debt *created* at zero is a 422
    -- at the edge, which is a different claim about a different moment. Please do not "fix" this.
    CONSTRAINT ck_debts_minimum_payment_not_negative
        CHECK (minimum_payment >= 0),

    -- An entered payment of zero or less clears nothing; the plan would project forever and the
    -- solver answers `terms-unsolvable`. Note this says nothing about a payment the PLAN assigns,
    -- which is not stored here at all.
    CONSTRAINT ck_debts_payment_positive
        CHECK (payment IS NULL OR payment > 0),

    -- Progress is (original - balance) / original, so a zero baseline is a division by zero and a
    -- negative one inverts the percentage. Absent is the honest value, and the screen renders no
    -- progress rather than 0%.
    CONSTRAINT ck_debts_original_balance_positive
        CHECK (original_balance IS NULL OR original_balance > 0),

    -- "Gone in zero months" is not a horizon, and a negative one is a date in the past, which is
    -- `terms-unsolvable` at the edge.
    CONSTRAINT ck_debts_months_left_positive
        CHECK (months_left IS NULL OR months_left > 0),

    -- A YearMonth in a `date` column is only a YearMonth while every row agrees on which day
    -- stands for the month. One row anchored mid-month would sort and compare wrongly against
    -- every other.
    CONSTRAINT ck_debts_payoff_month_is_first_of_month
        CHECK (payoff_month IS NULL OR date_part('day', payoff_month) = 1),

    CONSTRAINT ck_debts_custom_order_not_negative
        CHECK (custom_order IS NULL OR custom_order >= 0),

    CONSTRAINT uq_debts_id_household
        UNIQUE (id, household_id)
);

-- Serves GET /api/debts — one household's unarchived debts — and the countByHouseholdId the
-- per-household debt cap consults before every insert, which is the control that stops a cheap
-- POST loop amplifying into an expensive GET of two projections (the cap itself is the service's,
-- not the schema's). Also the household foreign key's own index, which the CASCADE consults.
--
-- There is deliberately no unique index on (household_id, lower(name)), unlike bills and income
-- sources: the plan's tie-break is "APR, then balance, then name, then id", which only has a
-- reason to exist because two debts may legitimately carry the same name.
CREATE INDEX ix_debts_household_archived_name
    ON debts (household_id, archived, lower(name), id);

-- The account foreign key's own index, for the SET NULL scan when an account is deleted.
CREATE INDEX ix_debts_account_id
    ON debts (account_id);

-- UNIQUE: one bill pays at most one debt's minimum, which is what makes "each counted once" an
-- invariant rather than a convention. Two debts pointing at the same bill row would subtract the
-- same minimum twice from what's left, and both figures would look plausible.
--
-- Partial, because most debts have no bill. It is also the FK's own index: `bill_id = $1` implies
-- `bill_id IS NOT NULL`, so PostgreSQL can use it for the parent-delete scan.
CREATE UNIQUE INDEX ux_debts_bill_id
    ON debts (bill_id)
    WHERE bill_id IS NOT NULL;

COMMENT ON TABLE debts IS
    'A balance being reduced, with terms. The balance is an ANCHOR plus the payments after it, '
    'never a stored total; only ENTERED terms are stored, and NULL is the entered/solved flag.';

COMMENT ON COLUMN debts.apr IS
    'A PERCENTAGE as a statement prints it (22.900), not a fraction. NULL means solved: the rate '
    'is derived from the balance, the payment and the term. No column holds the monthly rate.';

COMMENT ON COLUMN debts.anchor_balance IS
    'What was owed on anchor_date, qualified by balance_source. Never derived from the terms, and '
    'deliberately unconstrained in sign: zero is a cleared debt and negative is an overpayment.';

COMMENT ON COLUMN debts.bill_id IS
    'The bill whose occurrence pays this minimum, set by the household and NEVER inferred from a '
    'name or an amount. It is what keeps a minimum from being counted in both places.';

-- ---------------------------------------------------------------------------------------------
-- Payments.
--
-- The audit trail the balance is derived from. A payment is a statement about a balance held by a
-- third party, of which the money leaving the account is only one half — and that half is very
-- often already in the ledger, or about to arrive by import (ADR-0035).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE debt_payments (
    id                 uuid           PRIMARY KEY,
    household_id       uuid           NOT NULL,
    debt_id            uuid           NOT NULL,
    paid_date          date           NOT NULL,

    -- ADR-0006, and all four are what was true AT THE TIME. The split is stored rather than
    -- derived on read precisely so that a later APR change cannot rewrite it: recomputing a 2024
    -- payment against today's rate would rewrite history to agree with the present, which is the
    -- one thing an audit trail must not do.
    amount             numeric(19,4)  NOT NULL,
    interest           numeric(19,4)  NOT NULL,
    principal          numeric(19,4)  NOT NULL,
    balance_after      numeric(19,4)  NOT NULL,

    -- How the row got here, exactly as `status_source` does for a bill occurrence. Slice 10
    -- writes USER_LOGGED and STATEMENT_CORRECTION; AUTO_MATCHED needs a connected account
    -- (slice 16) and exists now so a figure somebody asserted never becomes indistinguishable
    -- from one the app inferred.
    provenance         text           NOT NULL,

    -- The real movement, when the household knows it. Evidence, not the source of truth.
    transaction_id     uuid,
    -- Set when marking a linked bill's occurrence paid writes this payment — after a prompt,
    -- never silently, because a balance that moves on its own cannot be audited.
    bill_occurrence_id uuid,

    note               text,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),
    version            bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_debt_payments_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Composite, so a payment cannot belong to another household's debt. CASCADE because a
    -- payment has no meaning without its debt: deleting a debt takes its own history and nothing
    -- else, exactly as deleting an envelope takes its log and no transactions.
    CONSTRAINT fk_debt_payments_debt
        FOREIGN KEY (debt_id, household_id) REFERENCES debts (id, household_id)
        ON DELETE CASCADE,

    -- Deleting the ledger row leaves the payment standing, with its link cleared, its split
    -- intact and the debt balance unmoved (ADR-0035). The alternative — removing the payment with
    -- the transaction — would mean tidying up a mistyped ledger row silently changed what the
    -- household owes.
    --
    -- Column-list form, for the same reason as fk_debts_bill above: a bare SET NULL would try to
    -- null `household_id` and the transaction delete would fail instead.
    CONSTRAINT fk_debt_payments_transaction
        FOREIGN KEY (transaction_id, household_id) REFERENCES transactions (id, household_id)
        ON DELETE SET NULL (transaction_id),

    -- Likewise for the occurrence: unticking a bill does not retract what was paid against the
    -- debt.
    CONSTRAINT fk_debt_payments_bill_occurrence
        FOREIGN KEY (bill_occurrence_id, household_id)
        REFERENCES bill_occurrences (id, household_id)
        ON DELETE SET NULL (bill_occurrence_id),

    CONSTRAINT ck_debt_payments_provenance
        CHECK (provenance IN ('USER_LOGGED', 'AUTO_MATCHED', 'STATEMENT_CORRECTION')),

    -- The split reconciles EXACTLY, by construction: the interest is rounded HALF_EVEN at scale 4
    -- and the principal takes the remainder, so no payment ever loses or invents a cent. Stated
    -- here because the rounding happens in Java, and an off-by-a-cent split is the defect nobody
    -- would see on screen — both figures look right and only their sum is wrong.
    CONSTRAINT ck_debt_payments_split_reconciles
        CHECK (interest + principal = amount),

    -- A note is optional, and an empty string is not a note.
    CONSTRAINT ck_debt_payments_note
        CHECK (note IS NULL OR (length(btrim(note)) BETWEEN 1 AND 500 AND length(note) <= 500))

    -- No sign checks on `amount`, `interest`, `principal` or `balance_after`, and all four
    -- omissions are deliberate. A STATEMENT_CORRECTION is the arithmetic being overruled by a
    -- statement and routinely runs the other way ("Statement corrected +$4.10"), which is a
    -- negative amount against a rising balance; `balance_after` goes below zero when a payment
    -- overpays, and the screen says the debt is overpaid by that much rather than clamping it;
    -- and interest can be zero at 0% APR. A direction constraint here would refuse the honest
    -- record in all four cases.
);

-- The history: one debt's payments, newest first, with a stable tiebreak on id so the page
-- envelope cannot repeat or skip a row (GET /api/debts/{id}/payments). Also indexes the debt
-- foreign key, which the CASCADE above consults.
CREATE INDEX ix_debt_payments_debt_paid_date
    ON debt_payments (debt_id, paid_date DESC, id DESC);

-- Rolls every anchor in the household forward in ONE query, which is what keeps GET /api/debts
-- and GET /api/debt-plan from issuing a payment query per debt. Also the household foreign key's
-- own index.
CREATE INDEX ix_debt_payments_household_paid_date
    ON debt_payments (household_id, paid_date DESC, id DESC);

-- UNIQUE: a transaction settles at most one debt payment, EVER — ADR-0031's argument about one
-- transaction and one envelope entry, and the rule bills already apply across occurrences.
-- Counting one charge against two debts would understate both balances plausibly, which is the
-- kind of wrong nobody notices. Partial, because most payments are logged by hand.
CREATE UNIQUE INDEX ux_debt_payments_transaction_id
    ON debt_payments (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- Likewise one occurrence, one payment: the prompt on marking a bill paid must not be able to
-- record the same month's car payment twice.
CREATE UNIQUE INDEX ux_debt_payments_bill_occurrence_id
    ON debt_payments (bill_occurrence_id)
    WHERE bill_occurrence_id IS NOT NULL;

COMMENT ON TABLE debt_payments IS
    'A payment recorded against a debt. A PLAN record, not a transaction (ADR-0035): it moves no '
    'account balance and writes no ledger row. transaction_id is evidence, not authority.';

COMMENT ON COLUMN debt_payments.interest IS
    'What was true under the rate in force on paid_date, stored rather than derived so a later '
    'APR change cannot rewrite it. interest + principal = amount, exactly.';

-- ---------------------------------------------------------------------------------------------
-- The plan, and the snapshots that make "4 months earlier than last month's plan" possible.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payoff_plans (
    id             uuid           PRIMARY KEY,
    household_id   uuid           NOT NULL,
    -- AVALANCHE, SNOWBALL or CUSTOM. The selection, not the computation: BOTH named strategies
    -- are computed on every read whichever is selected, because the comparison card is a product
    -- requirement rather than a view of the selection.
    strategy       text           NOT NULL,
    -- What the household puts towards debt each month, in the household's base currency. The
    -- order, the per-debt allocation, the payoff months, the total interest and the debt-free
    -- month are all derived from this and the debts; none of them are stored here.
    monthly_amount numeric(19,4)  NOT NULL,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    version        bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_payoff_plans_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- EXACTLY ONE plan per household (ADR-0026), which is why /api/debt-plan is singular. Without
    -- this a second row is a second answer to "what is my strategy", and the endpoint would have
    -- to pick one — silently, and differently as rows were added.
    CONSTRAINT uq_payoff_plans_household
        UNIQUE (household_id),

    CONSTRAINT ck_payoff_plans_strategy
        CHECK (strategy IN ('AVALANCHE', 'SNOWBALL', 'CUSTOM')),

    -- Not negative, and deliberately NOT "at least the sum of the minimums". A household that
    -- genuinely cannot meet its minimums exists, and refusing their plan — at the edge or here —
    -- tells somebody in trouble that their situation is invalid input. The plan says plainly that
    -- the amount is below the minimums and builds no order from it.
    -- No UNIQUE (id, household_id) here, unlike every other table in this file: nothing
    -- references a plan, and the pair would be an index bought for a composite foreign key that
    -- does not exist. The snapshots carry their household directly.
    CONSTRAINT ck_payoff_plans_monthly_amount_not_negative
        CHECK (monthly_amount >= 0)
);

COMMENT ON TABLE payoff_plans IS
    'One row per household (ADR-0026). Stores the two figures the household chose; the order, the '
    'allocation, the payoff months and the totals are derived on every read.';

CREATE TABLE payoff_plan_snapshots (
    id              uuid           PRIMARY KEY,
    household_id    uuid           NOT NULL,
    taken_at        timestamptz    NOT NULL DEFAULT now(),
    -- The plan as it was, copied rather than referenced: the whole point is to survive the plan
    -- changing, and a foreign key to the live row would show today's figures under yesterday's
    -- date. There is no plan_id for the same reason, and because one plan per household means
    -- household_id already names it.
    strategy        text           NOT NULL,
    monthly_amount  numeric(19,4)  NOT NULL,
    -- Plan-level totals are PER CURRENCY (ADR-0022): a household with debts in two currencies
    -- gets one snapshot row per currency and no combined figure, because a combined one would
    -- need a rate the instance may not have.
    currency        text           NOT NULL,
    -- Both NULL together when the projection could not be computed — a debt that never clears, or
    -- a monthly amount below the minimums. NULL is the honest answer and the delta is simply not
    -- shown; a zero would read as "debt-free, this month".
    debt_free_month date,
    total_interest  numeric(19,4),
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT fk_payoff_plan_snapshots_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    CONSTRAINT ck_payoff_plan_snapshots_strategy
        CHECK (strategy IN ('AVALANCHE', 'SNOWBALL', 'CUSTOM')),

    CONSTRAINT ck_payoff_plan_snapshots_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_payoff_plan_snapshots_monthly_amount_not_negative
        CHECK (monthly_amount >= 0),

    -- A YearMonth, like debts.payoff_month, and the same reason for pinning the day.
    CONSTRAINT ck_payoff_plan_snapshots_debt_free_month_is_first_of_month
        CHECK (debt_free_month IS NULL OR date_part('day', debt_free_month) = 1),

    -- The two projection columns are BOTH NULL or BOTH SET. The comment on debt_free_month above
    -- already said so and nothing enforced it, which is the gap worth closing before anything
    -- writes here: half a projection renders as half a comparison. A debt-free month with no
    -- total interest would show a date the household cannot check, and a total with no date would
    -- show a cost with no end.
    CONSTRAINT ck_payoff_plan_snapshots_projection_is_whole
        CHECK ((debt_free_month IS NULL) = (total_interest IS NULL))
);

-- One snapshot per currency per plan change.
--
-- taken_at defaults to now(), which in PostgreSQL is TRANSACTION START time and therefore
-- identical for every row a single transaction writes. A household with debts in two currencies
-- correctly gets two rows here; a retry, a double-call or a loop that ran twice would get two
-- rows for the SAME currency at the same instant, and "what did the plan say last time" would
-- have two answers with nothing to choose between them.
CREATE UNIQUE INDEX ux_payoff_plan_snapshots_household_taken_at_currency
    ON payoff_plan_snapshots (household_id, taken_at, currency);

-- "What did the plan say last time", newest first — the only query this table has. Also the
-- household foreign key's own index.
CREATE INDEX ix_payoff_plan_snapshots_household_taken_at
    ON payoff_plan_snapshots (household_id, taken_at DESC, id DESC);

COMMENT ON TABLE payoff_plan_snapshots IS
    'The projection as it stood when the plan last changed, per currency. Write-once and never '
    'updated, which is why there is no version column: a snapshot cannot be backfilled, so '
    '"4 months earlier than last month''s plan" has nothing to compare against unless it was '
    'taken at the time.';
