package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every migration applies cleanly to an empty database, and the JPA mappings validate against what
 * they produce.
 *
 * <p>The validation half needs no assertion of its own: {@code ddl-auto: validate} runs at startup
 * in every environment (ADR-0007), so an entity that disagrees with the schema fails this context
 * before a single test method runs. That is the intended behaviour in production too — the app
 * refuses to start rather than writing into a column that is not what it thinks it is.
 *
 * <p>Against real PostgreSQL 16 via Testcontainers, never H2 (ADR-0009): these migrations use
 * {@code citext}, expression indexes, partial indexes and a deferrable constraint trigger, none of
 * which H2 would have told the truth about.
 */
@SpringBootTest
class SchemaMigrationIT extends PersistenceTestBase {

    @Test
    void everyMigrationAppliedAndSucceeded() {
        List<String> applied =
                jdbc.queryForList(
                        "SELECT script FROM flyway_schema_history ORDER BY installed_rank",
                        String.class);

        assertThat(applied)
                .containsExactly(
                        "V1__baseline.sql",
                        "V2__users.sql",
                        "V3__households.sql",
                        "V4__invitations.sql",
                        "V5__auth_tokens.sql",
                        "V6__instance_settings.sql",
                        "V7__spring_session.sql");

        Integer failures =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE NOT success",
                        Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    void everyTableTheFeatureNeedsExists() {
        assertThat(tableNames())
                .contains(
                        "users",
                        "households",
                        "household_members",
                        "household_invitations",
                        "auth_tokens",
                        "instance_settings",
                        "spring_session",
                        "spring_session_attributes");
    }

    @Test
    void instanceSettingsHasItsSingleRowFromFirstRun() {
        // ADR-0016: setup must complete with nothing configured beyond a database password, so the
        // row cannot be something the application has to remember to create.
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM instance_settings", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void noColumnAnywhereUsesFloatingPoint() {
        // ADR-0006, checked against the database rather than the entities. ArchUnit stops a float
        // reaching a Java field; this stops one reaching a column, which is the half that would
        // survive a rewrite of the Java.
        List<String> floatingPoint =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND data_type IN ('real', 'double precision')
                        """,
                        String.class);

        assertThat(floatingPoint).isEmpty();
    }

    @Test
    void everyTimestampColumnCarriesItsTimeZone() {
        // A `timestamp without time zone` is a bug waiting for a daylight-saving boundary
        // (docs/guides/database-style.md). Spring Session's own tables use BIGINT epochs and are
        // its business, not ours.
        List<String> naive =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND data_type = 'timestamp without time zone'
                           AND table_name NOT LIKE 'spring_session%'
                           AND table_name <> 'flyway_schema_history'
                        """,
                        String.class);

        assertThat(naive).isEmpty();
    }

    @Test
    void everyPrimaryKeyOfAFeatureTableIsAUuid() {
        // Sequential ids leak volume and let anyone enumerate rows by incrementing a URL
        // (docs/guides/database-style.md). `instance_settings` is the documented exception: it is
        // a single row whose id is literally 1.
        List<String> nonUuid =
                jdbc.queryForList(
                        """
                        SELECT c.table_name || '.' || c.column_name
                          FROM information_schema.table_constraints tc
                          JOIN information_schema.key_column_usage k
                            ON k.constraint_name = tc.constraint_name
                          JOIN information_schema.columns c
                            ON c.table_name = k.table_name AND c.column_name = k.column_name
                         WHERE tc.constraint_type = 'PRIMARY KEY'
                           AND tc.table_schema = 'public'
                           AND c.table_name IN ('users', 'households', 'household_members',
                                                'household_invitations', 'auth_tokens')
                           AND c.udt_name <> 'uuid'
                        """,
                        String.class);

        assertThat(nonUuid).isEmpty();
    }

    @Test
    void everyForeignKeyIsIndexed() {
        // PostgreSQL does not index foreign keys for you, and an unindexed one turns a parent
        // delete into a sequential scan of the child table (docs/guides/database-style.md).
        List<String> unindexed =
                jdbc.queryForList(
                        """
                        SELECT con.conrelid::regclass::text || '.' || att.attname
                          FROM pg_constraint con
                          JOIN pg_attribute att
                            ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
                         WHERE con.contype = 'f'
                           AND connamespace = 'public'::regnamespace
                           AND NOT EXISTS (
                               SELECT 1 FROM pg_index i
                                WHERE i.indrelid = con.conrelid
                                  AND i.indkey[0] = con.conkey[1])
                        """,
                        String.class);

        assertThat(unindexed).isEmpty();
    }

    private List<String> tableNames() {
        return jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """,
                String.class);
    }
}
