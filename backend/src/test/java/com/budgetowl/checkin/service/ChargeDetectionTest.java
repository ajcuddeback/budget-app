package com.budgetowl.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.checkin.service.ChargeDetection.DatedAmount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Steps 1 and 3's arithmetic: the feature doc's "Assumed" thresholds, each at its edge. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChargeDetectionTest {

    @Nested
    class Unusual {

        @Test
        void twice_the_median_is_unusual_and_a_cent_less_is_not() {
            BigDecimal median = ChargeDetection.median(amounts("40", "42", "44")).orElseThrow();

            assertThat(median).isEqualByComparingTo("42");
            assertThat(ChargeDetection.isUnusual(new BigDecimal("84.00"), median)).isTrue();
            assertThat(ChargeDetection.isUnusual(new BigDecimal("83.99"), median)).isFalse();
        }

        @Test
        void fewer_than_three_earlier_rows_is_not_a_history() {
            assertThat(ChargeDetection.median(amounts("42", "42"))).isEmpty();
            assertThat(ChargeDetection.median(amounts("42", "42", "42"))).isPresent();
        }

        @Test
        void an_even_history_takes_the_mean_of_its_two_middles() {
            assertThat(ChargeDetection.median(amounts("10", "20", "30", "50")).orElseThrow())
                    .isEqualByComparingTo("25");
        }

        @Test
        void the_median_is_order_independent() {
            assertThat(ChargeDetection.median(amounts("44", "40", "42")).orElseThrow())
                    .isEqualByComparingTo("42");
        }

        @Test
        void a_zero_reference_makes_nothing_unusual() {
            assertThat(ChargeDetection.isUnusual(new BigDecimal("100"), BigDecimal.ZERO)).isFalse();
        }
    }

    @Nested
    class Prepaid {

        @Test
        void the_canvas_gym_is_three_months_at_once() {
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("126"), new BigDecimal("42"), null))
                    .hasValue(3);
        }

        @Test
        void a_fixed_bill_needs_an_exact_multiple() {
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("126.01"), new BigDecimal("42"), null))
                    .isEmpty();
        }

        @Test
        void a_varying_bill_takes_its_tolerance() {
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("129.00"),
                                    new BigDecimal("42"),
                                    new BigDecimal("4")))
                    .hasValue(3);
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("130.01"),
                                    new BigDecimal("42"),
                                    new BigDecimal("4")))
                    .isEmpty();
        }

        @Test
        void twelve_is_the_cap_and_one_is_not_prepaid() {
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("504"), new BigDecimal("42"), null))
                    .hasValue(12);
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("546"), new BigDecimal("42"), null))
                    .isEmpty();
            assertThat(
                            ChargeDetection.prepaidMultiple(
                                    new BigDecimal("42"), new BigDecimal("42"), null))
                    .isEmpty();
        }

        @Test
        void a_bill_of_nothing_is_never_prepaid() {
            assertThat(ChargeDetection.prepaidMultiple(new BigDecimal("42"), BigDecimal.ZERO, null))
                    .isEmpty();
        }
    }

    @Nested
    class Tolerance {

        @Test
        void a_fixed_bill_matches_only_its_amount() {
            assertThat(
                            ChargeDetection.withinTolerance(
                                    new BigDecimal("71.99"), new BigDecimal("71.99"), null))
                    .isTrue();
            assertThat(
                            ChargeDetection.withinTolerance(
                                    new BigDecimal("72.00"), new BigDecimal("71.99"), null))
                    .isFalse();
        }

        @Test
        void a_varying_bill_matches_inside_its_band_inclusive() {
            BigDecimal tolerance = new BigDecimal("4");
            assertThat(
                            ChargeDetection.withinTolerance(
                                    new BigDecimal("75.99"), new BigDecimal("71.99"), tolerance))
                    .isTrue();
            assertThat(
                            ChargeDetection.withinTolerance(
                                    new BigDecimal("76.00"), new BigDecimal("71.99"), tolerance))
                    .isFalse();
        }
    }

    @Nested
    class Recurring {

        private final YearMonth august = YearMonth.of(2026, 8);
        private final LocalDate latest = LocalDate.of(2026, 8, 19);

        @Test
        void three_months_running_within_ten_percent_is_recurring() {
            assertThat(
                            ChargeDetection.isRecurring(
                                    latest,
                                    new BigDecimal("71.99"),
                                    august,
                                    List.of(
                                            row("2026-06-19", "71.99"),
                                            row("2026-07-19", "71.99"))))
                    .isTrue();
        }

        @Test
        void a_missing_month_is_not() {
            assertThat(
                            ChargeDetection.isRecurring(
                                    latest,
                                    new BigDecimal("71.99"),
                                    august,
                                    List.of(
                                            row("2026-05-19", "71.99"),
                                            row("2026-07-19", "71.99"))))
                    .isFalse();
        }

        @Test
        void the_ten_percent_band_is_inclusive_and_a_cent_outside_is_not() {
            BigDecimal latestAmount = new BigDecimal("100.00");
            assertThat(
                            ChargeDetection.isRecurring(
                                    latest,
                                    latestAmount,
                                    august,
                                    List.of(
                                            row("2026-06-01", "110.00"),
                                            row("2026-07-01", "90.00"))))
                    .isTrue();
            assertThat(
                            ChargeDetection.isRecurring(
                                    latest,
                                    latestAmount,
                                    august,
                                    List.of(
                                            row("2026-06-01", "110.01"),
                                            row("2026-07-01", "90.00"))))
                    .isFalse();
        }

        @Test
        void the_latest_must_fall_in_the_windows_end_month() {
            assertThat(
                            ChargeDetection.isRecurring(
                                    LocalDate.of(2026, 7, 31),
                                    new BigDecimal("71.99"),
                                    august,
                                    List.of(
                                            row("2026-06-19", "71.99"),
                                            row("2026-07-19", "71.99"))))
                    .isFalse();
        }

        private DatedAmount row(String date, String magnitude) {
            return new DatedAmount(LocalDate.parse(date), new BigDecimal(magnitude));
        }
    }

    private static List<BigDecimal> amounts(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
