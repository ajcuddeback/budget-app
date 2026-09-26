-- V6 — instance settings: one row, forever.
--
-- Everything here is configured AT RUNTIME by the instance administrator in the app, not by
-- environment variable (docs/architecture/security-model.md). First run must work with nothing
-- set beyond a database password (ADR-0016), so every column has a safe default and the row is
-- inserted by this migration rather than by the application.
--
-- Migrations are APPEND-ONLY (ADR-0007).

CREATE TABLE instance_settings (
    id                         smallint     PRIMARY KEY DEFAULT 1,

    -- Off by default. The instance may be internet-facing, and open registration would mean
    -- anyone who finds it can create an account on somebody's private finances.
    registration_open          boolean      NOT NULL DEFAULT false,

    -- Email + password is a permanent capability (ADR-0018); this only hides it on an instance
    -- where OIDC is proven working. See ck_instance_settings_password_login_lockout below.
    password_login_enabled     boolean      NOT NULL DEFAULT true,

    oidc_enabled               boolean      NOT NULL DEFAULT false,
    oidc_issuer_uri            text,
    oidc_client_id             text,
    oidc_client_secret         text,
    -- An OIDC login may provision a USER; it never grants household membership. Off by default,
    -- because otherwise the provider's whole directory lands inside someone's finances.
    oidc_provisioning_enabled  boolean      NOT NULL DEFAULT false,
    -- Set the first time an OWNER completes a successful OIDC login. This is the evidence that
    -- turning password login off will not lock everyone out of their own server.
    oidc_owner_login_at        timestamptz,

    -- Claimed exactly once, by the winner of POST /api/setup/first-user. A conditional UPDATE
    -- ("... WHERE setup_completed_at IS NULL") on this single row is the database-level means the
    -- threat model asks for: the second concurrent caller blocks on the row lock, then matches
    -- zero rows once the first commits, so it cannot also become administrator.
    setup_completed_at         timestamptz,

    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_instance_settings_single_row CHECK (id = 1),

    -- "Password login cannot be disabled until at least one OWNER has completed a successful
    -- OIDC login" (security-model.md). A mechanism rather than a warning, and the database is
    -- where a mechanism survives a service-layer bug.
    CONSTRAINT ck_instance_settings_password_login_lockout
        CHECK (password_login_enabled OR oidc_owner_login_at IS NOT NULL),

    -- An OIDC configuration that is switched on but incomplete is a broken login route.
    CONSTRAINT ck_instance_settings_oidc_configured
        CHECK (NOT oidc_enabled
               OR (oidc_issuer_uri IS NOT NULL AND btrim(oidc_issuer_uri) <> ''
                   AND oidc_client_id IS NOT NULL AND btrim(oidc_client_id) <> '')),

    -- Provisioning users from a provider that is switched off is meaningless, and a setting that
    -- means nothing is a setting somebody will misread.
    CONSTRAINT ck_instance_settings_provisioning_requires_oidc
        CHECK (oidc_enabled OR NOT oidc_provisioning_enabled)
);

INSERT INTO instance_settings (id) VALUES (1);
