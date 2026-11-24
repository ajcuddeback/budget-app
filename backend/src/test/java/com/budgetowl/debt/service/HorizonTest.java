package com.budgetowl.debt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The horizon's two faces, and the window outside which neither of them means anything.
 *
 * <p>These exist because of a specific defect: {@code monthsLeft} was bounded above and below while
 * {@code payoffMonth} was bounded only from below, so a year wide enough to overflow the month
 * arithmetic reached {@code Math.toIntExact} and threw {@code ArithmeticException} — not a {@code
 * DomainException}, so it left the application as a {@code 500} with a stack trace where the
 * contract promises {@code 422}.
 *
 * <p>They are <b>unit</b> tests rather than API tests on purpose. Whether such a value gets past
 * the JSON deserializer is the deserializer's business and may change with a library version; what
 * must hold regardless is that no caller of {@link Horizon} can make it overflow.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HorizonTest {

    private static final YearMonth MARCH_2026 = YearMonth.of(2026, 3);

    @Test
    void the_first_payment_month_is_a_one_month_horizon_not_a_zero_month_one() {
        assertThat(Horizon.endingIn(MARCH_2026, MARCH_2026).monthsLeft()).isEqualTo(1);
        assertThat(Horizon.endingIn(MARCH_2026.plusMonths(11), MARCH_2026).monthsLeft())
                .isEqualTo(12);
    }

    @Test
    void the_two_faces_round_trip() {
        Horizon horizon = Horizon.ofMonths(37);
        assertThat(Horizon.endingIn(horizon.payoffMonth(MARCH_2026), MARCH_2026))
                .isEqualTo(horizon);
    }

    @Test
    void a_span_the_solver_could_answer_about_is_solvable() {
        assertThat(Horizon.isSolvableSpan(MARCH_2026, MARCH_2026)).isTrue();
        assertThat(
                        Horizon.isSolvableSpan(
                                MARCH_2026.plusMonths(DebtTermsSolver.MAX_MONTHS - 1L), MARCH_2026))
                .isTrue();
    }

    @Test
    void a_payoff_month_in_the_past_is_not_a_solvable_span() {
        assertThat(Horizon.isSolvableSpan(MARCH_2026.minusMonths(1), MARCH_2026)).isFalse();
    }

    @Test
    void a_payoff_month_past_the_month_cap_is_not_a_solvable_span() {
        assertThat(
                        Horizon.isSolvableSpan(
                                MARCH_2026.plusMonths(DebtTermsSolver.MAX_MONTHS), MARCH_2026))
                .isFalse();
    }

    @Test
    void a_year_wide_enough_to_overflow_the_month_arithmetic_is_not_a_solvable_span() {
        // About twelve billion months. ChronoUnit.MONTHS.between returns a long and the old code
        // narrowed it with Math.toIntExact, which is where the 500 came from.
        YearMonth absurd = YearMonth.of(999_999_999, 12);

        assertThat(Horizon.isSolvableSpan(absurd, MARCH_2026)).isFalse();
        assertThat(Horizon.isSolvableSpan(YearMonth.of(-999_999_999, 1), MARCH_2026)).isFalse();
    }

    @Test
    void endingIn_refuses_an_unsolvable_span_rather_than_overflowing() {
        YearMonth absurd = YearMonth.of(999_999_999, 12);

        // The point is the TYPE: IllegalArgumentException, which the service layer refuses before
        // ever reaching, rather than ArithmeticException, which nothing was looking for.
        assertThatThrownBy(() -> Horizon.endingIn(absurd, MARCH_2026))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Horizon.endingIn(MARCH_2026.minusMonths(1), MARCH_2026))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
