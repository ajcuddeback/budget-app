package com.budgetowl.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import com.budgetowl.report.persistence.CategoryTotals;
import com.budgetowl.report.persistence.MonthTotals;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The service's arithmetic without a database: how rows become figures, and what it refuses. The
 * integration tests prove the same against real SQL; these make the rules cheap to break and cheap
 * to see broken.
 */
class ReportServiceTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final ReportRange JUST_AUGUST = new ReportRange(AUGUST, AUGUST);

    // ------------------------------------------------------------------- currencies (item 9)

    @Test
    void refusesToAddAMonthRowFromAnotherCurrency() {
        MonthTotals euros = month("EUR", "10", "-5", "0", "0", "5");

        assertThatThrownBy(
                        () ->
                                ReportService.figuresFor(
                                        GBP, JUST_AUGUST, Map.of(AUGUST, euros), AUGUST))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesToAddACategoryRowFromAnotherCurrencyIntoTheTree() {
        CategoryTotals euros = expense("EUR", UUID.randomUUID(), "Groceries", null, "-5", "0");

        assertThatThrownBy(() -> ReportService.spendingFor(GBP, List.of(euros)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesAnUncategorisedRowFromAnotherCurrency() {
        CategoryTotals euros =
                new CategoryTotals(
                        "EUR",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("-3"),
                        BigDecimal.ZERO,
                        new BigDecimal("-3"),
                        1L);

        assertThatThrownBy(() -> ReportService.spendingFor(GBP, List.of(euros)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    // --------------------------------------------------------------------------- report 1

    @Test
    void failsLoudlyWhenTheTermsDoNotReconcileWithThePlainSum() {
        // net should be 10 − 5 = 5; a row that reached the sum without reaching a term is a bug
        MonthTotals broken = month("GBP", "10", "-5", "0", "0", "6");

        assertThatThrownBy(
                        () ->
                                ReportService.figuresFor(
                                        GBP,
                                        JUST_AUGUST,
                                        Map.of(AUGUST, broken),
                                        AUGUST.plusMonths(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not reconcile");
    }

    @Test
    void turnsRawSignedSumsIntoTheFiguresThePageShows() {
        MonthTotals row = month("GBP", "2000", "-300", "40", "-25", "1715");

        IncomeAndSpendingReport.MonthFigures august =
                ReportService.figuresFor(
                                GBP, JUST_AUGUST, Map.of(AUGUST, row), AUGUST.plusMonths(1))
                        .months()
                        .get(0);

        assertThat(august.income()).isEqualTo(Money.of("2000", "GBP"));
        assertThat(august.spending()).isEqualTo(Money.of("300", "GBP"));
        assertThat(august.uncategorisedIn()).isEqualTo(Money.of("40", "GBP"));
        assertThat(august.uncategorisedOut()).isEqualTo(Money.of("25", "GBP"));
        assertThat(august.kept()).isEqualTo(Money.of("1715", "GBP"));
        assertThat(august.complete()).isTrue();
    }

    // --------------------------------------------------------------------------- report 2

    @Test
    void foldsChildrenIntoTheirParentAndKeepsDirectSeparate() {
        UUID bills = UUID.randomUUID();
        List<CategoryTotals> rows =
                List.of(
                        expense("GBP", bills, "Bills", null, "-10", "0"),
                        child("GBP", "Utilities", bills, "Bills", "-90", "-50"),
                        child("GBP", "Insurance", bills, "Bills", "0", "-25"));

        SpendingByCategoryReport.CurrencySpending gbp = ReportService.spendingFor(GBP, rows);

        SpendingByCategoryReport.CategorySpending row = gbp.categories().get(0);
        assertThat(row.spending()).isEqualTo(Money.of("100", "GBP"));
        assertThat(row.comparedSpending()).isEqualTo(Money.of("75", "GBP"));
        assertThat(row.direct()).isEqualTo(Money.of("10", "GBP"));
        assertThat(row.children())
                .extracting(SpendingByCategoryReport.CategorySpending::name)
                .containsExactly("Utilities", "Insurance");
        assertThat(row.change()).contains(new BigDecimal("0.3333"));
    }

    @Test
    void ignoresIncomeCategoriesButLetsTheirCurrencyExist() {
        CategoryTotals salary =
                new CategoryTotals(
                        "GBP",
                        UUID.randomUUID(),
                        "Salary",
                        false,
                        CategoryKind.INCOME,
                        null,
                        null,
                        null,
                        new BigDecimal("2000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0L);

        SpendingByCategoryReport.CurrencySpending gbp =
                ReportService.spendingFor(GBP, List.of(salary));

        assertThat(gbp.categories()).isEmpty();
        assertThat(gbp.spending()).isEqualTo(Money.zero(GBP));
    }

    @Test
    void changeIsAgainstTheMagnitudeOfWhatWasSpentBefore() {
        // A category whose comparison range was refund-heavy: −40 before, 20 now. The rise is 60
        // against a magnitude of 40, so +1.5 — dividing by the signed −40 would say it fell.
        assertThat(ReportService.change(Money.of("20", "GBP"), Money.of("-40", "GBP")))
                .contains(new BigDecimal("1.5000"));
        assertThat(ReportService.change(Money.of("20", "GBP"), Money.zero(GBP))).isEmpty();
        assertThat(ReportService.change(Money.of("20", "GBP"), Money.of("20", "GBP")))
                .contains(new BigDecimal("0.0000"));
    }

    @Test
    void shareIsEmptyForAZeroOrNegativeRowAndAgainstNothing() {
        assertThat(ReportService.share(Money.of("-5", "GBP"), Money.of("100", "GBP"))).isEmpty();
        assertThat(ReportService.share(Money.zero(GBP), Money.of("100", "GBP"))).isEmpty();
        assertThat(ReportService.share(Money.of("5", "GBP"), Money.zero(GBP))).isEmpty();
        assertThat(ReportService.share(Money.of("1", "GBP"), Money.of("3", "GBP")))
                .contains(new BigDecimal("0.3333"));
    }

    // -------------------------------------------------------------------------------- rows

    private static MonthTotals month(
            String currency, String income, String expense, String in, String out, String net) {
        return new MonthTotals(
                2026,
                8,
                currency,
                new BigDecimal(income),
                new BigDecimal(expense),
                new BigDecimal(in),
                new BigDecimal(out),
                new BigDecimal(net),
                0L,
                3L);
    }

    private static CategoryTotals expense(
            String currency, UUID id, String name, UUID parentId, String amount, String compared) {
        return new CategoryTotals(
                currency,
                id,
                name,
                false,
                CategoryKind.EXPENSE,
                parentId,
                null,
                null,
                new BigDecimal(amount),
                new BigDecimal(compared),
                BigDecimal.ZERO,
                0L);
    }

    private static CategoryTotals child(
            String currency,
            String name,
            UUID parentId,
            String parentName,
            String amount,
            String compared) {
        return new CategoryTotals(
                currency,
                UUID.randomUUID(),
                name,
                false,
                CategoryKind.EXPENSE,
                parentId,
                parentName,
                false,
                new BigDecimal(amount),
                new BigDecimal(compared),
                BigDecimal.ZERO,
                0L);
    }
}
