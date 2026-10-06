package com.budgetowl.persistence;

import com.budgetowl.smoke.PostgresTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared setup for the database-layer integration tests: the one Testcontainers PostgreSQL from
 * {@link PostgresTestBase} (ADR-0009, never H2), plus an empty schema before each test.
 *
 * <p>These tests are deliberately <b>not</b> {@code @Transactional}. Half of what is being proved
 * here happens at {@code COMMIT} — the last-owner rule is a deferred constraint trigger — and a
 * test that rolls back never reaches it.
 */
public abstract class PersistenceTestBase extends PostgresTestBase {

    @Autowired protected DataSource dataSource;

    @Autowired protected PlatformTransactionManager transactionManager;

    protected JdbcTemplate jdbc;

    protected TransactionTemplate transaction;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        // TRUNCATE rather than DELETE: it does not fire row triggers, so emptying the tables
        // cannot trip the last-owner rule on the way.
        jdbc.execute(
                """
                TRUNCATE household_invitations, auth_tokens, household_members, households, users
                RESTART IDENTITY CASCADE
                """);
        jdbc.update(
                """
                UPDATE instance_settings
                   SET setup_completed_at = NULL,
                       registration_open = false,
                       password_login_enabled = true,
                       oidc_enabled = false,
                       oidc_provisioning_enabled = false,
                       oidc_issuer_uri = NULL,
                       oidc_client_id = NULL,
                       oidc_client_secret = NULL,
                       oidc_owner_login_at = NULL
                 WHERE id = 1
                """);
    }
}
