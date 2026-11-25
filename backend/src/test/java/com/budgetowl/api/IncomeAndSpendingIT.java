package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Report 1's arithmetic: what counts, what does not, and the identity the page leads with
 * (docs/features/reporting.md, Testing notes).
 *
 * <p>Every row is created through the real API, so a transfer is a real pair written by the
 * transfer endpoint, a refund is a real positive amount in an expense category, and an envelope
 * entry is a real {@code MANUAL} entry. The report is then read over HTTP, as the page reads it.
 */
class IncomeAndSpendingIT extends ReportTestBase {

    private static final String AUGUST = "2026-08";

    @Autowired private EntityManagerFactory entityManagerFactory;

    private Household household;
    private UUID current;
    private UUID savings;
    private UUID salary;
    private UUID groceries;

    @BeforeEach
    void seedAHouseholdWithTwoAccounts() {
        household = seedHousehold();
        current = account(household.owner(), "Current", "GBP");
        savings = account(household.owner(), "Savings", "GBP");
        salary = seededCategoryId("Salary");
        groceries = seededCategoryId("Groceries");
    }

    @AfterEach
    void stopCountingStatements() {
        statistics().setStatisticsEnabled(false);
    }

    // -------------------------------------------------------------------------------- transfers

    @Test
    void aSameCurrencyTransferChangesNoFigureIncludingTheUncategorisedOnes() {
        // given: a month with one real row each way, then a transfer between the two accounts
        createTransaction(household.owner(), current, "2026-08-01", "2000.00", salary);
        createTransaction(household.owner(), current, "2026-08-02", "-100.00", groceries);
        JsonNode before = august();

        // when
        transfer(household.owner(), current, savings, "2026-08-10", "750.00");

        // then: the trap. Both legs have a NULL category, so a report that took "uncategorised"
        // to mean category_id IS NULL would show 750 in and 750 out here.
        JsonNode after = august();
        assertThat(text(after, "uncategorisedIn")).isEqualTo("0.0000");
        assertThat(text(after, "uncategorisedOut")).isEqualTo("0.0000");
        assertThat(after).isEqualTo(before);
        assertThat(
                        currency(incomeAndSpending(household.owner(), AUGUST, AUGUST), "GBP")
                                .get("uncategorisedCount")
                                .asLong())
                .isZero();
    }

    @Test
    void aCrossCurrencyTransferChangesNeitherCurrencysReport() {
        UUID euros = account(household.owner(), "Euro savings", "EUR");
        createTransaction(household.owner(), current, "2026-08-01", "2000.00", salary);
        createTransaction(household.owner(), euros, "2026-08-03", "-20.00", groceries);
        JsonNode before = incomeAndSpending(household.owner(), AUGUST, AUGUST);

        transfer(household.owner(), current, euros, "2026-08-10", "200.00", "228.40");

        JsonNode after = incomeAndSpending(household.owner(), AUGUST, AUGUST);
        assertThat(currency(after, "GBP")).isEqualTo(currency(before, "GBP"));
        assertThat(currency(after, "EUR")).isEqualTo(currency(before, "EUR"));
    }

    // ------------------------------------------------------------------------ refunds and signs

    @Test
    void aRefundReducesSpendingInItsCategoryAndIsNotIncome() {
        createTransaction(household.owner(), current, "2026-08-02", "-120.00", groceries);
        createTransaction(household.owner(), current, "2026-08-05", "20.00", groceries);

        JsonNode month = august();

        assertThat(text(month, "spending")).isEqualTo("100.0000");
        assertThat(text(month, "income")).isEqualTo("0.0000");
        assertThat(text(month, "uncategorisedIn")).isEqualTo("0.0000");
    }

    @Test
    void aRefundLargerThanTheMonthsSpendingGivesNegativeSpendingNotZero() {
        createTransaction(household.owner(), current, "2026-08-02", "-30.00", groceries);
        createTransaction(household.owner(), current, "2026-08-05", "80.00", groceries);

        JsonNode month = august();

        assertThat(text(month, "spending")).isEqualTo("-50.0000");
        assertThat(text(month, "kept")).isEqualTo("50.0000");
    }

