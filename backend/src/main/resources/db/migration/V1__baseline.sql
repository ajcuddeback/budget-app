-- V1 — baseline.
--
-- Deliberately almost empty: slice 1 proves the migration pipeline runs unattended against real
-- PostgreSQL before any table depends on it. Tables arrive with the features that own them.
--
-- Migrations are APPEND-ONLY. Never edit this file once it has been merged (ADR-0007) — Flyway
-- records its checksum, and changing it breaks every existing instance on next start.

-- Used for gen_random_uuid(). Available in core PostgreSQL since 13; ADR-0003 pins 16.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Records that the schema exists and which application owns it. One row, forever.
CREATE TABLE schema_metadata (
    id              smallint     PRIMARY KEY DEFAULT 1,
    application     text         NOT NULL,
    initialised_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT schema_metadata_single_row CHECK (id = 1)
);

INSERT INTO schema_metadata (application) VALUES ('budget-owl');
