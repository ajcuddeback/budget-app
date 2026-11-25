package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Report 2: the category tree against the preceding range (docs/features/reporting.md, Report 2).
 */
class SpendingByCategoryIT extends ReportTestBase {

    private static final String AUGUST = "2026-08";
    private static final String JULY = "2026-07";

    @Autowired private EntityManagerFactory entityManagerFactory;

    private Household household;
    private UUID current;

    @BeforeEach
    void seedAHouseholdWithOneAccount() {
        household = seedHousehold();
        current = account(household.owner(), "Current", "GBP");
    }

    @AfterEach
    void stopCountingStatements() {
        statistics().setStatisticsEnabled(false);
    }

    // ------------------------------------------------------------------------------- the tree

    @Test
    void childrenPlusDirectEqualTheParentExactly() {
        UUID bills = seededCategoryId("Bills");
        spend(bills, AUGUST + "-01", "-10.0001");
        spend(seededCategoryId("Utilities"), AUGUST + "-02", "-120.3333");
        spend(seededCategoryId("Insurance"), AUGUST + "-03", "-45.6666");

        JsonNode row = category(gbp(AUGUST, AUGUST), "Bills");

        BigDecimal children = BigDecimal.ZERO;
        for (JsonNode child : row.get("children")) {
            children = children.add(new BigDecimal(text(child, "spending")));
        }
        assertThat(text(row, "direct")).isEqualTo("10.0001");
        assertThat(children.add(new BigDecimal(text(row, "direct"))))
                .isEqualByComparingTo(new BigDecimal(text(row, "spending")));
        assertThat(text(row, "spending")).isEqualTo("176.0000");
        assertThat(row.get("children")).hasSize(2);
    }

    @Test
    void aParentWithNoDirectSpendingStillAppearsWithItsChildren() {
        spend(seededCategoryId("Fuel"), AUGUST + "-05", "-60.00");

        JsonNode transport = category(gbp(AUGUST, AUGUST), "Transport");

        assertThat(text(transport, "direct")).isEqualTo("0.0000");
        assertThat(text(transport, "spending")).isEqualTo("60.0000");
        assertThat(text(transport.get("children").get(0), "name")).isEqualTo("Fuel");
    }

    @Test
    void onlyExpenseCategoriesAreInTheTree() {
        spend(seededCategoryId("Salary"), AUGUST + "-01", "2000.00");
        spend(seededCategoryId("Groceries"), AUGUST + "-02", "-50.00");

        JsonNode gbp = gbp(AUGUST, AUGUST);

        List<String> names = new ArrayList<>();
        gbp.get("categories").forEach(row -> names.add(row.get("name").asString()));
        assertThat(names).containsExactly("Groceries");
        assertThat(text(gbp, "spending")).isEqualTo("50.0000");
    }

    // ------------------------------------------------------------------------- the comparison

    @Test
    void oneMonthIsComparedWithTheMonthBefore() {
        UUID groceries = seededCategoryId("Groceries");
        spend(groceries, "2026-06-30", "-999.00");
        spend(groceries, JULY + "-15", "-527.60");
        spend(groceries, AUGUST + "-15", "-612.00");

        JsonNode report = spendingByCategory(household.owner(), AUGUST, AUGUST);
        JsonNode row = category(currency(report, "GBP"), "Groceries");

        assertThat(text(report, "comparedFrom")).isEqualTo(JULY);
        assertThat(text(report, "comparedTo")).isEqualTo(JULY);
        assertThat(text(row, "spending")).isEqualTo("612.0000");
        assertThat(text(row, "comparedSpending")).isEqualTo("527.6000");
        // (612 − 527.6) ÷ 527.6 = 0.15996…, HALF_EVEN at four places.
        assertThat(text(row, "change")).isEqualTo("0.1600");
    }

    @Test
    void threeMonthsAreComparedWithTheThreeMonthsBefore() {
        UUID groceries = seededCategoryId("Groceries");
        spend(groceries, "2026-02-28", "-1000.00");
        spend(groceries, "2026-03-01", "-100.00");
        spend(groceries, "2026-05-31", "-200.00");
        spend(groceries, "2026-06-01", "-30.00");
        spend(groceries, "2026-08-31", "-60.00");

        JsonNode report = spendingByCategory(household.owner(), "2026-06", AUGUST);
        JsonNode row = category(currency(report, "GBP"), "Groceries");

        assertThat(text(report, "comparedFrom")).isEqualTo("2026-03");
        assertThat(text(report, "comparedTo")).isEqualTo("2026-05");
        assertThat(text(row, "spending")).isEqualTo("90.0000");
        assertThat(text(row, "comparedSpending")).isEqualTo("300.0000");
        assertThat(text(row, "change")).isEqualTo("-0.7000");
    }

