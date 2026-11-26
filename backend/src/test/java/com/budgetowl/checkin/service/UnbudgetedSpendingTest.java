package com.budgetowl.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.SpendingGroup;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Decision 2, one exclusion per test: a test that "spending returns a number" passes with an
 * exclusion missing. The filters the query applies (transfer, currency, attachment) are proved
 * separately in {@code UnbudgetedSpendingIT}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class UnbudgetedSpendingTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 19);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    private static final UUID DINING = UUID.randomUUID();
    private static final UUID SALARY = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final UUID GROCERIES_CHILD = UUID.randomUUID();
    private static final UUID RENT = UUID.randomUUID();

    private final UnbudgetedSpending rule =
            new UnbudgetedSpending(
                    GBP,
                    Map.of(
                            DINING, category(DINING, CategoryKind.EXPENSE, null),
                            SALARY, category(SALARY, CategoryKind.INCOME, null),
                            GROCERIES, category(GROCERIES, CategoryKind.EXPENSE, null),
                            GROCERIES_CHILD,
                                    category(GROCERIES_CHILD, CategoryKind.EXPENSE, GROCERIES),
                            RENT, category(RENT, CategoryKind.EXPENSE, null)),
                    Map.of(AUGUST, Set.of(GROCERIES, RENT)));

    @Test
    void an_expense_outflow_counts() {
        assertThat(rule.of(DAY, DINING, new BigDecimal("-18.50"))).isEqualByComparingTo("18.50");
    }

    @Test
    void a_refund_in_an_expense_category_nets() {
        assertThat(total(group(DINING, "-50.00", "20.00"))).isEqualTo(gbp("30.00"));
    }

    @Test
    void refunds_beyond_the_spending_read_negative_and_are_not_clamped() {
        assertThat(total(group(DINING, "-10.00", "25.00"))).isEqualTo(gbp("-15.00"));
    }

    @Test
    void an_uncategorised_outflow_counts() {
        assertThat(rule.of(DAY, null, new BigDecimal("-94.10"))).isEqualByComparingTo("94.10");
    }

    @Test
    void an_uncategorised_deposit_does_not_net() {
        assertThat(rule.of(DAY, null, new BigDecimal("2620.00"))).isEqualByComparingTo("0");
    }

    @Test
    void an_income_category_is_not_spending() {
        assertThat(rule.of(DAY, SALARY, new BigDecimal("-5.00"))).isEqualByComparingTo("0");
    }

    @Test
    void an_envelope_or_bill_category_of_the_month_is_already_in_left() {
        assertThat(rule.of(DAY, GROCERIES, new BigDecimal("-94.10"))).isEqualByComparingTo("0");
        assertThat(rule.of(DAY, RENT, new BigDecimal("-1650.00"))).isEqualByComparingTo("0");
    }

    @Test
    void a_child_of_a_budgeted_category_is_already_in_left_too() {
        assertThat(rule.of(DAY, GROCERIES_CHILD, new BigDecimal("-12.00")))
                .isEqualByComparingTo("0");
    }

    @Test
    void a_category_budgeted_in_another_month_counts_in_this_one() {
        assertThat(rule.of(LocalDate.of(2026, 9, 1), GROCERIES, new BigDecimal("-12.00")))
                .isEqualByComparingTo("12.00");
    }

    @Test
    void an_unknown_category_counts_nothing() {
        assertThat(rule.of(DAY, UUID.randomUUID(), new BigDecimal("-12.00")))
                .isEqualByComparingTo("0");
    }

    @Test
    void the_range_bounds_are_inclusive_and_an_empty_range_is_zero() {
        List<SpendingGroup> groups =
                List.of(
                        new SpendingGroup(DAY, DINING, new BigDecimal("-1.00"), BigDecimal.ZERO),
                        new SpendingGroup(
                                DAY.plusDays(1), DINING, new BigDecimal("-2.00"), BigDecimal.ZERO));

        assertThat(rule.between(groups, DAY, DAY)).isEqualTo(gbp("1.00"));
        assertThat(rule.between(groups, DAY, DAY.plusDays(1))).isEqualTo(gbp("3.00"));
        assertThat(rule.between(groups, DAY.plusDays(1), DAY)).isEqualTo(gbp("0"));
    }

    @Test
    void an_answer_filing_a_row_under_groceries_takes_it_out() {
        Money adjusted =
                rule.adjusted(
                        gbp("94.10"),
                        DAY,
                        DAY,
                        DAY,
                        new BigDecimal("-94.10"),
                        null,
                        GROCERIES,
                        false,
                        true);

        assertThat(adjusted).isEqualTo(gbp("0"));
    }

    @Test
    void an_answer_attaching_a_row_takes_it_out_whatever_its_category() {
        Money adjusted =
                rule.adjusted(
                        gbp("18.50"),
                        DAY,
                        DAY,
                        DAY,
                        new BigDecimal("-18.50"),
                        DINING,
                        DINING,
                        true,
                        true);

        assertThat(adjusted).isEqualTo(gbp("0"));
    }

    @Test
    void a_row_the_groups_did_not_count_is_not_adjusted() {
        Money adjusted =
                rule.adjusted(
                        gbp("10.00"),
                        DAY,
                        DAY,
                        DAY,
                        new BigDecimal("-18.50"),
                        null,
                        DINING,
                        false,
                        false);

        assertThat(adjusted).isEqualTo(gbp("10.00"));
    }

    @Test
    void a_row_outside_the_range_is_not_adjusted() {
        Money adjusted =
                rule.adjusted(
                        gbp("10.00"),
                        DAY,
                        DAY,
                        DAY.plusDays(1),
                        new BigDecimal("-18.50"),
                        null,
                        GROCERIES,
                        false,
                        true);

        assertThat(adjusted).isEqualTo(gbp("10.00"));
    }

    private Money total(SpendingGroup group) {
        return rule.between(List.of(group), DAY, DAY);
    }

    private static SpendingGroup group(UUID category, String outflow, String inflow) {
        return new SpendingGroup(DAY, category, new BigDecimal(outflow), new BigDecimal(inflow));
    }

    private static CategoryRow category(UUID id, CategoryKind kind, UUID parent) {
        return new CategoryRow(id, "c", kind, parent, false);
    }

    private static Money gbp(String amount) {
        return Money.of(amount, "GBP");
    }
}
