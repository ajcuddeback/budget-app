-- V19 — file import: one row per committed import, the link from a transaction to the import that
-- wrote it, and one saved CSV column mapping per account (docs/features/file-import.md).
--
-- What this schema deliberately does NOT have, and must never grow (ADR-0037, ADR-0038):
--
--   * no table holding file content. Not the bytes, not a parsed row, not a staging copy waiting
--     for the commit. The preview is stateless and the commit re-uploads the file (ADR-0037), so
--     nothing parsed from a statement exists here until it is a transaction.
--   * no file name, no file hash, no statement balance, no account number. A statement carries the
--     full account number, the routing number, the holder's name and address and sometimes their
--     online-banking username; ADR-0028 limited what we store about an account to four digits, and
--     a statement must not widen that (ADR-0038).
--   * no cell values in a saved mapping. It holds settings and the bank's column LABELS only.
--
-- `transactions.external_id` and `ux_transactions_household_external_id` already exist (V12) and
-- were put there for this slice; nothing here changes them. Pairing two rows into a transfer
-- (rule 12) writes `transfer_group_id`, which V12 created and V15 constrains, so it needs nothing
-- here either.
--
-- No GRANT block: V10's ALTER DEFAULT PRIVILEGES already covers new tables for the application
-- role where it exists (ADR-0030). ApplicationRoleRestrictionsIT inserts into all three to prove it.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- ---------------------------------------------------------------------------------------------
-- One row per committed import.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE import_batches (
    id               uuid         PRIMARY KEY,
    household_id     uuid         NOT NULL,
    account_id       uuid         NOT NULL,
    -- Who did it, for repudiation. Users are not deleted when membership ends, so this normally
    -- survives; if a user row is deleted the history keeps the batch and forgets the person.
    imported_by      uuid,
    format           text         NOT NULL,
    imported_count   integer      NOT NULL,
    duplicate_count  integer      NOT NULL,
    excluded_count   integer      NOT NULL,
    unreadable_count integer      NOT NULL,
    zero_count       integer      NOT NULL,
    -- SHA-256 over the parsed classification (ADR-0037), NOT over the file. Unique per household,
    -- which is what makes a retried commit find the batch it already wrote instead of a second one.
    preview_digest   text         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_import_batches_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    -- Composite, so a batch can never name another household's account. CASCADE because an account
    -- can only be deleted once it has no transactions (fk_transactions_account is RESTRICT), and by
    -- then a batch is metadata about rows that no longer exist.
    CONSTRAINT fk_import_batches_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_import_batches_imported_by
        FOREIGN KEY (imported_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_import_batches_format
        CHECK (format IN ('CSV', 'OFX', 'QIF')),
    CONSTRAINT ck_import_batches_counts
        CHECK (imported_count >= 0 AND duplicate_count >= 0 AND excluded_count >= 0
               AND unreadable_count >= 0 AND zero_count >= 0),
    -- The row cap is 10,000 per file (decision 1); nothing a real import writes is above it, and a
    -- count above it means the application lost track of what it wrote.
    CONSTRAINT ck_import_batches_counts_bounded
        CHECK (imported_count + duplicate_count + excluded_count + unreadable_count + zero_count
               <= 10000),
    CONSTRAINT ck_import_batches_preview_digest
        CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_import_batches_household_digest
        UNIQUE (household_id, preview_digest),
    -- The composite FK target for transactions.import_batch_id.
    CONSTRAINT uq_import_batches_id_household
        UNIQUE (id, household_id)
);

-- The history list: one account's imports, newest first.
CREATE INDEX ix_import_batches_account_created
    ON import_batches (account_id, created_at DESC, id DESC);

CREATE INDEX ix_import_batches_imported_by
    ON import_batches (imported_by);

COMMENT ON TABLE import_batches IS
    'One committed file import. Metadata only: no file content, name, hash, statement balance or '
    'account number is ever stored (ADR-0038).';
COMMENT ON COLUMN import_batches.preview_digest IS
    'SHA-256 of the parsed classification the person was shown (ADR-0037). Not a hash of the file, '
    'and not a security boundary: it makes a retried commit idempotent.';

-- ---------------------------------------------------------------------------------------------
-- Provenance: which import wrote a transaction, and the handle for undo (decision 5).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE transactions
    ADD COLUMN import_batch_id uuid;

-- The column-list form of SET NULL (PostgreSQL 15+), as V12 does for category_id: a plain SET NULL
-- would also null household_id, which is NOT NULL, and the delete would fail.
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_import_batch
        FOREIGN KEY (import_batch_id, household_id) REFERENCES import_batches (id, household_id)
        ON DELETE SET NULL (import_batch_id);

CREATE INDEX ix_transactions_import_batch_id
    ON transactions (import_batch_id)
    WHERE import_batch_id IS NOT NULL;

COMMENT ON COLUMN transactions.import_batch_id IS
    'The import that wrote this row, or NULL for a typed one. Provenance and the handle for undo; '
    'undo removes only rows that are not transfer legs.';

-- ---------------------------------------------------------------------------------------------
-- One saved CSV column mapping per account.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE import_mappings (
    id                 uuid         PRIMARY KEY,
    household_id       uuid         NOT NULL,
    account_id         uuid         NOT NULL,
    -- Each of these is checked against the same allowlist the API uses. Changing one means
    -- changing both, in the same change, with a test.
    delimiter          text         NOT NULL,
    -- NULL: detect it (BOM, then strict UTF-8, then ask).
    charset            text,
    date_format        text         NOT NULL,
    decimal_separator  text         NOT NULL,
    -- NULL: no grouping separator.
    grouping_separator text,
    header_row         smallint     NOT NULL,
    invert_sign        boolean      NOT NULL DEFAULT false,
    -- Role -> column index, the direction values, and the header labels it was made against.
    -- Never a cell value. Validated by the application; bounded here so it cannot grow.
    columns            jsonb        NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_import_mappings_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    CONSTRAINT fk_import_mappings_account
        FOREIGN KEY (account_id, household_id) REFERENCES accounts (id, household_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_import_mappings_account
        UNIQUE (account_id),
    CONSTRAINT ck_import_mappings_delimiter
        CHECK (delimiter IN (',', ';', E'\t', '|')),
    CONSTRAINT ck_import_mappings_charset
        CHECK (charset IS NULL OR charset IN ('UTF-8', 'UTF-16LE', 'UTF-16BE', 'windows-1250',
                                              'windows-1251', 'windows-1252', 'ISO-8859-1',
                                              'ISO-8859-2', 'ISO-8859-15')),
    CONSTRAINT ck_import_mappings_date_format
        CHECK (date_format IN ('uuuu-MM-dd', 'dd/MM/uuuu', 'MM/dd/uuuu', 'd/M/uuuu', 'M/d/uuuu',
                               'dd.MM.uuuu', 'dd-MM-uuuu', 'uuuuMMdd', 'M/d/yy', 'M/d''yy',
                               'd/M/yy')),
    CONSTRAINT ck_import_mappings_decimal_separator
        CHECK (decimal_separator IN ('.', ',')),
    CONSTRAINT ck_import_mappings_grouping_separator
        CHECK (grouping_separator IS NULL
               OR grouping_separator IN (',', '.', ' ', U&'\00A0', U&'\202F', '''')),
    CONSTRAINT ck_import_mappings_separators_differ
        CHECK (grouping_separator IS NULL OR grouping_separator <> decimal_separator),
    CONSTRAINT ck_import_mappings_header_row
        CHECK (header_row BETWEEN 0 AND 20),
    CONSTRAINT ck_import_mappings_columns_size
        CHECK (octet_length(columns::text) <= 4096),
    CONSTRAINT ck_import_mappings_columns_object
        CHECK (jsonb_typeof(columns) = 'object'),
    CONSTRAINT uq_import_mappings_id_household
        UNIQUE (id, household_id)
);

CREATE INDEX ix_import_mappings_household
    ON import_mappings (household_id);

COMMENT ON TABLE import_mappings IS
    'A saved CSV column mapping for one account: settings and the bank''s header labels, never a '
    'cell value.';
