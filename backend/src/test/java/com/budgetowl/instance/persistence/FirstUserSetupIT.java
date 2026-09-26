package com.budgetowl.instance.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.persistence.PersistenceTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two simultaneous callers of {@code POST /api/setup/first-user} cannot both become instance
 * administrator.
 *
 * <p>From the threat model: <i>"An attacker finds an internet-facing fresh-looking instance and
 * POSTs /api/setup/first-user to become its administrator → the endpoint is refused the moment any
 * user exists, checked in the same transaction as the insert so two simultaneous callers cannot
 * both win."</i>
 *
 * <p>"Count the users, and insert if there are none" cannot deliver that, however carefully it is
 * written: between the count and the insert, the other caller does the same. So the database
 * provides the means and this proves they work — a conditional UPDATE that claims a single row,
 * backed by two unique indexes that make a second administrator or a second household impossible
 * even if a future caller forgets the claim.
 */
@SpringBootTest
class FirstUserSetupIT extends PersistenceTestBase {

    @Autowired private InstanceSettingsRepository settings;

    @Test
    void aFreshInstanceHasNotCompletedSetup() {
        assertThat(settings.findCurrent())
                .hasValueSatisfying(current -> assertThat(current.isSetupComplete()).isFalse());
    }

    @Test
    void theClaimSucceedsOnceAndNeverAgain() {
        Integer first = transaction.execute(status -> settings.claimFirstUserSetup(Instant.now()));
        Integer second = transaction.execute(status -> settings.claimFirstUserSetup(Instant.now()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(settings.findCurrent())
                .hasValueSatisfying(
                        current -> {
                            assertThat(current.isSetupComplete()).isTrue();
                            assertThat(current.setupCompletedAt()).isNotNull();
                        });
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousSetupClaimsWins() throws Exception {
        List<Integer> claimed = runSimultaneously(this::claimSetup, this::claimSetup);

        assertThat(claimed).containsExactlyInAnyOrder(1, 0);
        assertThat(setupCompletedAt()).isNotNull();
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousFirstUsersBecomesAdministrator() throws Exception {
        // The whole endpoint, not just its guard: claim, create the user, create the household,
        // make them its OWNER.
        List<Integer> created =
                runSimultaneously(
                        connection -> completeSetup(connection, "first@example.com"),
                        connection -> completeSetup(connection, "second@example.com"));

        assertThat(created).containsExactlyInAnyOrder(1, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE is_instance_admin", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void aSecondInstanceAdministratorCannotExistEvenWithoutTheClaim() {
        // The backstop, for the day somebody adds a second way to create a user. ADR-0026: the
        // household's OWNER is the operator, and there is one of them.
        jdbc.update(
                "INSERT INTO users (id, email, display_name, is_instance_admin) VALUES (?, ?, ?, true)",
                UUID.randomUUID(),
                "operator@example.com",
                "Operator");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO users (id, email, display_name, is_instance_admin)
                                        VALUES (?, ?, ?, true)
                                        """,
                                        UUID.randomUUID(),
                                        "usurper@example.com",
                                        "Usurper"))
                .hasMessageContaining("uq_users_single_instance_admin");
    }

    @Test
    void aSecondHouseholdCannotExist() {
        // ADR-0026 in the schema. There is no UI, API or migration path for a second household,
        // and now no psql session for one either.
        transaction.executeWithoutResult(
                status -> {
                    UUID user = UUID.randomUUID();
                    UUID household = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                            user,
                            "operator@example.com",
                            "Operator");
                    jdbc.update(
                            "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                            household,
                            "Home",
                            "GBP");
                    jdbc.update(
                            """
                            INSERT INTO household_members (id, household_id, user_id, role)
                            VALUES (?, ?, ?, 'OWNER')
                            """,
                            UUID.randomUUID(),
                            household,
                            user);
                });

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                                        UUID.randomUUID(),
                                        "Somebody else's",
                                        "GBP"))
                .hasMessageContaining("uq_households_singleton");
    }

    // ---------------------------------------------------------------------------------- helpers

    private int claimSetup(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        UPDATE instance_settings
                           SET setup_completed_at = now()
                         WHERE id = 1 AND setup_completed_at IS NULL
                        """)) {
            return statement.executeUpdate();
        }
    }

    private int completeSetup(Connection connection, String email) throws SQLException {
        if (claimSetup(connection) == 0) {
            return 0;
        }
        UUID user = UUID.randomUUID();
        UUID household = UUID.randomUUID();
        execute(
                connection,
                "INSERT INTO users (id, email, display_name, is_instance_admin) VALUES (?, ?, ?, true)",
                user,
                email,
                "First");
        execute(
                connection,
                "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                household,
                "Home",
                "GBP");
        execute(
                connection,
                "INSERT INTO household_members (id, household_id, user_id, role) VALUES (?, ?, ?, 'OWNER')",
                UUID.randomUUID(),
                household,
                user);
        return 1;
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SetupAttempt {
        int run(Connection connection) throws SQLException;
    }

    private List<Integer> runSimultaneously(SetupAttempt first, SetupAttempt second)
            throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = threads.submit(attempt(first, ready));
            Future<Integer> b = threads.submit(attempt(second, ready));
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    private Callable<Integer> attempt(SetupAttempt work, CyclicBarrier ready) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                ready.await(30, TimeUnit.SECONDS);
                int result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException refused) {
                return 0;
            }
        };
    }

    private Instant setupCompletedAt() {
        return jdbc.query(
                "SELECT setup_completed_at FROM instance_settings WHERE id = 1",
                (ResultSet rows) -> {
                    rows.next();
                    return rows.getObject("setup_completed_at", java.time.OffsetDateTime.class)
                                    == null
                            ? null
                            : rows.getObject("setup_completed_at", java.time.OffsetDateTime.class)
                                    .toInstant();
                });
    }
}