    @Test
    void changeIsNullAgainstNothing() {
        spend(seededCategoryId("Groceries"), AUGUST + "-15", "-40.00");

        JsonNode row = category(gbp(AUGUST, AUGUST), "Groceries");

        assertThat(row.get("change").isNull()).isTrue();
    }

    @Test
    void changeIsRoundedHalfEvenAtScaleFour() {
        // (8.0012 − 8) ÷ 8 = 0.00015 exactly: HALF_EVEN gives 0.0002, HALF_DOWN would give 0.0001.
        UUID groceries = seededCategoryId("Groceries");
        spend(groceries, JULY + "-15", "-8.0000");
        spend(groceries, AUGUST + "-15", "-8.0012");

        assertThat(text(category(gbp(AUGUST, AUGUST), "Groceries"), "change")).isEqualTo("0.0002");
    }

    @Test
    void aCategoryThatSpentNothingThisRangeIsShownAgainstWhatItSpentBefore() {
        spend(seededCategoryId("Eating Out"), JULY + "-12", "-381.00");

        JsonNode row = category(gbp(AUGUST, AUGUST), "Eating Out");

        assertThat(text(row, "spending")).isEqualTo("0.0000");
        assertThat(text(row, "comparedSpending")).isEqualTo("381.0000");
        assertThat(text(row, "change")).isEqualTo("-1.0000");
        assertThat(row.get("share").isNull()).isTrue();
    }

    @Test
    void aCategoryThatNetsToNothingInBothRangesIsLeftOut() {
        UUID shopping = seededCategoryId("Shopping");
        spend(shopping, AUGUST + "-02", "-25.00");
        spend(shopping, AUGUST + "-09", "25.00");

        assertThat(gbp(AUGUST, AUGUST).get("categories")).isEmpty();
    }

    // ---------------------------------------------------------------------- refunds and share

    @Test
    void aRefundHeavyCategoryIsNegativeAndHasNoShare() {
        UUID shopping = seededCategoryId("Shopping");
        spend(shopping, AUGUST + "-02", "-20.00");
        spend(shopping, AUGUST + "-09", "70.00");
        spend(seededCategoryId("Groceries"), AUGUST + "-03", "-300.00");

        JsonNode gbp = gbp(AUGUST, AUGUST);

        assertThat(text(category(gbp, "Shopping"), "spending")).isEqualTo("-50.0000");
        assertThat(category(gbp, "Shopping").get("share").isNull()).isTrue();
        assertThat(text(category(gbp, "Groceries"), "share")).isEqualTo("1.0000");
        assertThat(text(gbp, "spending")).isEqualTo("250.0000");
    }

    @Test
    void shareIsTheRowsFractionOfThePositiveSpending() {
        spend(seededCategoryId("Groceries"), AUGUST + "-03", "-300.00");
        spend(seededCategoryId("Home"), AUGUST + "-04", "-100.00");
        spend(seededCategoryId("Health"), AUGUST + "-05", "-200.00");

        JsonNode gbp = gbp(AUGUST, AUGUST);

        assertThat(text(category(gbp, "Groceries"), "share")).isEqualTo("0.5000");
        assertThat(text(category(gbp, "Health"), "share")).isEqualTo("0.3333");
        assertThat(text(category(gbp, "Home"), "share")).isEqualTo("0.1667");
    }

    // ----------------------------------------------------------------------- today's tree

    @Test
    void anArchivedCategoryIsReportedAndSaysSo() {
        UUID groceries = seededCategoryId("Groceries");
        spend(groceries, AUGUST + "-03", "-30.00");
        assertThat(
                        household
                                .owner()
                                .post("/api/categories/" + groceries + "/archive", null)
                                .status())
                .isEqualTo(200);

        JsonNode row = category(gbp(AUGUST, AUGUST), "Groceries");

        assertThat(row.get("archived").asBoolean()).isTrue();
        assertThat(text(row, "spending")).isEqualTo("30.0000");
    }

    @Test
    void afterACategoryIsDeletedItsHistoryIsUncategorised() {
        UUID doomed = createCategory(household.owner(), "Hobbies", "EXPENSE", null);
        spend(doomed, JULY + "-03", "-12.00");
        spend(doomed, AUGUST + "-03", "-30.00");
        assertThat(household.owner().delete("/api/categories/" + doomed).status()).isEqualTo(204);

        JsonNode gbp = gbp(AUGUST, AUGUST);

        assertThat(gbp.get("categories")).isEmpty();
        assertThat(text(gbp.get("uncategorised"), "spending")).isEqualTo("30.0000");
        assertThat(gbp.get("uncategorised").get("count").asLong()).isEqualTo(1);
    }

