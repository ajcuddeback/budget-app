package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Each currency is a complete report of its own, and nothing is ever added across currencies
 * (ADR-0022, ADR-0039).
 */
class ReportCurrencyIT extends ReportTestBase {

    private static final String AUGUST = "2026-08";

    private Household household;
    private UUID pounds;
    private UUID euros;
    private UUID dollars;

    @BeforeEach
    void seedAHouseholdWithThreeCurrencies() {
        household = seedHousehold();
        pounds = account(household.owner(), "Current", "GBP");
        euros = account(household.owner(), "Berlin", "EUR");
        dollars = account(household.owner(), "Boston", "USD");
    }

    @Test
    void twoCurrenciesGiveTwoCompleteReportsAndNoCombinedFigure() {
        createTransaction(household.owner(), pounds, "2026-08-01", "100.00", salary());
        createTransaction(household.owner(), euros, "2026-08-01", "100.00", salary());

        JsonNode report = incomeAndSpending(household.owner(), AUGUST, AUGUST);

        assertThat(codes(report)).containsExactly("GBP", "EUR");
        assertThat(text(month(currency(report, "GBP"), AUGUST), "income")).isEqualTo("100.0000");
        assertThat(text(month(currency(report, "EUR"), AUGUST), "income")).isEqualTo("100.0000");
        assertThat(report.toString())
                .as("200 of nothing is never computed")
                .doesNotContain("200.0000");
    }

    @Test
    void theBaseCurrencyComesFirstThenTheOthersInIsoCodeOrder() {
        createTransaction(household.owner(), dollars, "2026-08-01", "-1.00", groceries());
        createTransaction(household.owner(), euros, "2026-08-01", "-1.00", groceries());
        createTransaction(household.owner(), pounds, "2026-08-01", "-1.00", groceries());

        assertThat(codes(incomeAndSpending(household.owner(), AUGUST, AUGUST)))
                .containsExactly("GBP", "EUR", "USD");
        assertThat(codes(spendingByCategory(household.owner(), AUGUST, AUGUST)))
                .containsExactly("GBP", "EUR", "USD");
    }

    @Test
    void theBaseCurrencyIsPresentEvenWithNoRowsAtAll() {
        createTransaction(household.owner(), euros, "2026-08-01", "-1.00", groceries());

        JsonNode report = incomeAndSpending(household.owner(), AUGUST, AUGUST);
        JsonNode byCategory = spendingByCategory(household.owner(), AUGUST, AUGUST);

        assertThat(codes(report)).containsExactly("GBP", "EUR");
        assertThat(currency(report, "GBP").get("transactionCount").asLong()).isZero();
        assertThat(text(month(currency(report, "GBP"), AUGUST), "kept")).isEqualTo("0.0000");
        assertThat(codes(byCategory)).containsExactly("GBP", "EUR");
        assertThat(currency(byCategory, "GBP").get("categories")).isEmpty();
        assertThat(text(report, "baseCurrency")).isEqualTo("GBP");
    }

    @Test
    void anotherCurrencyAppearsOnlyWhenItHasARowInTheRange() {
        createTransaction(household.owner(), euros, "2026-06-01", "-1.00", groceries());

        assertThat(codes(incomeAndSpending(household.owner(), AUGUST, AUGUST)))
                .containsExactly("GBP");
    }

    @Test
    void anIncomeOnlyCurrencyStillAppearsInSpendingByCategory() {
        createTransaction(household.owner(), euros, "2026-08-01", "500.00", salary());

        JsonNode byCategory = spendingByCategory(household.owner(), AUGUST, AUGUST);

        assertThat(codes(byCategory)).containsExactly("GBP", "EUR");
        assertThat(text(currency(byCategory, "EUR"), "spending")).isEqualTo("0.0000");
    }

    @Test
    void everyAmountSitsInsideAnObjectThatNamesItsCurrency() {
        createTransaction(household.owner(), euros, "2026-08-01", "-7.00", groceries());

        JsonNode euro = currency(spendingByCategory(household.owner(), AUGUST, AUGUST), "EUR");

        assertThat(text(euro, "currency")).isEqualTo("EUR");
        assertThat(text(category(euro, "Groceries"), "spending")).isEqualTo("7.0000");
    }

    private UUID salary() {
        return seededCategoryId("Salary");
    }

    private UUID groceries() {
        return seededCategoryId("Groceries");
    }

    private static List<String> codes(JsonNode report) {
        List<String> codes = new ArrayList<>();
        report.get("currencies").forEach(c -> codes.add(c.get("currency").asString()));
        return codes;
    }
}
