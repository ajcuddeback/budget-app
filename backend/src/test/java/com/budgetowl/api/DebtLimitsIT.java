package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.debt.service.DebtService;
import com.budgetowl.debt.service.PayoffPlanService;
import com.budgetowl.debt.service.TermsSolveRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The four bounds the threat model added, each asserted against its bound rather than against "it
 * came back".
 *
 * <p>These did not come from the designs. They came from finishing three attack sentences
 * (docs/features/debt-plan.md, Security considerations), and all three have the same shape: a cheap
 * {@code POST} loop amplified into an expensive {@code GET}.
 *
 * <p><b>Why the assertions are on the bound.</b> An unbounded series also comes back; an
 * unpaginated collection also returns rows; an uncapped table also accepts the fifty-first insert.
 * A test that only checks a {@code 200} would pass with every one of these controls removed, which
 * is the way a limit test most often ends up proving nothing.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtLimitsIT extends ApiTestBase {

    private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();

    // ------------------------------------------------------------------ the per-household cap

    @Test
    void past_the_cap_a_new_debt_is_refused_and_nothing_is_written() {
        Household household = seedHousehold();
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        seedDebts(householdId, DebtService.MAXIMUM_PER_HOUSEHOLD);

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"One too many","kind":"CREDIT_CARD","balance":"100.0000",
                                 "balanceDate":"%s","apr":"1.000","minimumPayment":"10.0000"}
                                """
                                        .formatted(TODAY));

        // Without the cap this test creates the debt, and the GET below is the amplification the
        // control exists to stop: two strategies × every debt × up to 600 months, from one cheap
        // request.
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("debt-limit-reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class))
                .isEqualTo((long) DebtService.MAXIMUM_PER_HOUSEHOLD);
    }

    @Test
    void the_cap_counts_archived_debts_too() {
        Household household = seedHousehold();
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        seedDebts(householdId, DebtService.MAXIMUM_PER_HOUSEHOLD);
        jdbc.update("UPDATE debts SET archived = true");

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"One too many","kind":"CREDIT_CARD","balance":"100.0000",
                                 "balanceDate":"%s","apr":"1.000","minimumPayment":"10.0000"}
                                """
                                        .formatted(TODAY));

        // A caller that could archive its way past the cap would have exactly the amplification
        // the cap exists to prevent — the rows are still there to be projected the moment anything
        // un-archives them.
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("debt-limit-reached");
    }

    // ------------------------------------------------------------------- the paginated history

    @Test
    void the_payment_history_returns_a_page_and_honours_its_bounds() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Visa", "6180.00", "22.900", "118.00", null);
        seedPayments(debt, 60);

        ApiResponse firstPage = household.owner().get("/api/debts/" + debt + "/payments");
        ApiResponse secondPage =
                household.owner().get("/api/debts/" + debt + "/payments?page=1&size=50");
        ApiResponse oversized = household.owner().get("/api/debts/" + debt + "/payments?size=500");

        // The envelope api-style.md requires of every collection — and here a control as well: a
        // household with years of records has more payments than one response should carry.
        assertThat(firstPage.numberField("totalElements")).isEqualTo(60L);
        assertThat(firstPage.numberField("totalPages")).isEqualTo(2L);
        assertThat(firstPage.numberField("size")).isEqualTo(50L);
        assertThat(countOf(firstPage, "\"paidDate\"")).isEqualTo(50);
        assertThat(countOf(secondPage, "\"paidDate\"")).isEqualTo(10);
        // Over the maximum is clamped, not rejected (docs/guides/api-style.md).
        assertThat(oversized.numberField("size")).isEqualTo(200L);
        assertThat(countOf(oversized, "\"paidDate\"")).isEqualTo(60);
    }

    // --------------------------------------------------------------------- the bounded series

    @Test
    void the_plan_series_is_bounded_independently_of_the_solvers_iteration_cap() {
        Household household = seedHousehold();
        // 1% on 10,000 paid at 100 a month: it clears, but takes hundreds of months. The solver
        // may iterate 600 of them internally; the response must not carry 600 points.
        createDebt(household.owner(), "Slow", "10000.00", "1.000", "100.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"100.0000","version":0}
                        """);

        ApiResponse plan = household.owner().get("/api/debt-plan");

        int points = countOf(plan, "\"month\":");
        assertThat(plan.status()).isEqualTo(200);
        // Three series come back — the selected strategy plus both comparisons — each capped. The
        // assertion is on the bound, because an unbounded series also comes back.
        assertThat(points).isPositive();
        assertThat(points).isLessThanOrEqualTo(3 * (PayoffPlanService.MAXIMUM_SERIES_POINTS + 1));
        assertThat(plan.body()).contains("\"seriesMonthStride\":");
        assertThat(plan.numberField("seriesMonthStride"))
                .as("a thinned line says how many months one point stands for")
                .isGreaterThan(1L);
    }

    @Test
    void a_never_clearing_debt_still_renders_a_plan_with_a_bounded_series() {
        Household household = seedHousehold();
        // 22.9% on 6,180 accrues 117.94 a month, so 100 a month never clears it — and with
        // nothing else in the ladder there is no roll-down to rescue it either. The plan still
        // renders: one that refused to because a debt is underwater is a plan nobody can use to
        // fix it.
        createDebt(household.owner(), "Underwater", "6180.00", "22.900", "100.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"100.0000","version":0}
                        """);

        ApiResponse plan = household.owner().get("/api/debt-plan");

        assertThat(plan.status()).isEqualTo(200);
        // Null rather than a comfortable guess: a debt-free date that ignores the underwater debt
        // would be a lie.
        assertThat(plan.body()).contains("\"debtFreeMonth\":null");
        assertThat(plan.body()).contains("\"reason\":\"never-clears\"");
        // The ladder iterated its 600-month cap. The response must not carry 600 points per
        // strategy, which is the whole point of bounding the series separately.
        assertThat(countOf(plan, "\"month\":"))
                .isLessThanOrEqualTo(3 * (PayoffPlanService.MAXIMUM_SERIES_POINTS + 1));
        assertThat(plan.numberField("seriesMonthStride")).isGreaterThan(1L);
    }

    @Test
    void a_rung_that_cannot_clear_alone_still_clears_once_the_roll_down_reaches_it() {
        Household household = seedHousehold();
        // The same underwater card, with a small debt below it. Its own minimum never clears it,
        // and the plan does — because when the store card clears, its minimum rolls down. The
        // plan-level answer is not the per-debt one, and both are reported.
        createDebt(household.owner(), "Underwater", "6180.00", "22.900", "100.00", null);
        createDebt(household.owner(), "Store card", "900.00", "12.000", "50.00", null);
        household
                .owner()
                .put(
                        "/api/debt-plan",
                        """
                        {"strategy":"AVALANCHE","monthlyAmount":"150.0000","version":0}
                        """);

        ApiResponse plan = household.owner().get("/api/debt-plan");

        assertThat(plan.body()).doesNotContain("\"debtFreeMonth\":null");
        assertThat(countOf(plan, "\"month\":"))
                .isLessThanOrEqualTo(3 * (PayoffPlanService.MAXIMUM_SERIES_POINTS + 1));
    }

    @Test
    void a_debts_own_chart_is_bounded_too() {
        Household household = seedHousehold();
        UUID debt = createDebt(household.owner(), "Slow", "10000.00", "1.000", "100.00", null);

        ApiResponse detail = household.owner().get("/api/debts/" + debt);

        // Two lines — what it is paying, and what the minimum would do — each capped.
        assertThat(countOf(detail, "\"month\":"))
                .isLessThanOrEqualTo(2 * (PayoffPlanService.MAXIMUM_SERIES_POINTS + 1));
        assertThat(detail.numberField("seriesMonthStride")).isGreaterThan(1L);
    }

    // ---------------------------------------------------------------------------- the solver

    @Test
    void a_solve_that_cannot_finish_inside_the_month_cap_is_refused_quickly() {
        Household household = seedHousehold();

        // A payment that does cover the interest — so it is not never-clears — but would take
        // millions of months. The month cap is the one that fires on plausible input.
        Instant started = Instant.now();
        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/debt-terms/solve",
                                """
                                {"balance":"999999999999999.9999","currency":"GBP","apr":"0.000",
                                 "payment":"1.0000","firstPaymentMonth":"2027-01"}
                                """);
        Duration took = Duration.between(started, Instant.now());

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("terms-unsolvable");
        // Timed, so a regression that removes a cap FAILS rather than merely getting slower. The
        // bound is 600 iterations × 64 probes; anything near a second here means one of them is
        // gone.
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void a_solve_loop_is_rate_limited_rather_than_sustained_cpu() {
        Household household = seedHousehold();
        String body =
                """
                {"balance":"20000.0000","currency":"GBP","payment":"404.5800","monthsLeft":60,
                 "firstPaymentMonth":"2027-01"}
                """;

        int refusedAt = 0;
        for (int call = 1; call <= TermsSolveRateLimiter.PERMITTED_PER_WINDOW + 5; call++) {
            ApiResponse response = household.owner().post("/api/debt-terms/solve", body);
            if (response.status() == 429) {
                refusedAt = call;
                assertThat(response.stringField("code")).isEqualTo("rate-limited");
                assertThat(response.header("retry-after")).isPresent();
                break;
            }
            assertThat(response.status()).as("call %d", call).isEqualTo(200);
        }

        // This is the only endpoint in the app whose cost depends on the numbers in the request,
        // so a loop of them is the attack. Never a permanent lock: the window rolls.
        assertThat(refusedAt).isEqualTo(TermsSolveRateLimiter.PERMITTED_PER_WINDOW + 1);
    }

    // ------------------------------------------------------------------------------- internals

    /** Counts occurrences of a literal in the body — enough to count rows without a parser. */
    private static int countOf(ApiResponse response, String literal) {
        int count = 0;
        int index = response.body().indexOf(literal);
        while (index >= 0) {
            count++;
            index = response.body().indexOf(literal, index + literal.length());
        }
        return count;
    }

    /**
     * Seeds debts straight into the table.
     *
     * <p>Through SQL rather than the API on purpose: the cap is what the API path is being tested
     * for, and reaching it through fifty POSTs would be testing the control with the control.
     */
    private void seedDebts(UUID householdId, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update(
                    """
                    INSERT INTO debts (id, household_id, name, kind, currency, apr,
                                       minimum_payment, anchor_balance, anchor_date,
                                       balance_source)
                    VALUES (?, ?, ?, 'CREDIT_CARD', 'GBP', 1.000, 10.0000, 100.0000,
                            CURRENT_DATE, 'CONFIRMED')
                    """,
                    UUID.randomUUID(),
                    householdId,
                    "Debt " + index);
        }
    }

    /** Seeds a payment history. The split reconciles, as {@code ck_debt_payments} requires. */
    private void seedPayments(UUID debtId, int count) {
        UUID householdId = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        for (int index = 0; index < count; index++) {
            jdbc.update(
                    """
                    INSERT INTO debt_payments (id, household_id, debt_id, paid_date, amount,
                                               interest, principal, balance_after, provenance)
                    VALUES (?, ?, ?, CURRENT_DATE - ?, 10.0000, 0.0000, 10.0000, 100.0000,
                            'USER_LOGGED')
                    """,
                    UUID.randomUUID(),
                    householdId,
                    debtId,
                    index);
        }
    }
}
