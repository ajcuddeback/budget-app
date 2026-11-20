-- V11 — categories.
--
-- A category classifies a transaction (docs/features/categories.md). Like `accounts` it carries a
-- direct `household_id` rather than reaching one through a join: ADR-0017's ownership predicate
-- has to be one condition on the row itself.
--
-- The hierarchy is TWO LEVELS, no deeper (docs/domain/model.md). That is not expressible as a
-- CHECK, because a CHECK may only see the row in front of it and the rule is about the row's
-- parent, so it is a constraint trigger — the same mechanism V3 uses for the last-owner rule. The
-- alternative, trusting the application, is the thing the database is here to stop.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

CREATE TABLE categories (
    id            uuid         PRIMARY KEY,
    household_id  uuid         NOT NULL,
    name          text         NOT NULL,
    -- INCOME or EXPENSE. A text column with a CHECK rather than a PostgreSQL enum, for the reason
    -- V9 gives: a third kind is then an ordinary migration instead of an enum rewrite.
    kind          text         NOT NULL,
    -- NULL for a top-level category. A child's parent must itself be top-level, and must share the
    -- child's kind — both enforced by the trigger below.
    parent_id     uuid,
    -- Archiving keeps a category out of pickers while leaving history readable. Deleting one is
    -- also allowed and nulls its transactions' category_id (V12) rather than deleting them.
    archived      boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0,

    CONSTRAINT fk_categories_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,

    -- Deleting a parent deletes its children. A child category whose parent is gone has no
    -- meaning, and the transactions underneath are protected separately: V12's FK nulls their
    -- category_id, so no transaction is ever removed by this cascade.
    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_id) REFERENCES categories (id) ON DELETE CASCADE,

    -- 1–60 characters after trimming, with the raw bound too — see V9's note on why the second
    -- half is not redundant.
    CONSTRAINT ck_categories_name_length
        CHECK (length(btrim(name)) BETWEEN 1 AND 60 AND length(name) <= 60),

    CONSTRAINT ck_categories_kind
        CHECK (kind IN ('INCOME', 'EXPENSE')),

    -- A category cannot be its own parent. Longer cycles are impossible once the depth rule holds,
    -- but this one is free and catches the obvious case without consulting another row.
    CONSTRAINT ck_categories_not_own_parent
        CHECK (parent_id IS NULL OR parent_id <> id),

    -- Redundant as a key — `id` is already unique — and present solely as a composite FK target.
    -- V12's `transactions` references (category_id, household_id) together, which makes
    -- "a transaction's category belongs to the transaction's household" a structural fact the
    -- database checks rather than something every query has to be trusted to have got right
    -- (ADR-0017). A trigger could enforce the same thing; a foreign key cannot be forgotten.
    CONSTRAINT uq_categories_id_household
        UNIQUE (id, household_id)
);

-- Names are unique per household per level, case-insensitively: two "Groceries" at the top level
-- are a data-entry mistake, but "Bills → Phone" and "Transport → Phone" are both reasonable, so
-- the parent is part of the key. COALESCE to the nil UUID because NULL never equals NULL in a
-- unique index, which would otherwise let duplicate top-level names through.
CREATE UNIQUE INDEX ux_categories_household_parent_name
    ON categories (
        household_id,
        COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(name)
    );

-- Serves the tree listing: one household's categories, parents before children, ordered
-- case-insensitively with a stable tiebreak on id. Also indexes the household foreign key, which
-- PostgreSQL does not do for you.
CREATE INDEX ix_categories_household_kind_name
    ON categories (household_id, kind, lower(name), id);

-- Indexes the parent foreign key so deleting a parent does not scan the table to find its
-- children, and so loading a parent's children is a lookup.
CREATE INDEX ix_categories_parent_id
    ON categories (parent_id);

