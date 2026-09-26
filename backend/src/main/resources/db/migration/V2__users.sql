-- V2 — users.
--
-- A login is a *user* (docs/memory/glossary.md); "account" in this product is a container where
-- money sits. A user may exist without a household membership (OIDC-provisioned, not yet
-- invited), so this table knows nothing about households.
--
-- Migrations are APPEND-ONLY (ADR-0007). Fix a mistake here with a new migration, never an edit.

-- Case-insensitive text, for email. Addresses differ in case but are the same address, and
-- "Alice@example.com" must not be able to register alongside "alice@example.com".
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                 uuid         PRIMARY KEY,
    email              citext       NOT NULL,
    display_name       text         NOT NULL,
    -- Nullable on purpose: an OIDC-provisioned user has no password at all, and NULL is the
    -- honest representation of that. It is never the empty string and never a placeholder.
    password_hash      text,
    status             varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    is_instance_admin  boolean      NOT NULL DEFAULT false,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_email_shape CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_users_display_name_present CHECK (btrim(display_name) <> ''),

    -- The backstop against the worst possible defect in this table: a plaintext password.
    -- `DelegatingPasswordEncoder` (security-model.md) emits "{bcrypt}$2a$12$..."; a bare
    -- BCrypt/Argon2 encoder emits "$2a$..." / "$argon2id$...". Both are accepted. A human-typed
    -- password is neither, and is rejected by the database regardless of what the app believes.
    CONSTRAINT ck_users_password_hash_encoded
        CHECK (password_hash IS NULL
               OR (length(password_hash) >= 20 AND password_hash ~ '^(\{[a-z0-9]+\}|\$)'))
);

-- ADR-0026: one household per instance, and its OWNER is the operator. There is exactly one
-- instance administrator, so make "two simultaneous callers of /api/setup/first-user both become
-- administrator" impossible in the schema rather than in a service that races. A unique index on
-- a constant expression, restricted to admin rows, permits at most one such row for all time.
CREATE UNIQUE INDEX uq_users_single_instance_admin
    ON users ((true))
    WHERE is_instance_admin;

COMMENT ON COLUMN users.password_hash IS
    'Never selected into a DTO. Mapped by the PasswordCredential entity only, which exposes no '
    'accessor for it (docs/features/authentication-and-households.md).';
