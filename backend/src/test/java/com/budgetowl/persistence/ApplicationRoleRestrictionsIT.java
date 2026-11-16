package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * ADR-0030: the runtime connects as a role that <b>cannot change the schema</b>.
 *
 * <p>This is the only test in the suite that exercises that. Every other integration test connects
 * as the role that owns the tables — which is the whole problem the ADR is about, and the reason a
 * privilege model is invisible from the application's own tests. Without this class, ADR-0030 is a
 * comment in a migration.
 *
 * <p>It therefore runs its own PostgreSQL rather than sharing {@code PostgresTestBase}'s: the role
 * has to exist <b>before</b> Flyway runs, because {@code V10}'s grant block is conditional on
 * finding it, and nothing orders test classes against each other.
 *
 * <p>Everything here asserts the <b>effect</b>: the statement is executed and PostgreSQL's refusal
 * is the assertion. {@code 42501} is {@code insufficient_privilege}. Asserting on a grant table
 * would only prove that the migration said something, not that the database means it.
 *
 * <p>The context starting at all is the second thing this proves. The application's own DataSource
 * here cannot create a table, so if Spring Boot ignored {@code spring.flyway.user} and migrated
 * with it, {@code flyway_schema_history} could not be created and this class would fail before its
 * first assertion.
 */
@SpringBootTest
class ApplicationRoleRestrictionsIT {

    /** Hardcoded in {@code V10}, in the Compose init script, and here. A name, not a secret. */
    private static final String APP_ROLE = "budgetowl_app";

    /**
     * The restricted role's password in this test, fixed rather than taken from the container.
     *
     * <p>Not a secret: it exists for the few seconds this class's database does, and it matches the
     * shape of the other example passphrases in the suite.
     */
    private static final String APP_SECRET = "restricted-role-example-passphrase";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("budgetowl")
                    .withUsername("budgetowl")
                    .withPassword("test-only-not-a-secret");

    static {
        POSTGRES.start();
        createRestrictedRole();
    }

