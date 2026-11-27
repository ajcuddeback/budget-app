package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import com.budgetowl.debt.domain.BalanceSource;
import com.budgetowl.debt.domain.Debt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * One debt as everything downstream of the database sees it: the row, plus the figures that are
 * <b>derived on every read</b> rather than stored.
 *
 * <p>Built only by {@link DebtBalanceService}, so there is one definition of "what is owed" and one
 * of "what rate applies" — the two figures every screen, the ladder and the period summary share. A
 * second derivation would be a second answer.
 *
 * @param debt the row. Carries only what the household entered
 * @param balance what is owed <b>now</b>: the anchor rolled forward by the payments after it, and
 *     then by the months estimated on top of that. The figure the headline shows
 * @param anchoredBalance the same roll-forward <b>without</b> estimation — the last figure somebody
 *     confirmed, which is what a stale debt reports instead of a year of guesses
 * @param balanceSource what the reported balance is, which is not always what the anchor was: an
 *     anchor confirmed in June, read in September with nothing logged since, is {@link
 *     BalanceSource#ESTIMATED} because that is what the figure on screen is
 * @param balanceAsOf the date {@link #balance} was true — today for an estimate, the anchor or the
 *     last payment otherwise
 * @param estimatedMonths how many months were assumed. Zero for a balance nobody has to take on
 *     trust
 * @param stale past {@link DebtBalanceService#MAXIMUM_ESTIMATED_MONTHS} estimated months: the
 *     screen asks for a statement figure instead of extrapolating further, and {@link #balance}
 *     reverts to {@link #anchoredBalance}
 * @param apr the rate the arithmetic uses — the entered one, or the solved one, <b>never null</b>.
 *     The ladder cannot rank a null rate and the recurrence cannot run on one
 * @param aprSource {@link TermSource#ENTERED} or {@link TermSource#SOLVED}
 * @param aprDerivable false in the one case where a rate neither was entered nor can be worked out
 *     — a payment that clears this balance before its horizon at every rate under 1000%. {@link
 *     #apr} is then zero <b>for ranking only</b>, and the detail page says the rate is not
 *     available rather than showing a figure nobody can check
 * @param paymentCount how many payments this debt has
 * @param lastPaidDate the most recent payment's date, or empty for a debt with no payments at all —
 *     which projects from its anchor and is an ordinary state, not an empty one
 * @param firstPaymentMonth the month the projection's first period falls in: the linked bill's next
 *     occurrence, otherwise the day-of-month of the most recent payment, otherwise today
 * @param currentMonth the month these facts were derived in — today's
 * @param monthInterestBooked a stored payment dated in {@code currentMonth} already carries its
 *     interest. Interest is charged once per debt per month (ADR-0043)
 * @param paidThisMonth what was recorded against the debt in {@code currentMonth}, counted as paid
 *     towards it: statement corrections and check-in surpluses are not in it (see {@link
 *     FirstMonth})
 */
public record DebtFacts(
        Debt debt,
        Money balance,
        Money anchoredBalance,
        BalanceSource balanceSource,
        LocalDate balanceAsOf,
        int estimatedMonths,
        boolean stale,
        BigDecimal apr,
        TermSource aprSource,
        boolean aprDerivable,
        long paymentCount,
        LocalDate lastPaidDate,
        YearMonth firstPaymentMonth,
        YearMonth currentMonth,
        boolean monthInterestBooked,
        Money paidThisMonth) {

    public DebtFacts {
        Objects.requireNonNull(debt, "debt");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(anchoredBalance, "anchoredBalance");
        Objects.requireNonNull(balanceSource, "balanceSource");
        Objects.requireNonNull(balanceAsOf, "balanceAsOf");
        Objects.requireNonNull(apr, "apr");
        Objects.requireNonNull(aprSource, "aprSource");
        Objects.requireNonNull(firstPaymentMonth, "firstPaymentMonth");
        Objects.requireNonNull(currentMonth, "currentMonth");
        Objects.requireNonNull(paidThisMonth, "paidThisMonth");
    }

    /**
     * What is already true of {@code month} when a projection starts in it: the interest booked and
     * what was paid, if it is the current month, and nothing otherwise. No payment is dated in the
     * future, and a past month is not where a projection starts.
     */
    public FirstMonth firstMonthAt(YearMonth month) {
        return month.equals(currentMonth)
                ? new FirstMonth(monthInterestBooked, paidThisMonth)
                : FirstMonth.untouched(balance.currency());
    }

    /** The same, for this debt's own projection, which starts at its next payment month. */
    public FirstMonth firstMonth() {
        return firstMonthAt(firstPaymentMonth);
    }

    /**
     * The same debt as it would stand after one more check-in payment, which leaves {@code
     * newBalance} and may book the month's interest. Computed, never stored: the weekly check-in's
     * what-if. A check-in surplus is paid on top of the plan, so what counts as paid this month
     * does not change.
     */
    public DebtFacts afterCheckInPayment(Money newBalance, boolean booksTheMonth) {
        return new DebtFacts(
                debt,
                newBalance,
                anchoredBalance,
                balanceSource,
                balanceAsOf,
                estimatedMonths,
                stale,
                apr,
                aprSource,
                aprDerivable,
                paymentCount + 1,
                lastPaidDate,
                firstPaymentMonth,
                currentMonth,
                monthInterestBooked || booksTheMonth,
                paidThisMonth);
    }

    public java.util.UUID id() {
        return debt.id();
    }

    /**
     * Whether this debt takes part in the plan.
     *
     * <p>An archived debt is a retired container; a settled one has left the ladder with its
     * history intact; and a debt at or below zero has nothing to project. All three are still
     * listed, and none of them is ranked.
     */
    public boolean isInThePlan() {
        return !debt.isArchived() && !debt.isPaidOff() && balance.isPositive();
    }

    /** <em>"$4,470 paid off"</em> — absent when there is no baseline to measure against. */
    public Optional<Money> paidOffSoFar() {
        return debt.originalBalance().map(original -> original.minus(balance));
    }

    /** Amounts, rates and names are never logged. */
    @Override
    public String toString() {
        return "DebtFacts[debtId=" + debt.id() + ", estimatedMonths=" + estimatedMonths + "]";
    }
}
