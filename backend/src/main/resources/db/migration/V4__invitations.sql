-- V4 — household invitations.
--
-- There is no SMTP server on a self-hosted box, so an invitation is a LINK containing a
-- single-use, high-entropy token that the owner shares however they like. The link is therefore
-- a credential: possession of it is the authorization, and the invited email is only a label.
--
-- Migrations are APPEND-ONLY (ADR-0007).

CREATE TABLE household_invitations (
    id            uuid         PRIMARY KEY,
    household_id  uuid         NOT NULL,
    email         citext       NOT NULL,
    role          varchar(16)  NOT NULL,
    -- The token itself is NEVER stored. This is the SHA-256 of the token, lowercase hex, which is
    -- the right hash for a value that is already 256 bits of randomness: an adaptive hash buys
    -- nothing against a secret nobody can guess, and lookup must be an index probe rather than a
    -- byte-by-byte comparison (threat model: timing attacks on token comparison, ruled out).
    -- The CHECK is what makes "a database dump must not yield working credentials" structural:
    -- a plaintext token cannot be written to this column at all.
    token_hash    text         NOT NULL,
    expires_at    timestamptz  NOT NULL,
    accepted_at   timestamptz,
    revoked_at    timestamptz,
    created_by    uuid         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT fk_household_invitations_household
        FOREIGN KEY (household_id) REFERENCES households (id) ON DELETE CASCADE,
    -- RESTRICT, not CASCADE: who issued an invitation is part of the audit trail, and deleting a
    -- user must not silently erase it. Self-deletion is refused while owning a household anyway.
    CONSTRAINT fk_household_invitations_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT uq_household_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_household_invitations_role CHECK (role IN ('OWNER', 'MEMBER', 'VIEWER')),
    CONSTRAINT ck_household_invitations_token_hash_sha256
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_household_invitations_email_shape
        CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    -- Single-use: an invitation that was accepted cannot later be revoked, and one that was
    -- revoked cannot later be accepted. Both states at once means a bug in the accept transaction.
    CONSTRAINT ck_household_invitations_not_both_accepted_and_revoked
        CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT ck_household_invitations_expires_after_creation
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_household_invitations_household_id_email
    ON household_invitations (household_id, email);
CREATE INDEX ix_household_invitations_created_by ON household_invitations (created_by);
-- The list-invitations screen only ever wants the live ones, and they are the sparse case once an
-- instance has been running a while.
CREATE INDEX ix_household_invitations_household_id_pending
    ON household_invitations (household_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