    @Autowired private DataSource dataSource;

    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * Creates the application role the way deployment does — as a plain {@code LOGIN} role with no
     * attributes and no grants — before the Spring context, and therefore before Flyway, starts.
     * {@code V10} then grants it DML, which is the code path being tested.
     *
     * <p>It reuses the owner's login credential because this test is about privileges and a second
     * literal would only be a second thing to explain. A deployment must <b>not</b> do that: the
     * point of the split is that the credential sitting in the application container is not the
     * owner's.
     */
    private static void createRestrictedRole() {
        try (Connection owner =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement statement = owner.createStatement()) {
            // Both halves are compile-time constants, so there is no value here for anything to
            // inject through. It used to interpolate the container's generated password, which
            // CodeQL flagged as query-building from a possibly-untrusted string — correctly as a
            // pattern, even though the value came from Testcontainers. A literal is simpler than
            // arguing about it, and PostgreSQL will not take CREATE ROLE's password as a bind
            // parameter, so escaping-and-concatenating was the only other option.
            statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_SECRET + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the restricted role", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // The application pool: the restricted role.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_SECRET);
        // Flyway: the owner. This is the production arrangement, exactly as application.yml
        // resolves it from DB_USER/DB_PASSWORD.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    // ------------------------------------------------------------------ the arrangement itself

    @Test
    void theApplicationPoolIsNotTheRoleThatOwnsTheTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tableowner FROM pg_tables WHERE tablename = 'accounts'",
                                String.class))
                .isEqualTo(POSTGRES.getUsername());
    }

    @Test
    void flywayRanAsTheOwnerWithItsOwnCredentials() {
        // Spring Boot 4.1.1's FlywayAutoConfiguration#getMigrationDataSource derives a DataSource
        // from the primary one and overrides the credentials when `spring.flyway.user` is set and
        // `spring.flyway.url` is not. Verified in the bytecode, and then verified here: the
        // migrations are applied and owned by the owner, while the pool is the restricted role,
        // which could not have created a single one of these objects.
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

        assertThat(
                        owner.queryForObject(
                                """
                                SELECT count(*) FROM flyway_schema_history
                                 WHERE script = 'V10__application_role_grants.sql' AND success
                                """,
                                Integer.class))
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------- what it cannot do

    @Test
    void cannotTruncateATable() {
        // TRUNCATE is the dangerous one: it empties a table without firing row triggers, so the
        // owner-count sync and the last-owner rule never see it happen.
        assertThatThrownBy(() -> execute("TRUNCATE accounts"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    @Test
    void cannotDropAConstraint() {
        // Every CHECK in V8 and V9 depends on this refusal. Dropping one requires ownership of the
        // table, which this role does not have — and cannot grant itself.
        assertThatThrownBy(
                        () ->
                                execute(
                                        "ALTER TABLE accounts DROP CONSTRAINT"
                                                + " ck_accounts_last_four"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));

        // And it is still there afterwards, which is the assertion that would catch a refusal that
        // was reported but not enforced.
        assertThat(
                        new JdbcTemplate(dataSource)
                                .queryForObject(
                                        """
                                        SELECT count(*) FROM pg_constraint
                                         WHERE conname = 'ck_accounts_last_four'
                                        """,
                                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cannotDisableATrigger() {
        // ALTER TABLE ... DISABLE TRIGGER is the other way past the last-owner rule.
        assertThatThrownBy(() -> execute("ALTER TABLE household_members DISABLE TRIGGER ALL"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    @Test
    void cannotCreateATable() {
        // No DDL at all: a role that can create a table can create one that shadows another in its
        // own search_path, and it is one GRANT away from the rest.
        assertThatThrownBy(() -> execute("CREATE TABLE smuggled (id uuid PRIMARY KEY)"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    @Test
    void cannotSwitchOffTriggersForItsOwnSession() {
        // `SET session_replication_role = replica` skips every non-replica trigger in the session,
        // including the deferred last-owner constraint trigger. It is superuser-only, and the
        // restricted role is not one — assert it rather than trust the ADR's table.
        assertThatThrownBy(() -> execute("SET session_replication_role = replica"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    @Test
    void cannotRewriteTheSchemasOwnHistory() {
        // V10 takes flyway_schema_history back off the application role. `validate-on-migrate` is
        // worth something only if the process being validated cannot edit the record it is
        // validated against. 42501 again: the table exists, the privilege does not.
        assertThatThrownBy(() -> execute("DELETE FROM flyway_schema_history"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }

    // ----------------------------------------------------------------------------- what it can do

    @Test
    void canReadAndWriteRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID user = UUID.randomUUID();
        UUID household = UUID.randomUUID();
        UUID account = UUID.randomUUID();

        // The household chain is not scenery: inserting a membership fires
        // sync_household_owner_count(), an invoker-rights trigger function that UPDATEs
        // `households`. If the grants in V10 were short of what the application needs, this is
        // where it would show — a restriction that breaks ordinary writes is not a restriction,
        // it is an outage.
        assertThatCode(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.update(
                                                    """
                                                    INSERT INTO users (id, email, display_name)
                                                    VALUES (?, ?, ?)
                                                    """,
                                                    user,
                                                    "ada@example.com",
                                                    "Ada");
                                            jdbc.update(
                                                    """
                                                    INSERT INTO households (id, name, base_currency)
                                                    VALUES (?, ?, ?)
                                                    """,
                                                    household,
                                                    "Home",
                                                    "GBP");
                                            jdbc.update(
                                                    """
                                                    INSERT INTO household_members
                                                        (id, household_id, user_id, role)
                                                    VALUES (?, ?, ?, 'OWNER')
                                                    """,
                                                    UUID.randomUUID(),
                                                    household,
                                                    user);
                                            jdbc.update(
                                                    """
                                                    INSERT INTO accounts (id, household_id, name,
                                                        type, currency, opening_balance)
                                                    VALUES (?, ?, 'Current', 'CHECKING', 'GBP',
                                                        100.0000)
                                                    """,
                                                    account,
                                                    household);
                                        }))
                .doesNotThrowAnyException();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM accounts WHERE id = ?", String.class, account))
                .isEqualTo("Current");
        assertThat(jdbc.update("UPDATE accounts SET name = 'Joint' WHERE id = ?", account))
                .isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM accounts WHERE id = ?", account)).isEqualTo(1);

        // Leave the database as it was found: this class shares one container across its methods.
        transaction.executeWithoutResult(
                status -> {
                    jdbc.update("DELETE FROM household_members WHERE household_id = ?", household);
                    jdbc.update("DELETE FROM households WHERE id = ?", household);
                    jdbc.update("DELETE FROM users WHERE id = ?", user);
                });
    }

    @Test
    void andTheConstraintsItCannotDropStillRefuseBadData() {
        // The point of the whole arrangement, in one assertion: a CHECK the connection cannot drop
        // is a CHECK that holds. Five digits in `last_four` would be a full-ish account number
        // (ADR-0028).
        assertThatThrownBy(
                        () ->
                                new JdbcTemplate(dataSource)
                                        .update(
                                                """
                                                INSERT INTO accounts (id, household_id, name, type,
                                                    currency, opening_balance, last_four)
                                                VALUES (?, ?, 'Current', 'CHECKING', 'GBP', 0,
                                                    '98765')
                                                """,
                                                UUID.randomUUID(),
                                                UUID.randomUUID()))
                .hasMessageContaining("ck_accounts_last_four");
    }

    // --------------------------------------------------------------------------------- plumbing

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private DataSource ownerDataSource() {
        DriverManagerDataSource owner =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner.setDriverClassName("org.postgresql.Driver");
        return owner;
    }

    private static String sqlState(Throwable e) {
        return e instanceof SQLException sql ? sql.getSQLState() : null;
    }
}
