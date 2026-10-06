-- V7 — Spring Session's own tables for the web transport.
--
-- Sessions live in PostgreSQL (docs/architecture/security-model.md) so they survive a restart and
-- so revocation is a DELETE rather than a hope. Spring Session ships this DDL inside its jar as
-- `org/springframework/session/jdbc/schema-postgresql.sql`; the statements below are that file
-- VERBATIM, copied from spring-session-jdbc 4.1.1 (the version Spring Boot 4.1.1 manages).
--
-- Two deliberate deviations from this repository's conventions, both because the schema is owned
-- by the library rather than by us:
--   * the naming does not follow docs/guides/database-style.md — Spring Session's SQL refers to
--     these names, so renaming them breaks it;
--   * it is created here rather than by `spring.session.jdbc.initialize-schema`, which would put
--     schema management outside Flyway and contradict ADR-0007.
--
-- If Spring Session's schema ever changes, the fix is a NEW migration. This one is frozen
-- (ADR-0007), like every other.

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
