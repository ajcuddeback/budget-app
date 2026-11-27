package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The per-debt row lock on every payment write, proved where it matters: under concurrency
 * (ADR-0043, docs/features/debt-plan.md, The split, rule 5).
 *
 * <p>Each test is built to <b>fail if its lock is removed</b>. Logging is covered in {@code
 * MonthInterestIT}; these are the other writers — a correction, a delete, a balance confirmation —
 * and the order a check-in's completion takes two debts in.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtLockingIT extends CheckInTestBase {

    private static final Instant AUG_21 = Instant.parse("2026-08-21T12:00:00Z");

    // ---------------------------------------------------- a completion's two debts, in order

    /**
     * A completion writes a bill prompt's payment to one debt and the surplus to another, in that
     * order, and each write locks its own debt. Another transaction taking the same two debts the
     * other way round would deadlock with it. Two completions cannot be made to do that through the
     * API (only one week at a time has a surplus, and items are applied in a fixed order), so the
     * other transaction here is a plain JDBC one taking the debts in the global lock order.
     *
     * <p>Deterministic, not a race: the JDBC transaction holds the surplus debt before the
     * completion starts and waits until the completion is blocked. A completion that locks in the
     * global order is then waiting while holding no debt, and the JDBC side takes the prompt's debt
     * freely. One that locks in item order already holds the prompt's debt, and PostgreSQL reports
     * the deadlock.
     */
    @Test
    void a_completion_locks_its_debts_in_the_global_order_so_an_opposite_writer_waits()
            throws Exception {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID visa = canvas.id("visaDebt");
        UUID storeBill = bill(owner, "Store card", "25.00", 19, null);
        // The prompt's debt must sort AFTER the surplus's debt, so the global order and the
        // completion's item order disagree — otherwise this test would prove nothing.
        UUID storeDebt = null;
        for (int attempt = 0; attempt < 40 && storeDebt == null; attempt++) {
            UUID candidate =
                    debt(owner, "Store card", "CREDIT_CARD", "400.00", "9.90", "25.00", storeBill);
            if (candidate.toString().compareTo(visa.toString()) > 0) {
                storeDebt = candidate;
            } else {
                ok(owner.delete("/api/debts/" + candidate), 204);
            }
        }
        assertThat(storeDebt).as("a debt id that sorts after the Visa's").isNotNull();
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "bill." + storeBill + ".2026-08-19",
                "{\"choice\":\"MARK_PAID\",\"recordDebtPayment\":true}");
        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}".formatted(visa));

        DataSource dataSource = jdbc.getDataSource();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        boolean tookBoth;
        ApiResponse completed;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            lock(other, visa);
            Future<ApiResponse> completing = pool.submit(() -> complete(owner));
            awaitABackendBlockedBy(other);

            tookBoth = tryLock(other, storeDebt);
            other.rollback();
            completed = completing.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(tookBoth)
                .as("the other writer took the second debt without a deadlock")
                .isTrue();
        assertThat(completed.status()).as(completed.body()).isEqualTo(200);
    }

    // ------------------------------------------------- a completion reads its debts fresh

    /**
     * A completion builds its picture first and only then locks the debts it writes to. If another
     * transaction changed a debt in between — here, settled it — the lock must bring the fresh row
     * with it: a query returns the entity the completion already loaded, as it was loaded.
     *
     * <p>Deterministic: the other transaction settles the Visa and holds it while the completion
     * reads the old row (not blocked: a plain read) and then waits for the lock. It commits; the
     * completion gets the lock and must see the debt paid off.
     */
    @Test
    void a_completion_decides_against_the_debt_as_it_is_once_locked_not_as_it_was_read()
            throws Exception {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID visa = canvas.id("visaDebt");
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}".formatted(visa));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        ApiResponse completed;
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement settle =
                    other.prepareStatement(
                            "UPDATE debts SET paid_off_at = now(), version = version + 1"
                                    + " WHERE id = ?")) {
                settle.setObject(1, visa);
                settle.executeUpdate();
            }
            Future<ApiResponse> completing = pool.submit(() -> complete(owner));
            awaitABackendBlockedBy(other);
            other.commit();
            completed = completing.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed.status()).as(completed.body()).isEqualTo(409);
        assertThat(completed.body()).contains("debt-paid-off");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE provenance = 'CHECK_IN'",
                                Long.class))
                .as("nothing recorded against a debt that was settled while the check-in waited")
                .isZero();
    }

    // ------------------------------------------------------- a debt deleted while waited for

    /**
     * Deterministic: the other transaction deletes the debt and holds the delete open while the
     * payment waits for the row; then it commits. The row the payment was waiting for is gone,
     * which is the household's own 404, not a 500 from refreshing a row that no longer exists.
     */
    @Test
    void a_debt_deleted_while_a_payment_waits_for_it_is_a_404() throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        UUID debt = createDebtOn(owner, today, "1000.00", "10.000");

        ApiResponse logged;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            delete(other, debt);
            Future<ApiResponse> logging =
                    pool.submit(
                            () ->
                                    owner.post(
                                            "/api/debts/" + debt + "/payments",
                                            "{\"paidDate\":\"%s\",\"amount\":\"10.00\"}"
                                                    .formatted(today)));
            awaitABackendBlockedBy(other);
            other.commit();
            logged = logging.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(logged.status()).as(logged.body()).isEqualTo(404);
    }

    /**
     * The same for a check-in completion: the surplus's debt is deleted while the completion waits
     * for it. The completion skips a debt that is gone when it locks, and the surplus's write then
     * finds no debt: {@code 409 check-in-stale}, subject gone, with nothing written.
     */
    @Test
    void a_debt_deleted_while_a_completion_waits_for_it_is_subject_gone() throws Exception {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID visa = canvas.id("visaDebt");
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}".formatted(visa));

        ApiResponse completed;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            delete(other, visa);
            Future<ApiResponse> completing = pool.submit(() -> complete(owner));
            awaitABackendBlockedBy(other);
            other.commit();
            completed = completing.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed.status()).as(completed.body()).isEqualTo(409);
        assertThat(completed.body()).contains("check-in-stale").contains("subject-gone");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE provenance = 'CHECK_IN'",
                                Long.class))
                .isZero();
    }

    // ------------------------------------------------------------- the custom order

    /**
     * Setting "Your own order" writes a position to every debt whose position changed, and each
     * write takes that debt's row lock. In name order it could deadlock with a writer taking the
     * same debts in the global order; it writes in the global order instead.
     */
    @Test
    void setting_your_own_order_writes_the_debts_in_the_global_lock_order() throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        UUID one = createDebtOn(owner, today, "1000.00", "10.000");
        UUID two = createDebtOn(owner, today, "2000.00", "20.000");
        UUID first = one.toString().compareTo(two.toString()) < 0 ? one : two;
        UUID second = first.equals(one) ? two : one;
        // Named so that NAME order is the opposite of the lock order.
        rename(owner, first, "Zeta");
        rename(owner, second, "Alpha");
        long version = owner.get("/api/debt-plan").numberField("version");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        boolean tookBoth;
        ApiResponse ordered;
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            lock(other, first);
            Future<ApiResponse> ordering =
                    pool.submit(
                            () ->
                                    owner.put(
                                            "/api/debt-plan",
                                            ("{\"strategy\":\"CUSTOM\",\"monthlyAmount\":\"100.00\","
                                                            + "\"customOrder\":[\"%s\",\"%s\"],\"version\":%d}")
                                                    .formatted(second, first, version)));
            awaitABackendBlockedBy(other);
            tookBoth = tryLock(other, second);
            other.rollback();
            ordered = ordering.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(tookBoth)
                .as("the other writer took the second debt without a deadlock")
                .isTrue();
        assertThat(ordered.status()).as(ordered.body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ the lock timeout

    @Test
    void a_debt_held_too_long_is_a_retryable_409_record_busy_not_a_hang_or_a_500()
            throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        UUID debt = createDebtOn(owner, today, "1000.00", "10.000");

        ApiResponse refused;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            lock(other, debt);
            Future<ApiResponse> logging =
                    pool.submit(
                            () ->
                                    owner.post(
                                            "/api/debts/" + debt + "/payments",
                                            "{\"paidDate\":\"%s\",\"amount\":\"10.00\"}"
                                                    .formatted(today)));
            try {
                // Well past the lock timeout: an answer must come while the debt is still held.
                refused = logging.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException hung) {
                throw new AssertionError("the request was still waiting for the debt after 15s");
            } finally {
                other.rollback();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("record-busy");
        ApiResponse retried = log(owner, debt, today, "10.00");
        assertThat(retried.status())
                .as("the same request, sent again, goes through")
                .isEqualTo(201);
    }

    /** The writers that take debt rows without {@code DebtLocks.lock} are bounded the same way. */
    @Test
    void your_own_order_and_a_delete_on_a_held_debt_are_409_record_busy_too() throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        UUID one = createDebtOn(owner, today, "1000.00", "10.000");
        UUID two = createDebtOn(owner, today, "2000.00", "20.000");
        long version = owner.get("/api/debt-plan").numberField("version");

        List<ApiResponse> refused = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = jdbc.getDataSource().getConnection()) {
            other.setAutoCommit(false);
            lock(other, one);
            lock(other, two);
            List<Callable<ApiResponse>> writes =
                    List.of(
                            () ->
                                    owner.put(
                                            "/api/debt-plan",
                                            ("{\"strategy\":\"CUSTOM\",\"monthlyAmount\":\"100.00\","
                                                            + "\"customOrder\":[\"%s\",\"%s\"],\"version\":%d}")
                                                    .formatted(two, one, version)),
                            () -> owner.delete("/api/debts/" + one));
            for (Callable<ApiResponse> write : writes) {
                try {
                    refused.add(pool.submit(write).get(15, TimeUnit.SECONDS));
                } catch (java.util.concurrent.TimeoutException hung) {
                    throw new AssertionError("a write was still waiting for the debt after 15s");
                }
            }
            other.rollback();
        } finally {
            pool.shutdownNow();
        }

        for (ApiResponse busy : refused) {
            assertThat(busy.status()).as(busy.body()).isEqualTo(409);
            assertThat(busy.stringField("code")).isEqualTo("record-busy");
        }
    }

    // ------------------------------------------------------------------------ correct

    @Test
    void corrections_moving_payments_into_one_month_at_once_leave_it_one_carrier()
            throws Exception {
        Household household = seedHousehold();
        at(AUG_21);
        ApiClient owner = household.owner();
        UUID debt = createDebtOn(owner, "2026-02-01", "6180.00", "22.900");
        List<ApiResponse> logged = new ArrayList<>();
        for (int month = 2; month <= 7; month++) {
            logged.add(log(owner, debt, LocalDate.of(2026, month, 20).toString(), "445.00"));
        }

        List<Callable<ApiResponse>> moves = new ArrayList<>();
        for (int index = 0; index < logged.size(); index++) {
            ApiResponse payment = logged.get(index);
            ApiClient client = index % 2 == 0 ? owner : household.member();
            moves.add(
                    () ->
                            client.patch(
                                    "/api/debts/" + debt + "/payments/" + payment.stringField("id"),
                                    "{\"paidDate\":\"2026-08-10\",\"version\":%d}"
                                            .formatted(payment.numberField("version"))));
        }
        for (ApiResponse moved : together(moves)) {
            assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE debt_id = ?"
                                        + " AND paid_date = '2026-08-10' AND interest <> 0",
                                Long.class,
                                debt))
                .as("one payment carries August's interest, however the moves interleaved")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------------- delete

    @Test
    void a_delete_and_a_payment_at_once_never_leave_a_debt_settled_with_a_balance()
            throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<Callable<ApiResponse>> writes = new ArrayList<>();
        List<UUID> debts = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            // 0% so the arithmetic is the amounts: 100 owed, 30 paid. Paying the other 70 settles
            // it; deleting the 30 reopens it at 30. Interleaved, the payment can settle the debt
            // while the delete puts 30 back on it.
            UUID debt = createDebtOn(owner, today, "100.00", "0.000");
            String first = log(owner, debt, today, "30.00").stringField("id");
            debts.add(debt);
            writes.add(() -> owner.delete("/api/debts/" + debt + "/payments/" + first));
            writes.add(
                    () ->
                            household
                                    .member()
                                    .post(
                                            "/api/debts/" + debt + "/payments",
                                            "{\"paidDate\":\"%s\",\"amount\":\"70.00\"}"
                                                    .formatted(today)));
        }

        together(writes);

        for (UUID debt : debts) {
            ApiResponse read = owner.get("/api/debts/" + debt);
            boolean settled = !read.body().contains("\"paidOffAt\":null");
            boolean owes = new BigDecimal(read.stringField("balance")).signum() > 0;
            assertThat(settled)
                    .as("settled exactly when nothing is owed: " + read.stringField("balance"))
                    .isEqualTo(!owes);
        }
    }

    // --------------------------------------------------------------- confirm balance

    @Test
    void a_statement_and_a_payment_at_once_still_chain_in_the_history() throws Exception {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<Callable<ApiResponse>> writes = new ArrayList<>();
        List<UUID> debts = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            UUID debt = createDebtOn(owner, today, "1000.00", "0.000");
            debts.add(debt);
            writes.add(
                    () ->
                            owner.post(
                                    "/api/debts/" + debt + "/balance",
                                    "{\"balance\":\"950.00\",\"balanceDate\":\"%s\"}"
                                            .formatted(today)));
            writes.add(
                    () ->
                            household
                                    .member()
                                    .post(
                                            "/api/debts/" + debt + "/payments",
                                            "{\"paidDate\":\"%s\",\"amount\":\"20.00\"}"
                                                    .formatted(today)));
        }

        together(writes);

        for (UUID debt : debts) {
            BigDecimal[] correction =
                    jdbc.queryForObject(
                            "SELECT balance_after + principal, balance_after FROM debt_payments"
                                    + " WHERE debt_id = ? AND provenance = 'STATEMENT_CORRECTION'",
                            (row, n) ->
                                    new BigDecimal[] {row.getBigDecimal(1), row.getBigDecimal(2)},
                            debt);
            BigDecimal[] payment =
                    jdbc.queryForObject(
                            "SELECT balance_after + principal, balance_after FROM debt_payments"
                                    + " WHERE debt_id = ? AND provenance = 'USER_LOGGED'",
                            (row, n) ->
                                    new BigDecimal[] {row.getBigDecimal(1), row.getBigDecimal(2)},
                            debt);
            boolean paymentFirst = payment[1].compareTo(correction[0]) == 0;
            boolean statementFirst = correction[1].compareTo(payment[0]) == 0;
            assertThat(paymentFirst || statementFirst)
                    .as(
                            "one was taken against what the other left: payment %s→%s,"
                                    + " correction %s→%s",
                            payment[0], payment[1], correction[0], correction[1])
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------ helpers

    private UUID createDebtOn(ApiClient owner, String date, String balance, String apr) {
        return UUID.fromString(
                ok(
                                owner.post(
                                        "/api/debts",
                                        """
                                        {"name":"Card","kind":"CREDIT_CARD","balance":"%s",
                                         "balanceDate":"%s","apr":"%s","minimumPayment":"25.00"}
                                        """
                                                .formatted(balance, date, apr)),
                                201)
                        .stringField("id"));
    }

    private static void rename(ApiClient owner, UUID debt, String name) {
        long version = owner.get("/api/debts/" + debt).numberField("version");
        ok(
                owner.patch(
                        "/api/debts/" + debt,
                        "{\"name\":\"%s\",\"version\":%d}".formatted(name, version)),
                200);
    }

    private static ApiResponse log(ApiClient client, UUID debt, String date, String amount) {
        return ok(
                client.post(
                        "/api/debts/" + debt + "/payments",
                        "{\"paidDate\":\"%s\",\"amount\":\"%s\"}".formatted(date, amount)),
                201);
    }

    /** Runs every call at the same moment and waits for all of them. */
    private static List<ApiResponse> together(List<Callable<ApiResponse>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Callable<ApiResponse> call : calls) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return call.call();
                                }));
            }
            start.countDown();
            List<ApiResponse> responses = new ArrayList<>();
            for (Future<ApiResponse> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void lock(Connection connection, UUID debt) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement("SELECT id FROM debts WHERE id = ? FOR UPDATE")) {
            select.setObject(1, debt);
            select.executeQuery().close();
        }
    }

    private static void delete(Connection connection, UUID debt) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM debts WHERE id = ?")) {
            delete.setObject(1, debt);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
    }

    /** Takes the row lock, or reports that PostgreSQL broke a deadlock by refusing it. */
    private static boolean tryLock(Connection connection, UUID debt) {
        try {
            lock(connection, debt);
            return true;
        } catch (SQLException refused) {
            return false;
        }
    }

    /**
     * Waits until a backend is waiting on a lock that {@code holder} holds: the request under test,
     * blocked. Scoped to the holder's own process id, so a lock waiter anywhere else in the
     * database cannot satisfy it.
     */
    private void awaitABackendBlockedBy(Connection holder) throws Exception {
        int pid;
        try (PreparedStatement select = holder.prepareStatement("SELECT pg_backend_pid()");
                java.sql.ResultSet row = select.executeQuery()) {
            row.next();
            pid = row.getInt(1);
        }
        for (int poll = 0; poll < 200; poll++) {
            Long waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE ? = ANY(pg_blocking_pids(pid))",
                            Long.class,
                            pid);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("nothing ever waited on the held debt");
    }
}
