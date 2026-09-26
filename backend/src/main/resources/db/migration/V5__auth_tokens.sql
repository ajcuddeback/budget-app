-- V5 — opaque bearer tokens for the mobile transport (ADR-0018).
--
-- Deliberately opaque and stored server-side rather than self-contained, so revoking a stolen
-- phone's access is a DELETE that takes effect on the next request.
--
-- Migrations are APPEND-ONLY (ADR-0007).

CREATE TABLE auth_tokens (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL,
    -- SHA-256 of the token, lowercase hex. The token itself is shown to its owner once and never
    -- stored; see the note in V4 on why a fast hash is the correct one here. The CHECK makes a
    -- plaintext bearer token unwritable.
    token_hash    text         NOT NULL,
    device_label  text         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    expires_at    timestamptz  NOT NULL,
    revoked_at    timestamptz,
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT fk_auth_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT uq_auth_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_tokens_token_hash_sha256 CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_tokens_device_label_present CHECK (btrim(device_label) <> ''),
    CONSTRAINT ck_auth_tokens_expires_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_tokens_user_id ON auth_tokens (user_id);
-- The devices screen and "revoke everything for this user" both want only live tokens, and a
-- long-running instance accumulates dead ones.
CREATE INDEX ix_auth_tokens_user_id_live
    ON auth_tokens (user_id)
    WHERE revoked_at IS NULL;
