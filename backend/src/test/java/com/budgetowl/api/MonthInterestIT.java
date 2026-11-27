package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * <b>Interest is charged once per debt per calendar month</b> (ADR-0043,
 * docs/features/debt-plan.md, The split), through the API, for every way a payment is recorded: by
 * hand, back-dated, corrected, deleted, from a check-in's bill prompt and from its surplus, and two
 * at once.
 *
 * <p>The canvas Visa at 22.9% throughout the hand-logged tests: $6,180.00 accrues {@code 6180 ×
 * 22.9 ÷ 1200 = 117.935 → $117.94} a month. The clock is stopped in August 2026, so "this month" is
 * a fact of the test rather than of the day it runs.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MonthInterestIT extends CheckInTestBase {

    private static final Instant AUG_21 = Instant.parse("2026-08-21T12:00:00Z");
    private static final Instant SEP_4 = Instant.parse("2026-09-04T12:00:00Z");

    // ------------------------------------------------------------------------- by hand

    @Test
    void a_second_hand_logged_payment_in_the_month_is_all_principal() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);

        ApiResponse first = log(owner, visa, "2026-08-05", "445.00");
        ApiResponse extra = log(owner, visa, "2026-08-20", "50.00");

        assertThat(split(first)).isEqualTo("117.9400 327.0600 5852.9400");
        assertThat(split(extra))
                .as("August's interest is already booked, so the extra comes off the balance whole")
                .isEqualTo("0.0000 50.0000 5802.9400");
        assertThat(owner.get("/api/debts/" + visa).stringField("balance")).isEqualTo("5802.9400");
        assertThat(augustInterest(visa)).isEqualByComparingTo("117.94");
    }

    @Test
    void a_payment_back_dated_into_a_booked_month_is_all_principal_and_rewrites_nothing() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        log(owner, visa, "2026-08-20", "445.00");

        ApiResponse earlier = log(owner, visa, "2026-08-03", "100.00");

        assertThat(split(earlier)).startsWith("0.0000 100.0000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT interest || ' ' || principal FROM debt_payments"
                                        + " WHERE debt_id = ? AND paid_date = '2026-08-20'",
                                String.class,
                                visa))
                .as("the payment that booked August keeps its stored split")
                .isEqualTo("117.9400 327.0600");
        assertThat(augustInterest(visa)).isEqualByComparingTo("117.94");
    }

    @Test
    void the_next_months_first_payment_carries_that_months_interest() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        log(owner, visa, "2026-08-05", "445.00");
        at(SEP_4);

        ApiResponse september = log(owner, visa, "2026-09-02", "445.00");

        // 5852.94 × 22.9 ÷ 1200 = 111.6936… → 111.69.
        assertThat(split(september)).isEqualTo("111.6900 333.3100 5519.6300");
    }

    @Test
    void another_debts_payment_books_nothing_for_this_one() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        UUID car = createAugustDebt(owner, "Car loan", "10650.00", "6.400", "342.00");
        log(owner, car, "2026-08-05", "342.00");

        ApiResponse visaFirst = log(owner, visa, "2026-08-06", "445.00");

        assertThat(split(visaFirst)).startsWith("117.9400");
    }

    // -------------------------------------------------------------------------- the preview

    @Test
    void the_preview_says_which_payment_carries_the_months_interest_and_matches_logging() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);

        ApiResponse firstAsked = preview(owner, visa, "{\"amount\":\"445.00\"}");
        log(owner, visa, "2026-08-21", "445.00");
        ApiResponse secondAsked = preview(owner, visa, "{\"amount\":\"50.00\"}");
        ApiResponse july =
                preview(owner, visa, "{\"amount\":\"50.00\",\"paidDate\":\"2026-07-15\"}");
        ApiResponse second = log(owner, visa, "2026-08-21", "50.00");

        assertThat(firstAsked.stringField("monthInterest")).isEqualTo("CARRIED");
        assertThat(firstAsked.stringField("interestMonth")).isEqualTo("2026-08");
        assertThat(firstAsked.stringField("interest")).isEqualTo("117.9400");
        assertThat(secondAsked.stringField("monthInterest")).isEqualTo("ALREADY_CARRIED");
        assertThat(secondAsked.stringField("interest")).isEqualTo("0.0000");
        assertThat(secondAsked.stringField("balanceAfter"))
                .as("the preview is what logging then records")
                .isEqualTo(second.stringField("balanceAfter"));
        assertThat(july.stringField("monthInterest"))
                .as("July has no payment, so a payment dated in July would carry July's interest")
                .isEqualTo("CARRIED");
        assertThat(july.stringField("interestMonth")).isEqualTo("2026-07");
    }

    @Test
    void a_preview_dated_in_the_future_is_refused() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);

        ApiResponse refused =
                preview(owner, visa, "{\"amount\":\"50.00\",\"paidDate\":\"2026-09-01\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("paidDate");
    }

    // ------------------------------------------------------------- delete and correct

    @Test
    void deleting_the_payment_that_carried_the_months_interest_lets_the_next_one_carry_it_once() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        String carrier = log(owner, visa, "2026-08-05", "445.00").stringField("id");
        log(owner, visa, "2026-08-10", "50.00");

        assertThat(owner.delete("/api/debts/" + visa + "/payments/" + carrier).status())
                .isEqualTo(204);
        assertThat(augustInterest(visa))
                .as("August has no interest booked once its carrier is gone")
                .isEqualByComparingTo("0");
        ApiResponse next = log(owner, visa, "2026-08-20", "100.00");

        // The balance is 6180.00 − 50.00 = 6130.00: 6130 × 22.9 ÷ 1200 = 116.98083… → 116.98.
        assertThat(split(next)).isEqualTo("116.9800 -16.9800 6146.9800");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT interest FROM debt_payments WHERE debt_id = ?"
                                        + " AND paid_date = '2026-08-10'",
                                BigDecimal.class,
                                visa))
                .as(
                        "the payment that stayed keeps its principal-only split: history is not re-split")
                .isEqualByComparingTo("0");
        assertThat(augustInterest(visa)).isEqualByComparingTo("116.98");
    }

    @Test
    void moving_an_interest_carrying_payment_into_a_booked_month_makes_it_principal_only() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        log(owner, visa, "2026-08-05", "445.00");
        at(SEP_4);
        ApiResponse september = log(owner, visa, "2026-09-02", "445.00");
        assertThat(split(september)).startsWith("111.6900");

        ApiResponse moved =
                owner.patch(
                        "/api/debts/" + visa + "/payments/" + september.stringField("id"),
                        "{\"paidDate\":\"2026-08-25\",\"version\":%d}"
                                .formatted(september.numberField("version")));
        ApiResponse nextInSeptember = log(owner, visa, "2026-09-03", "445.00");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(split(moved))
                .as("August already carries its interest, so the moved payment is all principal")
                .isEqualTo("0.0000 445.0000 5407.9400");
        assertThat(augustInterest(visa)).isEqualByComparingTo("117.94");
        assertThat(nextInSeptember.stringField("interest"))
                .as("September lost its carrier, so its next payment carries September's interest")
                .isNotEqualTo("0.0000");
    }

    // ------------------------------------------------------------- the check-in's paths

    @Test
    void a_bill_prompt_after_a_hand_logged_payment_is_all_principal() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID storeCard = bill(owner, "Store card", "25.00", 19, null);
        UUID storeDebt =
                debt(owner, "Store card", "CREDIT_CARD", "400.00", "9.90", "25.00", storeCard);
        // 400 × 9.9 ÷ 1200 = 3.30: August's interest, booked by the payment logged by hand.
        assertThat(split(log(owner, storeDebt, "2026-08-17", "30.00"))).startsWith("3.3000");
        answerTheCanvas(canvas, owner);
        JsonNode marked =
                answer(
                        owner,
                        "bill." + storeCard + ".2026-08-19",
                        "{\"choice\":\"MARK_PAID\",\"recordDebtPayment\":true}");
        answer(
                owner,
                "alloc",
                "{\"choice\":\"ROLL_OVER\",\"amount\":\"%s\"}"
                        .formatted(amount(figures(marked).get("surplus"))));

        ok(complete(owner), 200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT interest || ' ' || principal FROM debt_payments"
                                        + " WHERE debt_id = ? AND bill_occurrence_id IS NOT NULL",
                                String.class,
                                storeDebt))
                .as("the confirmed occurrence's payment is August's second: all principal")
                .isEqualTo("0.0000 25.0000");
    }

    /**
     * Sydney, 09:00 on September 1st, is 23:00 UTC on August 31st. A card bill marked paid in the
     * check-in then is September's payment. Dated by the server it would be August's second, all
     * principal, and September would never be charged.
     */
    @Test
    void a_bill_marked_paid_on_the_first_east_of_utc_is_the_new_months_payment() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID storeCard = bill(owner, "Store card", "25.00", 19, null);
        UUID storeDebt =
                debt(owner, "Store card", "CREDIT_CARD", "400.00", "9.90", "25.00", storeCard);
        // August booked by hand: 400 × 9.9 ÷ 1200 = 3.30, leaving 373.30.
        assertThat(split(log(owner, storeDebt, "2026-08-17", "30.00"))).startsWith("3.3000");
        markTheStoreCardPaid(canvas, owner, storeCard);
        at(Instant.parse("2026-08-31T23:00:00Z"));

        ok(complete(owner, "2026-09-01"), 200);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT paid_date || ' ' || interest || ' ' || principal"
                                        + " FROM debt_payments"
                                        + " WHERE debt_id = ? AND bill_occurrence_id IS NOT NULL",
                                String.class,
                                storeDebt))
                .as("dated the household's own day: September's first, 373.30 × 9.9 ÷ 1200")
                .isEqualTo("2026-09-01 3.0800 21.9200");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT paid_date FROM bill_occurrences"
                                        + " WHERE bill_id = ? AND status = 'PAID'",
                                String.class,
                                storeCard))
                .as("and the bill is marked paid on that same day")
                .isEqualTo("2026-09-01");
    }

    @Test
    void a_completion_dated_where_nobody_is_living_is_refused_and_writes_nothing() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID storeCard = bill(owner, "Store card", "25.00", 19, null);
        debt(owner, "Store card", "CREDIT_CARD", "400.00", "9.90", "25.00", storeCard);
        markTheStoreCardPaid(canvas, owner, storeCard);
        at(Instant.parse("2026-08-31T23:00:00Z"));

        // At 23:00 UTC on Aug 31 it is Aug 31 in UTC−12 and Sep 1 in UTC+14, and nowhere else.
        for (String nowhere : List.of("2026-08-30", "2026-09-02", "1899-12-31")) {
            ApiResponse refused = complete(owner, nowhere);
            assertThat(refused.status()).as(nowhere + ": " + refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("\"field\":\"today\"");
        }
        assertThat(count("SELECT count(*) FROM debt_payments WHERE bill_occurrence_id IS NOT NULL"))
                .isZero();
        ok(complete(owner, "2026-08-31"), 200);
    }

    @Test
    void a_surplus_paid_after_the_months_payment_is_all_principal_and_never_later() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID visa = canvas.id("visaDebt");
        // The canvas Visa is £6,180.00 at 24.99%: 6180 × 24.99 ÷ 1200 = 128.6985 → 128.70.
        assertThat(split(log(owner, visa, "2026-08-20", "185.00"))).startsWith("128.7000");
        answerTheCanvas(canvas, owner);
        JsonNode debt = choice(json(owner.get(W34)), "DEBT");

        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}".formatted(visa));
        ok(complete(owner), 200);

        assertThat(debt.path("monthInterest").isNull())
                .as("August's interest is already booked, so the choice names none")
                .isTrue();
        assertThat(YearMonth.parse(debt.get("payoffMonthWith").asString()))
                .isBeforeOrEqualTo(YearMonth.parse(debt.get("payoffMonthNow").asString()));
        assertThat(checkInSplit()).isEqualTo("0.0000 63.0000");
        assertThat(augustInterest(visa)).isEqualByComparingTo("128.70");
    }

    @Test
    void a_surplus_that_is_the_months_first_payment_carries_its_interest_once_and_never_later() {
        Canvas canvas = canvasWeek();
        ApiClient owner = canvas.household().owner();
        UUID visa = canvas.id("visaDebt");
        answerTheCanvas(canvas, owner);
        JsonNode debt = choice(json(owner.get(W34)), "DEBT");
        BigDecimal interestBefore = planInterest(owner);

        answer(
                owner,
                "alloc",
                "{\"choice\":\"DEBT\",\"debtId\":\"%s\",\"amount\":\"63.00\"}".formatted(visa));
        ok(complete(owner), 200);
        BigDecimal interestAfter = planInterest(owner);
        ApiResponse regular = log(owner, visa, "2026-08-21", "185.00");

        assertThat(amount(debt.get("monthInterest")))
                .as("the choice says the surplus carries August's interest")
                .isEqualTo("128.7000");
        assertThat(YearMonth.parse(debt.get("payoffMonthWith").asString()))
                .as("paying more never moves the payoff month later")
                .isBeforeOrEqualTo(YearMonth.parse(debt.get("payoffMonthNow").asString()));
        assertThat(checkInSplit())
                .as("the month's first payment carries the month's interest, whatever its size")
                .isEqualTo("128.7000 -65.7000");
        assertThat(split(regular))
                .as("so the regular payment later in August is all principal")
                .startsWith("0.0000 185.0000");
        assertThat(augustInterest(visa))
                .as("August is charged exactly one month of interest")
                .isEqualByComparingTo("128.70");
        assertThat(interestAfter.add(new BigDecimal("128.70")))
                .as(
                        "what the surplus booked plus what the plan still projects is no more than before")
                .isLessThanOrEqualTo(interestBefore);
    }

    // ------------------------------------------------------- the month boundary and dates

    @Test
    void a_payment_dated_the_first_by_a_household_east_of_utc_carries_the_new_months_interest() {
        // 23:00 UTC on August 31st is already September 1st in Sydney. The household's own date is
        // accepted — it is today somewhere on Earth — and so the payment is September's first
        // and carries September's interest, rather than being refused into August, whose interest
        // is already booked, which would leave September never charged.
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        log(owner, visa, "2026-08-05", "445.00");
        at(Instant.parse("2026-08-31T23:00:00Z"));

        ApiResponse asked =
                preview(owner, visa, "{\"amount\":\"445.00\",\"paidDate\":\"2026-09-01\"}");
        ApiResponse logged = log(owner, visa, "2026-09-01", "445.00");
        ApiResponse tooLate =
                owner.post(
                        "/api/debts/" + visa + "/payments",
                        "{\"paidDate\":\"2026-09-02\",\"amount\":\"10.00\"}");

        assertThat(asked.status()).isEqualTo(200);
        assertThat(asked.stringField("monthInterest")).isEqualTo("CARRIED");
        assertThat(asked.stringField("interestMonth")).isEqualTo("2026-09");
        // 5852.94 × 22.9 ÷ 1200 = 111.6936… → 111.69.
        assertThat(split(logged)).isEqualTo("111.6900 333.3100 5519.6300");
        assertThat(asked.stringField("balanceAfter")).isEqualTo(logged.stringField("balanceAfter"));
        assertThat(tooLate.status())
                .as("September 2nd is not yet today anywhere on Earth")
                .isEqualTo(400);
    }

    @Test
    void a_date_before_1900_is_refused_as_invalid_on_log_preview_and_correct() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);
        ApiResponse payment = log(owner, visa, "2026-08-05", "445.00");

        for (String date : List.of("-5000-01-01", "1899-12-31")) {
            ApiResponse logged =
                    owner.post(
                            "/api/debts/" + visa + "/payments",
                            "{\"paidDate\":\"%s\",\"amount\":\"10.00\"}".formatted(date));
            ApiResponse asked =
                    preview(
                            owner,
                            visa,
                            "{\"amount\":\"10.00\",\"paidDate\":\"%s\"}".formatted(date));
            ApiResponse corrected =
                    owner.patch(
                            "/api/debts/" + visa + "/payments/" + payment.stringField("id"),
                            "{\"paidDate\":\"%s\",\"version\":%d}"
                                    .formatted(date, payment.numberField("version")));

            for (ApiResponse refused : List.of(logged, asked, corrected)) {
                assertThat(refused.status()).as(date + " " + refused.body()).isEqualTo(400);
                assertThat(refused.stringField("code")).isEqualTo("validation-failed");
                assertThat(refused.body()).contains("paidDate");
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE debt_id = ?",
                                Long.class,
                                visa))
                .isEqualTo(1L);
    }

    @Test
    void a_balance_dated_before_1900_is_refused_as_invalid_on_create_and_confirm() {
        ApiClient owner = augustHousehold().owner();
        UUID visa = visa(owner);

        for (String date : List.of("-5000-01-01", "1899-12-31")) {
            ApiResponse created =
                    owner.post(
                            "/api/debts",
                            """
                            {"name":"Before 1900","kind":"CREDIT_CARD","balance":"100.00",
                             "balanceDate":"%s","apr":"10.000","minimumPayment":"25.00"}
                            """
                                    .formatted(date));
            ApiResponse confirmed =
                    owner.post(
                            "/api/debts/" + visa + "/balance",
                            "{\"balance\":\"6000.00\",\"balanceDate\":\"%s\"}".formatted(date));

            for (ApiResponse refused : List.of(created, confirmed)) {
                assertThat(refused.status()).as(date + " " + refused.body()).isEqualTo(400);
                assertThat(refused.stringField("code")).isEqualTo("validation-failed");
                assertThat(refused.body()).contains("balanceDate");
            }
        }
        assertThat(count("SELECT count(*) FROM debts WHERE name = 'Before 1900'")).isZero();
        assertThat(owner.get("/api/debts/" + visa).stringField("balance")).isEqualTo("6180.0000");
    }

    // ----------------------------------------------- a debt paid off this month (decision 10)

    @Test
    void paying_a_debt_off_to_the_cent_still_counts_what_was_paid_this_month() {
        // T $5,000 at 25%, S $600 at 0%, $500 a month. Paying S $599.99 this month counts as
        // paid, so month 1 has nothing left to give T beyond its minimum. Paying the last cent
        // clears S and takes it out of the ladder — and what was paid towards it was still spent
        // this month. Before the fix the plan forgot it, handed T the whole $500 and the plan's
        // interest fell by a hundred dollars for one cent.
        ApiClient owner = augustHousehold().owner();
        UUID t = createAugustDebt(owner, "T", "5000.00", "25.000", "100.00");
        UUID s = createAugustDebt(owner, "S", "600.00", "0.000", "50.00");
        long version = owner.get("/api/debt-plan").numberField("version");
        ok(
                owner.put(
                        "/api/debt-plan",
                        "{\"strategy\":\"AVALANCHE\",\"monthlyAmount\":\"500.00\",\"version\":%d}"
                                .formatted(version)),
                200);
        log(owner, s, "2026-08-20", "599.99");
        BigDecimal toTheCent = planInterest(owner);

        log(owner, s, "2026-08-21", "0.01");
        BigDecimal cleared = planInterest(owner);

        assertThat(owner.get("/api/debts/" + s).body()).doesNotContain("\"paidOffAt\":null");
        assertThat(cleared.subtract(toTheCent).abs())
                .as("one cent paid changes the plan's interest by cents, not by a month of T's")
                .isLessThan(new BigDecimal("1.00"));
        assertThat(t).isNotNull();
    }

    // --------------------------------------------------------------------- two at once

    @Test
    void payments_logged_at_the_same_moment_book_the_months_interest_exactly_once()
            throws Exception {
        Household household = augustHousehold();
        UUID visa = visa(household.owner());
        int writers = 6;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<ApiResponse>> results = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++) {
            ApiClient client = writer % 2 == 0 ? household.owner() : household.member();
            results.add(
                    pool.submit(
                            () -> {
                                start.await();
                                return client.post(
                                        "/api/debts/" + visa + "/payments",
                                        "{\"paidDate\":\"2026-08-20\",\"amount\":\"100.00\"}");
                            }));
        }
        start.countDown();
        for (Future<ApiResponse> result : results) {
            assertThat(result.get(60, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(
                        count(
                                "SELECT count(*) FROM debt_payments WHERE debt_id = '"
                                        + visa
                                        + "' AND interest <> 0"))
                .as("one carrier for August, however the writers interleaved")
                .isEqualTo(1L);
        assertThat(augustInterest(visa)).isEqualByComparingTo("117.94");
        // 6180.00 + 117.94 − 6 × 100.00: every split was taken against the balance the one before
        // it left, so the chain closes exactly.
        assertThat(household.owner().get("/api/debts/" + visa).stringField("balance"))
                .isEqualTo("5697.9400");
        assertThat(
                        count(
                                "SELECT count(DISTINCT balance_after) FROM debt_payments"
                                        + " WHERE debt_id = '"
                                        + visa
                                        + "'"))
                .as("no two splits were taken against the same balance")
                .isEqualTo((long) writers);
    }

    // ------------------------------------------------------------------------- helpers

    /** The canvas's answers, the store card's Aug 19 bill marked paid and recorded, rolled over. */
    private void markTheStoreCardPaid(Canvas canvas, ApiClient owner, UUID storeCard) {
        answerTheCanvas(canvas, owner);
        JsonNode marked =
                answer(
                        owner,
                        "bill." + storeCard + ".2026-08-19",
                        "{\"choice\":\"MARK_PAID\",\"recordDebtPayment\":true}");
        answer(
                owner,
                "alloc",
                "{\"choice\":\"ROLL_OVER\",\"amount\":\"%s\"}"
                        .formatted(amount(figures(marked).get("surplus"))));
    }

    /** A household seeded at real time, then the clock stopped in August 2026. */
    private Household augustHousehold() {
        Household household = seedHousehold();
        at(AUG_21);
        return household;
    }

    /** The canvas Visa, anchored on August 1st so every August date applies to it. */
    private UUID visa(ApiClient owner) {
        return createAugustDebt(owner, "Visa", "6180.00", "22.900", "185.00");
    }

    private UUID createAugustDebt(
            ApiClient owner, String name, String balance, String apr, String minimum) {
        return UUID.fromString(
                ok(
                                owner.post(
                                        "/api/debts",
                                        """
                                        {"name":"%s","kind":"CREDIT_CARD","balance":"%s",
                                         "balanceDate":"2026-08-01","apr":"%s","minimumPayment":"%s"}
                                        """
                                                .formatted(name, balance, apr, minimum)),
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

    private static ApiResponse preview(ApiClient client, UUID debt, String body) {
        return client.post("/api/debts/" + debt + "/payments/preview", body);
    }

    private static String split(ApiResponse payment) {
        return payment.stringField("interest")
                + " "
                + payment.stringField("principal")
                + " "
                + payment.stringField("balanceAfter");
    }

    private BigDecimal augustInterest(UUID debt) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(interest), 0) FROM debt_payments WHERE debt_id = ?"
                        + " AND paid_date BETWEEN '2026-08-01' AND '2026-08-31'",
                BigDecimal.class,
                debt);
    }

    private String checkInSplit() {
        return jdbc.queryForObject(
                "SELECT interest || ' ' || principal FROM debt_payments"
                        + " WHERE provenance = 'CHECK_IN'",
                String.class);
    }

    private static BigDecimal planInterest(ApiClient owner) {
        JsonNode plan = json(owner.get("/api/debt-plan"));
        return new BigDecimal(
                plan.get("currencies").get(0).get("selected").get("totalInterest").asString());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static JsonNode choice(JsonNode week, String kind) {
        for (JsonNode choice : step(week, "NEXT_WEEK").get("choices")) {
            if (kind.equals(choice.get("choice").asString())) {
                return choice;
            }
        }
        throw new AssertionError("no " + kind + " choice in " + week);
    }
}
