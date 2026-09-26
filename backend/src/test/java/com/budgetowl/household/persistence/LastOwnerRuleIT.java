package com.budgetowl.household.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.persistence.PersistenceTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>A household always has at least one {@code OWNER}</b> — including when two owners try to
 * remove each other at the same instant.
 *
 * <p>This is the test the threat model asks for by name: <i>"Two owners remove each other
 * simultaneously, leaving the household with no administrator → the last-owner rule is enforced as
 * a database constraint inside the transaction, not as application logic that races."</i>
 *
 * <p>It is worth being precise about why application logic cannot do this. Under PostgreSQL's
 * default READ COMMITTED, two transactions deleting <em>different</em> rows never conflict, and a
 * {@code SELECT count(*) WHERE role = 'OWNER'} inside each one runs against a snapshot in which the
 * other's delete has not happened. Both would see "one owner will remain". Both would commit. The
 * household would have none. No amount of care in the service layer changes that — it is write
 * skew, and the fix has to be in the schema.
 *
 * <p>The schema's fix is {@code V3__households.sql}: a counter on {@code households} that both
 * transactions must UPDATE (so the second blocks on a row lock and then recomputes {@code
 * owner_count - 1} against the newly committed row), and a deferred constraint trigger that
 * re-reads it at {@code COMMIT}.
 *
 * <p>Repeated, because a concurrency test that passed once has told you very little.
 */
@SpringBootTest
class LastOwnerRuleIT extends PersistenceTestBase {

