package com.budgetowl.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.checkin.service.WeeklyFigure.MonthTerms;
import com.budgetowl.checkin.service.WeeklyFigure.Result;
import com.budgetowl.common.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** Next week's figure from the plan (ADR-0040, decision 1). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WeeklyFigureTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Test
    void the_canvas_week_is_480_and_543_rolled_over() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "1529.01", "917.44", "0")),
                        usd("63.00"));

        assertThat(result.free()).isEqualTo(usd("611.57"));
        assertThat(result.held()).isEqualTo(usd("63.00"));
        assertThat(result.base()).isEqualTo(usd("480.00"));
        assertThat(result.rolledOver()).isEqualTo(usd("543.00"));
        assertThat(result.basePerDay()).isEqualTo(usd("68.57"));
        assertThat(result.rolledPerDay()).isEqualTo(usd("77.57"));
        assertThat(result.surplusAllocatable()).isTrue();
    }

    @Test
    void rollover_adds_exactly_the_surplus_and_nothing_else() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 3),
                        List.of(terms(AUGUST, "3000.00", "100.00", "0")),
                        usd("12.34"));

        assertThat(result.rolledOver()).isEqualTo(result.base().plus(usd("12.34")));
    }

    @Test
    void the_first_week_has_no_surplus_so_nothing_is_held_or_rolled() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 17),
                        List.of(terms(AUGUST, "1529.01", "350.44", "0")),
                        null);

        assertThat(result.held()).isEqualTo(usd("0"));
        assertThat(result.rolledOver()).isNull();
        assertThat(result.base()).as("7 × 1,178.57 ÷ 15").isEqualTo(usd("550.00"));
    }

    @Test
    void an_overspent_week_holds_nothing_and_lowers_the_figure_by_construction() {
        Result under =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "1529.01", "917.44", "0")),
                        usd("-40.00"));
        Result more =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "1529.01", "957.44", "0")),
                        usd("-80.00"));

        assertThat(under.held()).isEqualTo(usd("0"));
        assertThat(under.rolledOver()).isNull();
        assertThat(under.surplusAllocatable()).isFalse();
        assertThat(more.base()).isLessThan(under.base());
    }

    @Test
    void a_surplus_of_exactly_zero_holds_nothing() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "1000.00", "200.00", "0")),
                        usd("0.00"));

        assertThat(result.held()).isEqualTo(usd("0"));
        assertThat(result.rolledOver()).isNull();
        assertThat(result.surplusAllocatable()).isFalse();
    }

    @Test
    void when_the_month_cannot_cover_the_surplus_only_roll_over_is_offered() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "100.00", "80.00", "0")),
                        usd("63.00"));

        assertThat(result.free()).isEqualTo(usd("20.00"));
        assertThat(result.surplusAllocatable()).isFalse();
        assertThat(result.held()).isEqualTo(usd("0"));
        assertThat(result.rolledOver()).isEqualTo(result.base().plus(usd("63.00")));
    }

    @Test
    void check_in_debt_payments_come_off_free() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "1529.01", "917.44", "100.00")),
                        null);

        assertThat(result.free()).isEqualTo(usd("511.57"));
    }

    @Test
    void a_week_crossing_a_month_end_takes_each_day_from_its_own_month() {
        // Monday 31 August: one August day from August's free money over the one day left, six
        // September days from September's whole plan over its thirty days.
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 31),
                        List.of(
                                terms(AUGUST, "500.00", "430.00", "0"),
                                terms(SEPTEMBER, "3000.00", "0", "0")),
                        null);

        assertThat(result.base()).as("70 + 6 × 3,000 ÷ 30").isEqualTo(usd("670.00"));
    }

    @Test
    void a_week_wholly_in_the_next_month_is_that_months_plan_spread_over_its_days_left() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 9, 7),
                        List.of(terms(SEPTEMBER, "3000.00", "0", "0")),
                        null);

        assertThat(result.base()).as("7 × 3,000 ÷ 24 days from the 7th").isEqualTo(usd("875.00"));
    }

    @Test
    void a_negative_figure_is_shown_negative_and_never_clamped() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(terms(AUGUST, "-200.00", "0", "0")),
                        null);

        assertThat(result.base()).isEqualTo(usd("-175.00"));
        assertThat(result.basePerDay())
                .as("rounded down, toward minus infinity")
                .isEqualTo(usd("-25.00"));
    }

    @Test
    void per_day_rounds_down_at_the_minor_unit_for_usd_and_jpy() {
        assertThat(WeeklyFigure.perDay(usd("480.00"), 2)).isEqualTo(usd("68.57"));
        assertThat(WeeklyFigure.perDay(Money.of("48000", "JPY"), 0))
                .isEqualTo(Money.of("6857", "JPY"));
        assertThat(WeeklyFigure.minorUnit(JPY)).isZero();
        assertThat(WeeklyFigure.minorUnit(USD)).isEqualTo(2);
    }

    @Test
    void a_jpy_base_has_no_decimals() {
        Result result =
                WeeklyFigure.of(
                        LocalDate.of(2026, 8, 24),
                        List.of(
                                new MonthTerms(
                                        AUGUST,
                                        Money.of("100001", "JPY"),
                                        Money.of("0", "JPY"),
                                        Money.of("0", "JPY"))),
                        null);

        assertThat(result.base().amount().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
    }

    @Test
    void the_first_month_must_be_the_one_next_week_starts_in() {
        assertThatThrownBy(
                        () ->
                                WeeklyFigure.of(
                                        LocalDate.of(2026, 8, 24),
                                        List.of(terms(SEPTEMBER, "1", "0", "0")),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MonthTerms terms(YearMonth month, String left, String spent, String paid) {
        return new MonthTerms(month, usd(left), usd(spent), usd(paid));
    }

    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }
}