    @Test
    void afterACategoryMovesItsHistoryFollowsItToTheNewParent() {
        UUID hobbies = createCategory(household.owner(), "Hobbies", "EXPENSE", null);
        spend(hobbies, JULY + "-03", "-12.00");
        spend(hobbies, AUGUST + "-03", "-30.00");
        UUID entertainment = seededCategoryId("Entertainment");
        assertThat(
                        household
                                .owner()
                                .patch(
                                        "/api/categories/" + hobbies,
                                        "{\"version\":0,\"parentId\":\"" + entertainment + "\"}")
                                .status())
                .isEqualTo(200);

        JsonNode row = category(gbp(AUGUST, AUGUST), "Entertainment");

        assertThat(text(row, "spending")).isEqualTo("30.0000");
        assertThat(text(row, "comparedSpending")).isEqualTo("12.0000");
        assertThat(text(row.get("children").get(0), "name")).isEqualTo("Hobbies");
    }

    // --------------------------------------------------------------------------- ordering

    @Test
    void rowsAreOrderedBySpendingThenNameThenId() {
        spend(seededCategoryId("Home"), AUGUST + "-03", "-50.00");
        spend(seededCategoryId("Health"), AUGUST + "-03", "-50.00");
        spend(seededCategoryId("Groceries"), AUGUST + "-03", "-80.00");
        spend(seededCategoryId("Shopping"), AUGUST + "-03", "-10.00");

        List<String> names = new ArrayList<>();
        gbp(AUGUST, AUGUST).get("categories").forEach(row -> names.add(row.get("name").asString()));

        assertThat(names).containsExactly("Groceries", "Health", "Home", "Shopping");
    }

    // ---------------------------------------------------------------------- uncategorised

    @Test
    void theUncategorisedOutflowIsBesideTheTreeAndExcludesInflowsAndTransfers() {
        UUID savings = account(household.owner(), "Savings", "GBP");
        spend(null, AUGUST + "-03", "-54.80");
        spend(null, AUGUST + "-04", "200.00");
        transfer(household.owner(), current, savings, AUGUST + "-05", "500.00");

        JsonNode uncategorised = gbp(AUGUST, AUGUST).get("uncategorised");

        assertThat(text(uncategorised, "spending")).isEqualTo("54.8000");
        assertThat(uncategorised.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void transfersAreInNoRow() {
        UUID savings = account(household.owner(), "Savings", "GBP");
        transfer(household.owner(), current, savings, AUGUST + "-05", "500.00");

        JsonNode gbp = gbp(AUGUST, AUGUST);

        assertThat(gbp.get("categories")).isEmpty();
        assertThat(text(gbp, "spending")).isEqualTo("0.0000");
        assertThat(text(gbp.get("uncategorised"), "spending")).isEqualTo("0.0000");
    }

    // ------------------------------------------------------------------------------ misc

    @Test
    void theRangeIsIncompleteWhenItReachesTheCurrentMonth() {
        readAt(IN_AUGUST_2026);

        assertThat(
                        spendingByCategory(household.owner(), AUGUST, AUGUST)
                                .get("complete")
                                .asBoolean())
                .isFalse();
        assertThat(spendingByCategory(household.owner(), JULY, JULY).get("complete").asBoolean())
                .isTrue();
    }

    @Test
    void readsBothRangesInOneReportStatementHoweverManyCategories() {
        spend(seededCategoryId("Groceries"), AUGUST + "-02", "-5.00");
        long small = statementsFor(() -> spendingByCategory(household.owner(), AUGUST, AUGUST));
        long smallReportQueries = reportQueryExecutions;

        for (int i = 0; i < 12; i++) {
            UUID parent = createCategory(household.owner(), "Parent " + i, "EXPENSE", null);
            UUID child = createCategory(household.owner(), "Child " + i, "EXPENSE", parent);
            for (int m = 1; m <= 24; m++) {
                String date = "20%02d-%02d-10".formatted(24 + (m + 8) / 12, 1 + (m + 8) % 12);
                spend(parent, date, "-1.00");
                spend(child, date, "-2.00");
            }
        }

        long large = statementsFor(() -> spendingByCategory(household.owner(), "2025-09", AUGUST));

        assertThat(large)
                .as("no statement per category or per child: an N+1 would multiply this")
                .isEqualTo(small);
        assertThat(smallReportQueries).isEqualTo(1);
        assertThat(reportQueryExecutions)
                .as("both ranges and the whole tree in one grouped query")
                .isEqualTo(1);
    }

    private long reportQueryExecutions;

    private long statementsFor(Runnable read) {
        Statistics statistics = statistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        read.run();
        long count = statistics.getPrepareStatementCount();
        reportQueryExecutions = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("CategoryTotals")) {
                reportQueryExecutions += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        statistics.setStatisticsEnabled(false);
        return count;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void spend(UUID category, String date, String amount) {
        createTransaction(household.owner(), current, date, amount, category);
    }

    private JsonNode gbp(String from, String to) {
        return currency(spendingByCategory(household.owner(), from, to), "GBP");
    }
}
