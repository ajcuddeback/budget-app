-- V9 — accounts.
--
-- An account is a place money sits (docs/features/accounts.md). It is the first FINANCIAL table in
-- the schema, so it is the first one to carry a direct `household_id`: ADR-0017's ownership
-- predicate has to be one condition on the row itself, never "reachable via a join", or every
-- later query has to be trusted to join correctly.
--
-- Balance is NOT here. It is derived — `opening_balance` plus the sum of the account's
-- transactions (docs/domain/model.md) — and a stored copy is a column that drifts out of sync
-- with the rows it summarises. If that ever becomes a performance problem it is a materialised
-- projection with a rebuild path, and it needs an ADR.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

CREATE TABLE accounts (
    id               uuid           PRIMARY KEY,
    household_id     uuid           NOT NULL,
    name             text           NOT NULL,
    -- Optional, and an account with no digits is entirely ordinary — cash in a tin has no number.
    last_four        text,
    -- A text column with a CHECK rather than a PostgreSQL enum, deliberately: adding a sixth
    -- account type is then an ordinary migration (`DROP`/`ADD CONSTRAINT`) instead of an enum
    -- rewrite, and the set stays readable in a dump. docs/features/accounts.md says so.
    type             text           NOT NULL,
    currency         text           NOT NULL,
    -- ADR-0006. NUMERIC(19,4), signed: a credit card you owe 400 on opens at -400.0000, and
    -- coercing a sign from the type would be the app guessing at the user's own figure.
    -- No `scale(...) <= 4` CHECK, unlike docs/guides/database-style.md's transactions example:
    -- the typmod on NUMERIC(19,4) already fixes the scale, and the cast to the column type
    -- happens BEFORE constraints are evaluated, so such a CHECK could never fail and would read
    -- like protection while providing none. The scale check belongs on a bare `numeric`.
    opening_balance  numeric(19,4)  NOT NULL,
    -- Archiving is the normal way to retire an account: it keeps its history and disappears from
    -- pickers and the default list. Deletion is only permitted while the account is empty.
    archived         boolean        NOT NULL DEFAULT false,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    -- Optimistic locking. Two people editing one account is a normal household, and the loser
    -- must be told rather than silently overwriting the winner.
    version          bigint         NOT NULL DEFAULT 0,

    -- Deleting a household removes its financial data; no orphaned rows
    -- (docs/guides/database-style.md). An account cannot outlive the household that owns it.
    CONSTRAINT fk_accounts_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- 1–80 characters after trimming. The second half of the predicate is not redundant:
    -- `length(btrim(name)) <= 80` on its own permits a gigabyte of padding around one character,
    -- and `text` in PostgreSQL has no ceiling of its own. V8 section 4 added exactly this bound
    -- to every other user-supplied text column for exactly that reason, and the application
    -- stores the trimmed value, so raw and trimmed lengths agree on every row it writes.
    CONSTRAINT ck_accounts_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 80 AND length(name) <= 80),

    -- ADR-0028. Exactly four ASCII digits or nothing at all. `[0-9]` rather than `\d` or
    -- `[[:digit:]]` on purpose — the character range cannot widen with a locale, so Arabic-Indic
    -- digits (١٢٣٤) are rejected like any other non-ASCII input. This is the constraint that makes
    -- "the column cannot hold a full account number" structural rather than a promise: five digits
    -- fail here even if the edge and every future import path forget to check.
    CONSTRAINT ck_accounts_last_four
        CHECK (last_four IS NULL OR last_four ~ '^[0-9]{4}$'),

    -- A closed set. An unknown type is a 400 at the edge and impossible in the table.
    CONSTRAINT ck_accounts_type
        CHECK (type IN ('CHECKING', 'SAVINGS', 'CREDIT_CARD', 'CASH', 'INVESTMENT')),

    -- Three uppercase letters, like households.base_currency. Uppercase is checked rather than
    -- only the length: 'gbp' would otherwise sit alongside 'GBP' and nothing would ever reconcile
    -- the two. Which codes actually exist is the application's job (ISO-4217); which SHAPES exist
    -- is the database's.
    CONSTRAINT ck_accounts_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

-- Serves the default listing — one household's unarchived accounts, ordered by name
-- case-insensitively with a stable tiebreak on id so paging cannot repeat or skip a row — without
-- a sort step. `lower(name)` because the sort is case-insensitive, and `id` as the fourth column
-- because the tiebreak is part of the order the feature promises: without it the index answers the
-- filter and PostgreSQL still sorts. It also indexes the foreign key, which PostgreSQL does not
-- do for you.
CREATE INDEX ix_accounts_household_id_archived_name
    ON accounts (household_id, archived, lower(name), id);

COMMENT ON COLUMN accounts.last_four IS
    'A partial financial identifier (ADR-0028). Never logged, never placed in an error message or '
    'a problem-detail body, and never put in a URL or query string where a proxy or access log '
    'would capture it.';

COMMENT ON COLUMN accounts.opening_balance IS
    'What the account held when the household started tracking it, in the account''s own currency. '
    'Signed. The balance is derived from this plus the account''s transactions and is never stored.';
