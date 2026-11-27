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
            awaitABlockedBackend();

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

    /** Takes the row lock, or reports that PostgreSQL broke a deadlock by refusing it. */
    private static boolean tryLock(Connection connection, UUID debt) {
        try {
            lock(connection, debt);
            return true;
        } catch (SQLException refused) {
            return false;
        }
    }

    /** Waits until another backend is waiting on a row lock: the completion, blocked. */
    private void awaitABlockedBackend() throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            Long waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
                            Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the completion never reached the locked debt");
    }
}