    private static final UUID HOUSEHOLD = UUID.fromString("00000000-0000-4000-8000-0000000000a0");
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID USER_A = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID USER_B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    @BeforeEach
    void seedOneHouseholdWithTwoOwners() {
        // One transaction: a household with no OWNER does not survive COMMIT, by design.
        transaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                            USER_A,
                            "ada@example.com",
                            "Ada");
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                            USER_B,
                            "grace@example.com",
                            "Grace");
                    jdbc.update(
                            "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                            HOUSEHOLD,
                            "Ada and Grace",
                            "GBP");
                    jdbc.update(
                            """
                            INSERT INTO household_members (id, household_id, user_id, role)
                            VALUES (?, ?, ?, 'OWNER'), (?, ?, ?, 'OWNER')
                            """,
                            OWNER_A,
                            HOUSEHOLD,
                            USER_A,
                            OWNER_B,
                            HOUSEHOLD,
                            USER_B);
                });

        assertThat(ownerCount()).isEqualTo(2);
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousOwnerRemovalsSucceeds() throws Exception {
        List<Optional<SQLException>> outcomes =
                runSimultaneously(removeMembership(OWNER_A), removeMembership(OWNER_B));

        assertThat(successes(outcomes))
                .as("one owner must be removed, the other refused")
                .isEqualTo(1);

        SQLException refusal = onlyFailure(outcomes);
        assertThat(refusal.getSQLState()).isEqualTo("23514");
        assertThat(refusal.getMessage()).contains("would be left without an OWNER");
        assertThat(constraintNameOf(refusal))
                .as("the service layer needs to tell this apart from any other integrity violation")
                .isEqualTo("ck_households_at_least_one_owner");

        assertThat(ownerCount()).isEqualTo(1);
        assertThat(remainingOwners()).hasSize(1);
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousOwnerDemotionsSucceeds() throws Exception {
        // The same hole from the other side: "nobody was removed, we only changed a role" leaves a
        // household with no administrator just as effectively.
        List<Optional<SQLException>> outcomes =
                runSimultaneously(demoteMembership(OWNER_A), demoteMembership(OWNER_B));

        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(constraintNameOf(onlyFailure(outcomes)))
                .isEqualTo("ck_households_at_least_one_owner");
        assertThat(ownerCount()).isEqualTo(1);
    }

    @RepeatedTest(5)
    void oneOwnerLeavingWhileTheOtherIsDemotedCannotEmptyTheHousehold() throws Exception {
        List<Optional<SQLException>> outcomes =
                runSimultaneously(removeMembership(OWNER_A), demoteMembership(OWNER_B));

        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(ownerCount()).isEqualTo(1);
    }

    @Test
    void removingOneOfTwoOwnersIsFine() {
        jdbc.update("DELETE FROM household_members WHERE id = ?", OWNER_A);

        assertThat(ownerCount()).isEqualTo(1);
    }

    @Test
    void removingTheLastOwnerIsRefused() {
        jdbc.update("DELETE FROM household_members WHERE id = ?", OWNER_A);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM household_members WHERE id = ?", OWNER_B))
                .hasMessageContaining("would be left without an OWNER");

        assertThat(ownerCount()).isEqualTo(1);
    }

    @Test
    void demotingTheLastOwnerIsRefused() {
        jdbc.update("DELETE FROM household_members WHERE id = ?", OWNER_A);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE household_members SET role = 'MEMBER' WHERE id = ?",
                                        OWNER_B))
                .hasMessageContaining("would be left without an OWNER");
    }

    @Test
    void deletingTheUserWhoIsTheLastOwnerIsRefused() {
        // The resolved decision "a user cannot delete their own account while they own a
        // household", seen from the database. The membership would cascade away and take the last
        // OWNER with it, so the whole delete is aborted — even if a service forgot to check.
        jdbc.update("DELETE FROM household_members WHERE id = ?", OWNER_A);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", USER_B))
                .hasMessageContaining("would be left without an OWNER");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE id = ?", Long.class, USER_B))
                .isEqualTo(1L);
    }

    @Test
    void deletingAUserWhoIsNotTheLastOwnerTakesTheirMembershipWithThem() {
        jdbc.update("DELETE FROM users WHERE id = ?", USER_A);

        assertThat(ownerCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM household_members WHERE household_id = ?",
                                Long.class,
                                HOUSEHOLD))
                .isEqualTo(1L);
    }

    @Test
    void aHouseholdThatIsNeverGivenAnOwnerDoesNotSurviveCommit() {
        // Deferred, not immediate: creating the household and its first OWNER in one transaction
        // has to work. Committing without one must not. The seeded household has to go first —
        // uq_households_singleton permits exactly one (ADR-0026).
        jdbc.update("DELETE FROM households WHERE id = ?", HOUSEHOLD);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                jdbc.update(
                                                        """
                                                        INSERT INTO households (id, name, base_currency)
                                                        VALUES (?, ?, ?)
                                                        """,
                                                        UUID.randomUUID(),
                                                        "Orphan",
                                                        "GBP")))
                .hasMessageContaining("would be left without an OWNER");
    }

    @Test
    void deletingTheWholeHouseholdIsAllowed() {
        // The rule protects a household that still exists. Deleting one is a different decision
        // (out of scope for this slice) and must not be blocked by this trigger.
        jdbc.update("DELETE FROM households WHERE id = ?", HOUSEHOLD);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class)).isZero();
    }

    // ---------------------------------------------------------------------------------- helpers

    /**
     * Two real transactions on two real connections, released into the delete at the same moment.
     */
    private List<Optional<SQLException>> runSimultaneously(Attempt first, Attempt second)
            throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<SQLException>> a = threads.submit(attempt(first, ready));
            Future<Optional<SQLException>> b = threads.submit(attempt(second, ready));
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }

    private Callable<Optional<SQLException>> attempt(Attempt work, CyclicBarrier ready) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                ready.await(30, TimeUnit.SECONDS);
                try (PreparedStatement statement = connection.prepareStatement(work.sql())) {
                    statement.setObject(1, work.membershipId());
                    statement.executeUpdate();
                }
                connection.commit();
                return Optional.empty();
            } catch (SQLException refused) {
                return Optional.of(refused);
            }
        };
    }

    private record Attempt(String sql, UUID membershipId) {}

    private static Attempt removeMembership(UUID membershipId) {
        return new Attempt("DELETE FROM household_members WHERE id = ?", membershipId);
    }

    private static Attempt demoteMembership(UUID membershipId) {
        return new Attempt(
                "UPDATE household_members SET role = 'MEMBER' WHERE id = ?", membershipId);
    }

    private static long successes(List<Optional<SQLException>> outcomes) {
        return outcomes.stream().filter(Optional::isEmpty).count();
    }

    private static SQLException onlyFailure(List<Optional<SQLException>> outcomes) {
        return outcomes.stream()
                .flatMap(Optional::stream)
                .reduce(
                        (one, other) -> {
                            throw new AssertionError(
                                    "both transactions failed: " + one + " / " + other);
                        })
                .orElseThrow(() -> new AssertionError("both transactions succeeded"));
    }

    private static String constraintNameOf(SQLException refusal) {
        // SQLException is Iterable, so the cast picks AssertJ's object overload.
        assertThat((Object) refusal).isInstanceOf(PSQLException.class);
        return ((PSQLException) refusal).getServerErrorMessage().getConstraint();
    }

    private long ownerCount() {
        return jdbc.queryForObject(
                "SELECT owner_count FROM households WHERE id = ?", Long.class, HOUSEHOLD);
    }

    private List<UUID> remainingOwners() {
        return jdbc.queryForList(
                "SELECT id FROM household_members WHERE household_id = ? AND role = 'OWNER'",
                UUID.class,
                HOUSEHOLD);
    }
}