-- The depth and kind rules.
--
-- A CONSTRAINT TRIGGER rather than a plain one, and DEFERRABLE INITIALLY IMMEDIATE: the rule is
-- about a single row's relationship to its parent, so it can be judged as each row lands, but
-- making it deferrable leaves the option of a transaction that reparents a subtree in several
-- statements without tripping over itself mid-way.
CREATE FUNCTION categories_enforce_two_levels() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_parent_id uuid;
    parent_kind      text;
    parent_household uuid;
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        SELECT parent_id, kind, household_id
          INTO parent_parent_id, parent_kind, parent_household
          FROM categories
         WHERE id = NEW.parent_id;

        -- The FK guarantees the parent exists by the end of the transaction, not necessarily now.
        IF NOT FOUND THEN
            RAISE EXCEPTION 'category parent % does not exist', NEW.parent_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;

        -- Two levels. A child of a child would be a third.
        IF parent_parent_id IS NOT NULL THEN
            RAISE EXCEPTION
                'category hierarchy is limited to two levels: % cannot be a child of %',
                NEW.id, NEW.parent_id
                USING ERRCODE = 'check_violation';
        END IF;

        -- An EXPENSE child of an INCOME parent would make every roll-up ambiguous.
        IF parent_kind <> NEW.kind THEN
            RAISE EXCEPTION 'category kind % does not match parent kind %', NEW.kind, parent_kind
                USING ERRCODE = 'check_violation';
        END IF;

        -- Scoping, in the database as well as in the repository (ADR-0017). Without this a
        -- household could nest its category under another household's and leak its name.
        IF parent_household <> NEW.household_id THEN
            RAISE EXCEPTION 'category parent belongs to a different household'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    -- The other direction: a category that already has children may not acquire a parent, because
    -- that would push its children to depth three.
    IF NEW.parent_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM categories WHERE parent_id = NEW.id) THEN
        RAISE EXCEPTION
            'category % has children, so it cannot itself become a child', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_categories_two_levels
    AFTER INSERT OR UPDATE OF parent_id, kind, household_id ON categories
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION categories_enforce_two_levels();

COMMENT ON TABLE categories IS
    'Two-level classification for transactions. Depth, kind agreement with the parent and '
    'household agreement with the parent are enforced by trg_categories_two_levels, not by the '
    'application alone.';

COMMENT ON COLUMN categories.parent_id IS
    'NULL for a top-level category. A child''s parent is always top-level: the hierarchy is two '
    'levels deep (docs/domain/model.md).';

-- ---------------------------------------------------------------------------------------------
-- A starting set of categories for households that already exist.
--
-- New households get these from the application when they are created, because that is where the
-- household''s own language and locale are known. This block is for the instance that is already
-- running: without it, an existing household upgrades into a categories feature with an empty
-- list and no obvious way to tell that it is supposed to have one.
--
-- Idempotent by construction — it inserts only for households that have no categories at all — so
-- it stays correct if it is ever re-run against a restored database.
-- ---------------------------------------------------------------------------------------------

WITH households_without_categories AS (
    SELECT h.id AS household_id
      FROM households h
     WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.household_id = h.id)
),
parents (name, kind, sort) AS (
    VALUES
        ('Income',        'INCOME',  1),
        ('Bills',         'EXPENSE', 2),
        ('Groceries',     'EXPENSE', 3),
        ('Transport',     'EXPENSE', 4),
        ('Eating Out',    'EXPENSE', 5),
        ('Shopping',      'EXPENSE', 6),
        ('Health',        'EXPENSE', 7),
        ('Entertainment', 'EXPENSE', 8),
        ('Home',          'EXPENSE', 9),
        ('Other',         'EXPENSE', 10)
),
inserted_parents AS (
    INSERT INTO categories (id, household_id, name, kind, parent_id)
    SELECT gen_random_uuid(), hwc.household_id, p.name, p.kind, NULL
      FROM households_without_categories hwc
     CROSS JOIN parents p
    RETURNING id, household_id, name, kind
),
children (parent_name, name) AS (
    VALUES
        ('Income',    'Salary'),
        ('Income',    'Other income'),
        ('Bills',     'Utilities'),
        ('Bills',     'Rent or mortgage'),
        ('Bills',     'Phone and internet'),
        ('Bills',     'Insurance'),
        ('Bills',     'Subscriptions'),
        ('Transport', 'Fuel'),
        ('Transport', 'Transit'),
        ('Transport', 'Car'),
        ('Health',    'Pharmacy'),
        ('Health',    'Medical')
)
INSERT INTO categories (id, household_id, name, kind, parent_id)
SELECT gen_random_uuid(), ip.household_id, ch.name, ip.kind, ip.id
  FROM inserted_parents ip
  JOIN children ch ON ch.parent_name = ip.name;
