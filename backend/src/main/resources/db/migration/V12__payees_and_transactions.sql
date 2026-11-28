-- V12 — payees and transactions.
--
-- A transaction is a single movement of money (docs/features/transactions.md). This is the table
-- the rest of the product is about: balances are derived from it, envelopes draw down against it,
-- bills match to it, and reporting sums it.
--
-- Three things it deliberately does NOT have:
--
--   * no `currency` — the amount is in the account's currency, and a second copy here could
--     disagree with it. That is also why V9 refuses to change an account's currency once it has
--     any transaction: the rows are denominated by the account, not by themselves.
--   * no `balance_after` — a running balance in a row is wrong the moment an earlier transaction
--     is inserted or corrected, which is the normal case, not the exception.
--   * no stored `kind` (income/expense) — the SIGN of `amount` carries that, and a category's
--     `kind` describes the category. Two sources for one fact is two sources that disagree.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- Required as a composite FK target, for the reason V11 gives on categories: it lets
-- `transactions` reference (account_id, household_id) together so that a transaction can never
-- point at another household's account. Redundant as a key; `id` is already the primary key.
ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_household UNIQUE (id, household_id);

-- ---------------------------------------------------------------------------------------------
-- Payees.
--
-- Who money went to or came from, normalised so that "STARBUCKS #1234" and "Starbucks" can
-- reconcile to one payee (docs/domain/model.md). Slice 5 does not ship renaming or merging — it
-- gets the name from the user and gets-or-creates the row — but the FK is here from the start,
-- because adding normalisation later would mean a migration that extracts names out of a text
-- column and rewrites every transaction that used one.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payees (
    id            uuid         PRIMARY KEY,
    household_id  uuid         NOT NULL,
    name          text         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_payees_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- 1–80 characters after trimming, with the raw bound too — see V9's note.
    CONSTRAINT ck_payees_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 80 AND length(name) <= 80),

    CONSTRAINT uq_payees_id_household
        UNIQUE (id, household_id)
);

-- One payee per name per household, case-insensitively. This is what makes get-or-create safe
-- under concurrency: two requests typing "Tesco" at the same moment cannot produce two rows, and
-- the loser retries the lookup instead of inserting a duplicate.
CREATE UNIQUE INDEX ux_payees_household_name
    ON payees (household_id, lower(name));

-- ---------------------------------------------------------------------------------------------
-- Transactions.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE transactions (
    id                uuid           PRIMARY KEY,
    household_id      uuid           NOT NULL,
    account_id        uuid           NOT NULL,
    -- A real date, not a timestamp. "When did this happen" is a calendar question a person answers
    -- from a receipt, and a timestamptz would make the same purchase fall in different months for
    -- two members in different zones.
    date              date           NOT NULL,
    -- ADR-0006. Signed: negative is money leaving, positive is money arriving. See V9's note on
    -- why there is no `scale(...) <= 4` CHECK on a NUMERIC(19,4) column.
    amount            numeric(19,4)  NOT NULL,
    -- Both nullable, and a NULL category is a real state, not missing data: it is the design's
    -- "Needs a category". Deleting a category nulls this rather than deleting the transaction.
    payee_id          uuid,
    category_id       uuid,
    note              text,
    -- PENDING means entered but not yet confirmed against a statement; CLEARED is confirmed;
    -- RECONCILED is part of a closed statement period and is the state reconciliation will set
    -- (slice 13). With no bank connection every manually entered transaction starts CLEARED,
    -- because the person typing it is the authority on whether it happened.
    status            text           NOT NULL DEFAULT 'CLEARED',
    -- Set by an importer (slice 12) to the source's own identifier, so re-importing a file cannot
    -- duplicate rows. NULL for everything a person types.
    external_id       text,
    -- A transfer is a linked PAIR of equal and opposite transactions sharing this id, never a
    -- single row with two account columns (docs/domain/model.md). Slice 8 writes it; the column is
    -- here now so that a transfer does not require altering this table later.
    transfer_group_id uuid,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    version           bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_transactions_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Composite on purpose: it is impossible to record a transaction against an account belonging
    -- to another household, whatever the request body says and whatever a future query forgets to
    -- filter (ADR-0017).
    --
    -- RESTRICT, not CASCADE: deleting an account that still has transactions must fail. That is
    -- exactly the rule docs/features/accounts.md states — deletion only while the account is empty
    -- — and here it becomes structural rather than a service-layer check that could be bypassed.
    CONSTRAINT fk_transactions_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE RESTRICT,

    -- Deleting a category must never delete money. `SET NULL (category_id)` names the column so
    -- that only the category is cleared: a bare ON DELETE SET NULL would try to null household_id
    -- too, which is NOT NULL, and the delete would fail instead. Needs PostgreSQL 15+.
    CONSTRAINT fk_transactions_category
        FOREIGN KEY (category_id, household_id) REFERENCES categories (id, household_id)
        ON DELETE SET NULL (category_id),

    CONSTRAINT fk_transactions_payee
        FOREIGN KEY (payee_id, household_id) REFERENCES payees (id, household_id)
        ON DELETE SET NULL (payee_id),

    -- Zero moves no money. Permitted nowhere in the product, and easier to relax later with one
    -- migration than to tighten once a thousand zero rows exist.
    CONSTRAINT ck_transactions_amount_nonzero
        CHECK (amount <> 0),

    -- A note is optional, and an empty string is not a note — it is a NULL that took a detour.
    CONSTRAINT ck_transactions_note
        CHECK (note IS NULL OR (length(btrim(note)) BETWEEN 1 AND 500 AND length(note) <= 500)),

    CONSTRAINT ck_transactions_status
        CHECK (status IN ('PENDING', 'CLEARED', 'RECONCILED')),

    CONSTRAINT ck_transactions_external_id
        CHECK (external_id IS NULL OR length(external_id) BETWEEN 1 AND 200)
);