    @Test
    void aNegativeAmountInAnIncomeCategoryReducesIncome() {
        createTransaction(household.owner(), current, "2026-08-01", "2000.00", salary);
        createTransaction(household.owner(), current, "2026-08-20", "-150.00", salary);

        JsonNode month = august();

        assertThat(text(month, "income")).isEqualTo("1850.0000");
        assertThat(text(month, "spending")).isEqualTo("0.0000");
    }

    @Test
    void anUncategorisedInflowIsInNeitherIncomeNorSpendingButIsInKept() {
        createTransaction(household.owner(), current, "2026-08-01", "640.00", null);

        JsonNode month = august();

        assertThat(text(month, "uncategorisedIn")).isEqualTo("640.0000");
        assertThat(text(month, "income")).isEqualTo("0.0000");
        assertThat(text(month, "spending")).isEqualTo("0.0000");
        assertThat(text(month, "kept")).isEqualTo("640.0000");
    }

    @Test
    void anUncategorisedOutflowIsReportedPositiveAndCounted() {
        createTransaction(household.owner(), current, "2026-08-01", "-54.80", null);
        createTransaction(household.owner(), current, "2026-08-02", "10.00", null);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), AUGUST, AUGUST), "GBP");

        assertThat(text(month(gbp, AUGUST), "uncategorisedOut")).isEqualTo("54.8000");
        assertThat(gbp.get("uncategorisedCount").asLong()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------- identity

    @Test
    void keptIsExactlyIncomeMinusSpendingPlusUncategorisedInMinusOutAndThePlainSumOfEveryRow() {
        // given: a month of mixed rows, generated, with a fixed seed so a failure reproduces
        Random random = new Random(20261125L);
        UUID[] categories = {salary, groceries, seededCategoryId("Utilities"), null};
        BigDecimal plainSum = BigDecimal.ZERO;
        for (int i = 0; i < 40; i++) {
            BigDecimal amount = BigDecimal.valueOf(random.nextInt(2_000_000) - 1_000_000 + 1, 4);
            if (amount.signum() == 0) {
                continue;
            }
            UUID category = categories[random.nextInt(categories.length)];
            createTransaction(
                    household.owner(),
                    random.nextBoolean() ? current : savings,
                    "2026-08-" + String.format("%02d", 1 + random.nextInt(31)),
                    amount.toPlainString(),
                    category);
            plainSum = plainSum.add(amount);
        }
        transfer(household.owner(), current, savings, "2026-08-15", "333.33");

        // when
        JsonNode month = august();

        // then
        BigDecimal income = new BigDecimal(text(month, "income"));
        BigDecimal spending = new BigDecimal(text(month, "spending"));
        BigDecimal in = new BigDecimal(text(month, "uncategorisedIn"));
        BigDecimal out = new BigDecimal(text(month, "uncategorisedOut"));
        BigDecimal kept = new BigDecimal(text(month, "kept"));
        assertThat(income.subtract(spending).add(in).subtract(out)).isEqualByComparingTo(kept);
        assertThat(kept).as("kept is the ledger's net movement").isEqualByComparingTo(plainSum);
    }

    // ----------------------------------------------------------------- what counts and what not

    @Test
    void aPendingTransactionCountsInItsMonth() {
        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/transactions",
                                """
                                {"accountId":"%s","date":"2026-08-04","amount":"-42.00",
                                 "categoryId":"%s","status":"PENDING"}
                                """
                                        .formatted(current, groceries));
        assertThat(created.status()).isEqualTo(201);

        assertThat(text(august(), "spending")).isEqualTo("42.0000");
    }

    @Test
    void anOpeningBalanceIsNotAMovement() {
        // ApiTestBase.createAccount opens at 100.0000. A month of income it would be, if counted.
        createAccount(household.owner(), "Opened with money", null);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), AUGUST, AUGUST), "GBP");

        assertThat(text(month(gbp, AUGUST), "kept")).isEqualTo("0.0000");
        assertThat(gbp.get("transactionCount").asLong()).isZero();
    }

    @Test
    void envelopeEntriesPaidBillsAndDebtPaymentsChangeNothing() {
        createTransaction(household.owner(), current, "2026-08-02", "-60.00", groceries);
        JsonNode before = incomeAndSpending(household.owner(), AUGUST, AUGUST);

        // A MANUAL envelope entry: an amount logged against a plan, not a ledger row.
        ApiResponse envelope =
                household
                        .owner()
                        .post(
                                "/api/envelopes",
                                """
                                {"name":"Food","period":"%s","amount":"400.00","categoryId":"%s"}
                                """
                                        .formatted(AUGUST, groceries));
        assertThat(envelope.status()).isEqualTo(201);
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/envelopes/" + envelope.stringField("id") + "/entries",
                                        "{\"amount\":\"35.00\"}")
                                .status())
                .isEqualTo(201);

        // A bill occurrence marked PAID, with nothing matched to it.
        ApiResponse bill =
                household
                        .owner()
                        .post(
                                "/api/bills",
                                """
                                {"name":"Rent","expectedAmount":"1200.00",
                                 "schedule":{"kind":"MONTHLY","dayOfMonth":15}}
                                """);
        assertThat(bill.status()).isEqualTo(201);
        assertThat(
                        household
                                .owner()
                                .put(
                                        "/api/bills/"
                                                + bill.stringField("id")
                                                + "/occurrences/2026-08-15",
                                        "{\"status\":\"PAID\"}")
                                .status())
                .isEqualTo(200);

        // A debt payment: a plan record that writes no transaction (ADR-0035).
        UUID debt = createDebt(household.owner(), "Card", "900.00", "19.900", "25.00", null);
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/debts/" + debt + "/payments",
                                        "{\"paidDate\":\"2026-08-12\",\"amount\":\"80.00\"}")
                                .status())
                .isEqualTo(201);

        assertThat(incomeAndSpending(household.owner(), AUGUST, AUGUST)).isEqualTo(before);
    }

    @Test
    void twoRowsPairedIntoATransferLeaveEveryFigureAndCountAgainWhenSplit() {
        // Slice 12's "these two rows are one transfer" writes a shared transfer_group_id to two
        // ordinary rows. It is not built yet, so the pairing is written here the way it will be:
        // two uncategorised, payee-less rows of opposite sign on different accounts.
        UUID leaving = createTransaction(household.owner(), current, "2026-08-09", "-300.00", null);
        UUID arriving = createTransaction(household.owner(), savings, "2026-08-09", "300.00", null);
        createTransaction(household.owner(), current, "2026-08-01", "2000.00", salary);

        UUID group = UUID.randomUUID();
        jdbc.update(
                "UPDATE transactions SET transfer_group_id = ? WHERE id IN (?, ?)",
                group,
                leaving,
                arriving);
        JsonNode paired = august();
        assertThat(text(paired, "uncategorisedIn")).isEqualTo("0.0000");
        assertThat(text(paired, "uncategorisedOut")).isEqualTo("0.0000");
        assertThat(text(paired, "kept")).isEqualTo("2000.0000");

        jdbc.update(
                "UPDATE transactions SET transfer_group_id = NULL WHERE id IN (?, ?)",
                leaving,
                arriving);
        JsonNode split = august();
        assertThat(text(split, "uncategorisedIn")).isEqualTo("300.0000");
        assertThat(text(split, "uncategorisedOut")).isEqualTo("300.0000");
        assertThat(text(split, "kept")).isEqualTo("2000.0000");
    }

    // ------------------------------------------------------------------------------- months

    @Test
    void theFirstAndTheLastDayOfAMonthFallInThatMonth() {
        createTransaction(household.owner(), current, "2026-08-01", "-1.00", groceries);
        createTransaction(household.owner(), current, "2026-08-31", "-2.00", groceries);

        assertThat(text(august(), "spending")).isEqualTo("3.0000");
    }

    @Test
    void theThirtyFirstAndTheFollowingFirstFallInDifferentMonths() {
        createTransaction(household.owner(), current, "2026-07-31", "-1.00", groceries);
        createTransaction(household.owner(), current, "2026-08-01", "-2.00", groceries);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-07", AUGUST), "GBP");

        assertThat(text(month(gbp, "2026-07"), "spending")).isEqualTo("1.0000");
        assertThat(text(month(gbp, AUGUST), "spending")).isEqualTo("2.0000");
    }

    @Test
    void theTwentyNinthOfFebruaryInALeapYearIsFebruary() {
        createTransaction(household.owner(), current, "2028-02-29", "-5.00", groceries);
        createTransaction(household.owner(), current, "2028-03-01", "-7.00", groceries);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2028-02", "2028-03"), "GBP");

        assertThat(text(month(gbp, "2028-02"), "spending")).isEqualTo("5.0000");
        assertThat(text(month(gbp, "2028-03"), "spending")).isEqualTo("7.0000");
    }

    @Test
    void rowsOutsideTheRangeAreNotCounted() {
        createTransaction(household.owner(), current, "2026-07-31", "-1.00", groceries);
        createTransaction(household.owner(), current, "2026-09-01", "-2.00", groceries);

        assertThat(text(august(), "spending")).isEqualTo("0.0000");
    }

    @Test
    void everyMonthInTheRangeIsPresentWithZerosWhereNothingHappened() {
        createTransaction(household.owner(), current, "2026-03-10", "-9.00", groceries);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-01", "2026-06"), "GBP");

        List<String> periods = new ArrayList<>();
        gbp.get("months").forEach(month -> periods.add(month.get("period").asString()));
        assertThat(periods)
                .containsExactly("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06");
        assertThat(text(month(gbp, "2026-05"), "income")).isEqualTo("0.0000");
        assertThat(text(month(gbp, "2026-05"), "kept")).isEqualTo("0.0000");
    }

    // ----------------------------------------------------------------- complete months, average

    @Test
    void theCurrentMonthIsIncompleteAndTheAverageAndBestMonthIgnoreIt() {
        createTransaction(household.owner(), current, "2026-06-01", "100.00", salary);
        createTransaction(household.owner(), current, "2026-07-01", "300.00", salary);
        createTransaction(household.owner(), current, "2026-08-01", "9000.00", salary);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-06", AUGUST), "GBP");

        assertThat(month(gbp, "2026-07").get("complete").asBoolean()).isTrue();
        assertThat(month(gbp, AUGUST).get("complete").asBoolean()).isFalse();
        assertThat(text(gbp, "averageKept")).isEqualTo("200.0000");
        assertThat(text(gbp.get("bestMonth"), "period")).isEqualTo("2026-07");
        assertThat(text(gbp.get("bestMonth"), "kept")).isEqualTo("300.0000");
        assertThat(gbp.get("completeMonths").asInt()).isEqualTo(2);
    }

    @Test
    void aLaterMonthThanTheCurrentOneIsIncompleteToo() {
        createTransaction(household.owner(), current, "2026-09-03", "-12.00", groceries);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), AUGUST, "2026-09"), "GBP");

        assertThat(month(gbp, "2026-09").get("complete").asBoolean()).isFalse();
        assertThat(text(month(gbp, "2026-09"), "spending")).isEqualTo("12.0000");
    }

    @Test
    void aTieForBestMonthGoesToTheEarlierMonth() {
        createTransaction(household.owner(), current, "2026-05-01", "250.00", salary);
        createTransaction(household.owner(), current, "2026-06-01", "250.00", salary);
        createTransaction(household.owner(), current, "2026-07-01", "100.00", salary);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-05", "2026-07"), "GBP");

        assertThat(text(gbp.get("bestMonth"), "period")).isEqualTo("2026-05");
    }

    @Test
    void theBestMonthCanBeOneThatKeptLessThanNothing() {
        createTransaction(household.owner(), current, "2026-06-01", "-50.00", groceries);
        createTransaction(household.owner(), current, "2026-07-01", "-20.00", groceries);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-06", "2026-07"), "GBP");

        assertThat(text(gbp.get("bestMonth"), "kept")).isEqualTo("-20.0000");
        assertThat(text(gbp, "averageKept")).isEqualTo("-35.0000");
    }

    @Test
    void theAverageIsRoundedHalfEvenAtScaleFour() {
        // 1.0000 / 3 = 0.33333…, and 0.00005 sits exactly on a boundary for neither — so use
        // 0.0001 + 0.0002 over 2 months: 0.00015, which HALF_EVEN takes to 0.0002 and HALF_DOWN
        // would take to 0.0001.
        createTransaction(household.owner(), current, "2026-06-01", "0.0001", salary);
        createTransaction(household.owner(), current, "2026-07-01", "0.0002", salary);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), "2026-06", "2026-07"), "GBP");

        assertThat(text(gbp, "averageKept")).isEqualTo("0.0002");
    }

    @Test
    void withNoCompleteMonthTheAverageAndBestMonthAreNullNotZero() {
        createTransaction(household.owner(), current, "2026-08-01", "100.00", salary);
        readAt(IN_AUGUST_2026);

        JsonNode gbp = currency(incomeAndSpending(household.owner(), AUGUST, AUGUST), "GBP");

        assertThat(gbp.get("averageKept").isNull()).isTrue();
        assertThat(gbp.get("bestMonth").isNull()).isTrue();
        assertThat(gbp.get("completeMonths").asInt()).isZero();
    }

    @Test
    void theRangeTotalsAreSummedByTheServerIncludingTheMonthInProgress() {
        createTransaction(household.owner(), current, "2026-07-02", "-54.80", null);
        createTransaction(household.owner(), current, "2026-08-02", "-10.00", null);
        createTransaction(household.owner(), current, "2026-08-03", "25.00", null);
        createTransaction(household.owner(), current, "2026-07-01", "2000.00", salary);
        createTransaction(household.owner(), current, "2026-08-04", "-300.00", groceries);
        readAt(IN_AUGUST_2026);

        JsonNode totals =
                currency(incomeAndSpending(household.owner(), "2026-07", AUGUST), "GBP")
                        .get("totals");

        assertThat(text(totals, "income")).isEqualTo("2000.0000");
        assertThat(text(totals, "spending")).isEqualTo("300.0000");
        assertThat(text(totals, "uncategorisedIn")).isEqualTo("25.0000");
        assertThat(text(totals, "uncategorisedOut")).isEqualTo("64.8000");
        assertThat(text(totals, "kept")).isEqualTo("1660.2000");
    }

    // ------------------------------------------------------------------------------- the wire

    @Test
    void amountsKeepEveryDigitAndNoneIsAJsonNumber() {
        createTransaction(household.owner(), current, "2026-08-01", "123456789012345.6789", salary);
        createTransaction(household.owner(), current, "2026-08-02", "-0.0001", groceries);

        ApiResponse response =
                household.owner().get("/api/reports/income-and-spending?from=2026-08&to=2026-08");

        assertThat(response.body())
                .contains("\"income\":\"123456789012345.6789\"")
                .contains("\"spending\":\"0.0001\"")
                .contains("\"kept\":\"123456789012345.6788\"");
        assertThat(response.body())
                .as("no amount field is ever a bare JSON number")
                .doesNotContainPattern(
                        "\"(income|spending|uncategorisedIn|uncategorisedOut|kept|averageKept)\""
                                + "\\s*:\\s*-?[0-9]");
    }

    @Test
    void readsTheWholeRangeInOneReportStatementHoweverManyMonthsAndCategories() {
        // given: a small range, measured
        createTransaction(household.owner(), current, "2026-08-02", "-5.00", groceries);
        long small = statementsFor(() -> incomeAndSpending(household.owner(), AUGUST, AUGUST));

        // and a large one: 24 months, a dozen categories each, both directions
        List<UUID> categories = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            categories.add(createCategory(household.owner(), "Spend " + i, "EXPENSE", null));
        }
        LocalDate month = LocalDate.of(2024, 9, 1);
        for (int m = 0; m < 24; m++) {
            for (UUID category : categories) {
                createTransaction(
                        household.owner(),
                        current,
                        month.plusMonths(m).toString(),
                        "-1.00",
                        category);
            }
            createTransaction(
                    household.owner(), savings, month.plusMonths(m).toString(), "3.00", null);
        }

        long large =
                statementsFor(() -> incomeAndSpending(household.owner(), "2024-09", "2026-08"));

        assertThat(large)
                .as("no statement per month or per category: an N+1 would multiply this")
                .isEqualTo(small);
        assertThat(reportQueryExecutions).as("the report itself is one grouped query").isEqualTo(1);
    }

    /** How many queries the last {@link #statementsFor} ran that were the report scan itself. */
    private long reportQueryExecutions;

    private long statementsFor(Runnable read) {
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        read.run();
        long count = statistics.getPrepareStatementCount();
        reportQueryExecutions = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("MonthTotals")) {
                reportQueryExecutions += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        statistics.setStatisticsEnabled(false);
        return count;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private JsonNode august() {
        return month(currency(incomeAndSpending(household.owner(), AUGUST, AUGUST), "GBP"), AUGUST);
    }
}
