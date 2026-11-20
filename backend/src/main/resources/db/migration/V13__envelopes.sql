-- V13 — envelopes and their entries.
--
-- An envelope is an amount you decided on and spend down (docs/features/envelopes.md). It is the
-- planning primitive for variable spending, and the one thing a spreadsheet does worst: the
-- remaining figure has to exist while somebody is still standing in the shop, not be totalled at
-- month end when it can no longer change a decision.
--
-- Three things this schema deliberately does NOT have:
--
--   * no `remaining` — it is `amount` minus the sum of the entries, computed on read. A stored
--     copy is wrong the instant an entry lands, which is the only moment it is worth anything.
--   * no `rollover` — ADR-0031. A column that is always false reads as a feature and is not one.
--   * no period start/end pair — `period` is a LABEL (the first day of the labelled month), so
--     slice 9's configurable month start changes what people log where, not what any row means.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- Needed as a composite FK target. `accounts`, `categories` and `payees` already have one; nothing
-- referenced a transaction until now, so this is where it is added rather than by editing V12.
-- Append-only (ADR-0007) is the habit even for a migration that has not merged yet: reasoning
-- about who has already run which file is exactly the reasoning the rule exists to avoid.
ALTER TABLE transactions
    ADD CONSTRAINT uq_transactions_id_household UNIQUE (id, household_id);

CREATE TABLE envelopes (
    id            uuid           PRIMARY KEY,
    household_id  uuid           NOT NULL,
    name          text           NOT NULL,
    -- Optional. An envelope is a decision about money, and "Adhoc" is a perfectly good envelope
    -- with no category behind it. Deleting the category nulls this rather than the envelope.
    category_id   uuid,
    -- The first day of the labelled month: 2026-08-01 is "the envelope for August 2026". It is
    -- NOT a lower bound on entry dates — an entry belongs to an envelope because somebody logged
    -- it there (ADR-0031).
    period        date           NOT NULL,
    -- ADR-0006. What was decided on, in the household's base currency.
    amount        numeric(19,4)  NOT NULL,
    archived      boolean        NOT NULL DEFAULT false,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    version       bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_envelopes_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Deleting a category must never delete a plan. `SET NULL (category_id)` names the column so
    -- household_id is left alone — see V12 for why a bare SET NULL would fail.
    CONSTRAINT fk_envelopes_category
        FOREIGN KEY (category_id, household_id) REFERENCES categories (id, household_id)
        ON DELETE SET NULL (category_id),

    CONSTRAINT ck_envelopes_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 60 AND length(name) <= 60),

    -- A period is a month label, so it is always the first of a month. Enforced rather than
    -- trusted: a row dated the 17th would sort and group correctly and mean something subtly
    -- different from every other row, which is the worst kind of wrong.
    CONSTRAINT ck_envelopes_period_is_first_of_month
        CHECK (date_part('day', period) = 1),

    -- An envelope of zero is somebody deciding to spend nothing, which is a real decision. A
    -- NEGATIVE envelope is not: you cannot set aside minus forty pounds.
    CONSTRAINT ck_envelopes_amount_not_negative
        CHECK (amount >= 0),

    CONSTRAINT uq_envelopes_id_household
        UNIQUE (id, household_id)
);

-- One envelope per name per period, case-insensitively. "Groceries" twice in August is a
-- data-entry mistake; "Groceries" in August and in September are two different decisions and both
-- must exist.
CREATE UNIQUE INDEX ux_envelopes_household_period_name
    ON envelopes (household_id, period, lower(name));

-- Serves the default listing: one household's envelopes for one period, ordered by name
-- case-insensitively with a stable tiebreak on id.
CREATE INDEX ix_envelopes_household_period_name
    ON envelopes (household_id, period, lower(name), id);

-- The category foreign key's own index. The roll-up index above leads with household_id, so it
-- cannot serve the scan PostgreSQL does when a category is deleted — see V12's note, which is
-- there because SchemaMigrationIT caught exactly this omission.
CREATE INDEX ix_envelopes_category_id
    ON envelopes (category_id);

COMMENT ON COLUMN envelopes.period IS
    'The first day of the labelled month. A LABEL, not a boundary: an entry belongs to an '
    'envelope because somebody logged it there, not because its date falls in a range (ADR-0031).';

