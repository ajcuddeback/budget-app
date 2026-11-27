package com.budgetowl.debt.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.common.Money;
import com.budgetowl.debt.domain.PaymentProvenance;
import com.budgetowl.debt.persistence.MonthInterestRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Interest is charged once per debt per calendar month (ADR-0043, docs/features/debt-plan.md, The
 * split). No Spring, no database: the rule as a unit, over the rows the service would hand it.
 *
 * <p>The canvas Visa throughout: $6,180.00 at 22.9%, whose month of interest is {@code 6180 × 22.9
 * ÷ 1200 = 117.935 → $117.94}.
 */
class MonthInterestTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final UUID VISA = new UUID(0L, 1L);
    private static final UUID CAR = new UUID(0L, 2L);
    private static final BigDecimal APR = new BigDecimal("22.900");
    private static final Money BALANCE = money("6180.00");
    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);

    private final DebtTermsSolver solver = new DebtTermsSolver();

    @Test
    void the_first_payment_in_a_month_carries_the_months_interest() {
        MonthInterest.Decision decision = decide(AUG_20, BALANCE, List.of());

        assertThat(decision.interest()).isEqualTo(money("117.94"));
        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.CARRIED);
        assertThat(decision.month()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(decision.booksTheMonth()).isTrue();
    }

    @Test
    void a_second_payment_in_the_same_month_is_all_principal() {
        MonthInterest.Decision decision =
                decide(
                        LocalDate.of(2026, 8, 28),
                        money("5852.94"),
                        List.of(row(VISA, AUG_20, "117.94", PaymentProvenance.USER_LOGGED)));

        assertThat(decision.interest()).isEqualTo(money("0.00"));
        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.ALREADY_CARRIED);
        assertThat(decision.booksTheMonth()).isFalse();
    }

    @Test
    void a_payment_back_dated_before_the_one_that_carried_the_interest_is_all_principal() {
        MonthInterest.Decision decision =
                decide(
                        LocalDate.of(2026, 8, 3),
                        BALANCE,
                        List.of(row(VISA, AUG_20, "117.94", PaymentProvenance.USER_LOGGED)));

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.ALREADY_CARRIED);
        assertThat(decision.interest().isZero()).isTrue();
    }

    @Test
    void a_payment_in_another_month_carries_that_months_interest() {
        MonthInterest.Decision decision =
                decide(
                        LocalDate.of(2026, 9, 1),
                        BALANCE,
                        List.of(row(VISA, AUG_20, "117.94", PaymentProvenance.USER_LOGGED)));

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.CARRIED);
        assertThat(decision.interest()).isEqualTo(money("117.94"));
    }

    @Test
    void another_debts_interest_in_the_same_month_books_nothing_for_this_one() {
        MonthInterest.Decision decision =
                decide(
                        AUG_20,
                        BALANCE,
                        List.of(row(CAR, AUG_20, "56.80", PaymentProvenance.USER_LOGGED)));

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.CARRIED);
        assertThat(decision.interest()).isEqualTo(money("117.94"));
    }

    @Test
    void a_month_whose_only_rows_carry_no_interest_is_not_booked() {
        // A statement correction, and a slice-14 check-in row stored principal-only: neither
        // charged August anything, so the regular payment still carries August's interest.
        MonthInterest.Decision decision =
                decide(
                        LocalDate.of(2026, 8, 28),
                        BALANCE,
                        List.of(
                                row(VISA, AUG_20, "0.00", PaymentProvenance.STATEMENT_CORRECTION),
                                row(VISA, AUG_20, "0.00", PaymentProvenance.CHECK_IN)));

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.CARRIED);
        assertThat(decision.interest()).isEqualTo(money("117.94"));
    }

    @Test
    void the_payment_being_corrected_does_not_book_its_own_month() {
        UUID itself = new UUID(9L, 9L);
        MonthInterestRow own =
                new MonthInterestRow(
                        itself,
                        VISA,
                        AUG_20,
                        new BigDecimal("445.00"),
                        new BigDecimal("117.94"),
                        PaymentProvenance.USER_LOGGED);

        assertThat(MonthInterest.isBooked(VISA, YearMonth.of(2026, 8), List.of(own), itself))
                .isFalse();
        assertThat(MonthInterest.isBooked(VISA, YearMonth.of(2026, 8), List.of(own), null))
                .isTrue();
    }

    @Test
    void a_zero_rate_debt_carries_no_interest_and_books_nothing() {
        MonthInterest.Decision decision =
                MonthInterest.decide(
                        VISA, AUG_20, BALANCE, BigDecimal.ZERO, List.of(), null, solver);

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.NONE);
        assertThat(decision.interest().isZero()).isTrue();
        assertThat(decision.booksTheMonth()).isFalse();
    }

    @Test
    void a_balance_with_nothing_owed_carries_no_interest() {
        MonthInterest.Decision decision = decide(AUG_20, money("-20.00"), List.of());

        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.NONE);
        assertThat(decision.interest().isZero()).isTrue();
    }

    @Test
    void a_first_payment_smaller_than_the_months_interest_still_carries_all_of_it() {
        // The check-in's $63 as the month's first payment: the whole $117.94 is booked by it, not
        // capped at the payment. DebtPaymentTest shows the row it makes — principal −$54.94, the
        // balance $6,234.94 — and the regular payment later in the month is then all principal
        // (the second test above), so August is still charged exactly once.
        MonthInterest.Decision decision = decide(AUG_20, BALANCE, List.of());

        assertThat(decision.interest()).isEqualTo(money("117.94"));
        assertThat(decision.charge()).isEqualTo(MonthInterest.Charge.CARRIED);
    }

    // ------------------------------------------------ what counts as paid this month

    @Test
    void what_was_paid_this_month_counts_this_debts_payments_in_this_month_only() {
        List<MonthInterestRow> rows =
                List.of(
                        paid(VISA, AUG_20, "445.00", "117.94", PaymentProvenance.USER_LOGGED),
                        paid(
                                VISA,
                                LocalDate.of(2026, 8, 25),
                                "50.00",
                                "0.00",
                                PaymentProvenance.USER_LOGGED),
                        paid(
                                VISA,
                                LocalDate.of(2026, 7, 20),
                                "445.00",
                                "120.00",
                                PaymentProvenance.USER_LOGGED),
                        paid(CAR, AUG_20, "342.00", "56.80", PaymentProvenance.USER_LOGGED));

        assertThat(MonthInterest.paidIn(VISA, YearMonth.of(2026, 8), rows, USD))
                .isEqualTo(money("495.00"));
    }

    @Test
    void a_statement_correction_and_a_check_in_surplus_are_not_paid_towards_the_plan() {
        // A correction is not a payment. A check-in surplus is paid ON TOP of the plan (decision
        // 5): counting it would take it off the plan's own payment and the surplus could never move
        // a payoff date.
        List<MonthInterestRow> rows =
                List.of(
                        paid(VISA, AUG_20, "185.00", "117.94", PaymentProvenance.USER_LOGGED),
                        paid(VISA, AUG_20, "-4.10", "0.00", PaymentProvenance.STATEMENT_CORRECTION),
                        paid(VISA, AUG_20, "63.00", "0.00", PaymentProvenance.CHECK_IN));

        assertThat(MonthInterest.paidIn(VISA, YearMonth.of(2026, 8), rows, USD))
                .isEqualTo(money("185.00"));
    }

    private MonthInterest.Decision decide(
            LocalDate paidDate, Money balanceBefore, List<MonthInterestRow> rows) {
        return MonthInterest.decide(VISA, paidDate, balanceBefore, APR, rows, null, solver);
    }

    private static MonthInterestRow row(
            UUID debtId, LocalDate paidDate, String interest, PaymentProvenance provenance) {
        return paid(debtId, paidDate, "100.00", interest, provenance);
    }

    private static MonthInterestRow paid(
            UUID debtId,
            LocalDate paidDate,
            String amount,
            String interest,
            PaymentProvenance provenance) {
        return new MonthInterestRow(
                UUID.randomUUID(),
                debtId,
                paidDate,
                new BigDecimal(amount),
                new BigDecimal(interest),
                provenance);
    }

    private static Money money(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }
}