-- The ledger: one household's transactions, newest first, with a stable tiebreak on id so paging
-- cannot repeat or skip a row. `date DESC, id DESC` matches the order the endpoint promises, so
-- the index answers the sort as well as the filter.
CREATE INDEX ix_transactions_household_date
    ON transactions (household_id, date DESC, id DESC);

-- One account's register, same ordering. Also indexes the account foreign key, which PostgreSQL
-- does not do for you — and which the RESTRICT above consults on every account deletion.
CREATE INDEX ix_transactions_account_date
    ON transactions (account_id, date DESC, id DESC);

-- Category roll-ups: one household's spending under one category, in date order.
--
-- Partial, because uncategorised rows are found through the household index and excluding them
-- keeps this small on exactly the instance where many rows have no category.
CREATE INDEX ix_transactions_household_category
    ON transactions (household_id, category_id, date DESC)
    WHERE category_id IS NOT NULL;

CREATE INDEX ix_transactions_household_payee
    ON transactions (household_id, payee_id, date DESC)
    WHERE payee_id IS NOT NULL;

-- The foreign keys' own indexes, which are NOT the same thing as the two above.
--
-- PostgreSQL consults a child index when a parent row is deleted, and it needs one whose LEADING
-- column is the referencing column. The roll-up indexes lead with `household_id` and are partial,
-- so neither can serve that scan: deleting a category would sequentially scan every transaction in
-- the instance, and deleting a category is an ordinary thing to do. `SchemaMigrationIT`
-- .everyForeignKeyIsIndexed is what caught this, and is why it exists.
CREATE INDEX ix_transactions_category_id
    ON transactions (category_id);

CREATE INDEX ix_transactions_payee_id
    ON transactions (payee_id);

-- Finds the other half of a transfer (slice 8).
CREATE INDEX ix_transactions_transfer_group
    ON transactions (transfer_group_id)
    WHERE transfer_group_id IS NOT NULL;

-- Import idempotency (slice 12): a source's identifier is unique within a household, so running
-- the same file twice cannot double its rows. Unique INDEX rather than a constraint because it is
-- partial — the many rows a person typed by hand all have a NULL external_id.
CREATE UNIQUE INDEX ux_transactions_household_external_id
    ON transactions (household_id, external_id)
    WHERE external_id IS NOT NULL;

COMMENT ON TABLE transactions IS
    'A single movement of money, in its account''s currency. Append-mostly: correcting a '
    'transaction keeps an audit trail rather than silently overwriting history '
    '(docs/domain/model.md).';

COMMENT ON COLUMN transactions.amount IS
    'Signed, in the ACCOUNT''s currency — there is deliberately no currency column here. Negative '
    'is money leaving the account, positive is money arriving.';

COMMENT ON COLUMN transactions.category_id IS
    'NULL means "Needs a category" — a real state shown in the UI, not missing data. Deleting a '
    'category nulls this column and never deletes the transaction.';

COMMENT ON COLUMN transactions.transfer_group_id IS
    'Shared by the two equal-and-opposite halves of a transfer. A transfer is never one row with '
    'two accounts, and is never counted as income or expense in reporting.';