-- ---------------------------------------------------------------------------------------------
-- Entries.
--
-- The 81-times-a-month action. An amount alone is a valid entry: payee optional, note optional,
-- category inherited from the envelope. Anything that makes more than the amount mandatory has
-- broken the feature.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE envelope_entries (
    id             uuid           PRIMARY KEY,
    household_id   uuid           NOT NULL,
    envelope_id    uuid           NOT NULL,
    date           date           NOT NULL,
    -- POSITIVE, and that is the difference from `transactions.amount`. A transaction records which
    -- way money went, so it is signed; an envelope entry records how much of a decided amount was
    -- used, which only goes one way. A signed entry would make `remaining` ambiguous.
    amount         numeric(19,4)  NOT NULL,
    payee_id       uuid,
    note           text,
    -- MANUAL: somebody typed it. SYNCED: somebody ATTACHED a real transaction to this envelope.
    -- Never the app matching on its own — docs/features/envelopes.md is explicit that a
    -- transaction never files itself, so SYNCED still means a person decided.
    source         text           NOT NULL DEFAULT 'MANUAL',
    transaction_id uuid,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    version        bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_envelope_entries_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Composite, so an entry cannot belong to another household's envelope. CASCADE because an
    -- entry has no meaning without its envelope — unlike a transaction, which is a record of
    -- something that happened in the world and outlives any plan it was filed under.
    CONSTRAINT fk_envelope_entries_envelope
        FOREIGN KEY (envelope_id, household_id) REFERENCES envelopes (id, household_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_envelope_entries_payee
        FOREIGN KEY (payee_id, household_id) REFERENCES payees (id, household_id)
        ON DELETE SET NULL (payee_id),

    -- Deleting a transaction detaches the entry and leaves the amount: somebody decided that money
    -- came out of this envelope, and the plan does not stop being true because the record of the
    -- purchase was removed. The entry becomes an ordinary one with no source transaction, which
    -- `ck_envelope_entries_source` tolerates in exactly that direction.
    CONSTRAINT fk_envelope_entries_transaction
        FOREIGN KEY (transaction_id, household_id) REFERENCES transactions (id, household_id)
        ON DELETE SET NULL (transaction_id),

    CONSTRAINT ck_envelope_entries_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ck_envelope_entries_note
        CHECK (note IS NULL OR (length(btrim(note)) BETWEEN 1 AND 500 AND length(note) <= 500)),

    CONSTRAINT ck_envelope_entries_source
        CHECK (source IN ('MANUAL', 'SYNCED')),

    -- A MANUAL entry must not carry a transaction: that is what SYNCED means, and the two would
    -- disagree about where the figure came from. The reverse is permitted — a SYNCED entry whose
    -- transaction was later deleted keeps its source, because the person really did attach one.
    CONSTRAINT ck_envelope_entries_manual_has_no_transaction
        CHECK (source = 'SYNCED' OR transaction_id IS NULL)
);

-- The log: one envelope's entries, newest first, with a stable tiebreak so paging cannot repeat
-- or skip a row. Also indexes the envelope foreign key, which the CASCADE above consults.
CREATE INDEX ix_envelope_entries_envelope_date
    ON envelope_entries (envelope_id, date DESC, id DESC);

CREATE INDEX ix_envelope_entries_household_date
    ON envelope_entries (household_id, date DESC, id DESC);

-- The payee and transaction foreign keys' own indexes, for the parent-delete scan.
CREATE INDEX ix_envelope_entries_payee_id
    ON envelope_entries (payee_id);

-- UNIQUE, and this is the integrity rule that matters most here (ADR-0031): one transaction may be
-- attached to at most one envelope entry, ever. The same purchase counted in two envelopes would
-- overstate spending in both and understate what is left in each — and both numbers would still
-- look plausible, which is why nobody would notice. Partial, because the many typed entries all
-- have a NULL transaction_id.
CREATE UNIQUE INDEX ux_envelope_entries_transaction_id
    ON envelope_entries (transaction_id)
    WHERE transaction_id IS NOT NULL;

COMMENT ON TABLE envelope_entries IS
    'One purchase logged against an envelope. Append-mostly: correcting one keeps a trail through '
    'updated_at and version rather than silently rewriting history.';

COMMENT ON COLUMN envelope_entries.amount IS
    'POSITIVE, unlike transactions.amount. An entry records how much of a decided amount was used, '
    'which only goes one way; a sign here would make `remaining` ambiguous.';

COMMENT ON COLUMN envelope_entries.source IS
    'MANUAL means somebody typed it. SYNCED means somebody ATTACHED a real transaction — never the '
    'app matching on its own. A person decided either way (docs/features/envelopes.md).';
