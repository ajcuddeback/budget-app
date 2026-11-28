package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import com.budgetowl.debt.domain.PaymentProvenance;
import com.budgetowl.debt.persistence.MonthInterestRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Interest is charged once per debt per calendar month</b> (ADR-0043,
 * docs/features/debt-plan.md, The split).
 *
 * <p>The first payment recorded for a debt in a month — by the payment's date — carries that
 * month's interest, {@code round(balance × apr ÷ 1200)}. Every further payment for that debt dated
 * in the same month is entirely principal. Before this rule every payment was charged a whole month
 * of interest, so two payments in one month paid two months of it: a $63 extra on a 22.9% card was
 * charged $117.94 and the balance went up.
 *
 * <p><b>"Booked" is read off the stored rows, never off an order of arrival.</b> A month is booked
 * when a stored payment for the same debt, dated in that month, carries a non-zero interest. So:
 *
 * <ul>
 *   <li>a payment back-dated to earlier in a booked month is principal only — the month's interest
 *       is already in the history, and stored splits are never rewritten;
 *   <li>a month whose only rows carry no interest — a statement correction, a payment made while
 *       nothing was owed, or a slice-14 check-in row stored principal-only — is <em>not</em>
 *       booked, and the next payment in it carries the interest, so it is never lost;
 *   <li>deleting the row that booked a month un-books it, and the next payment logged in that month
 *       re-books it. Nothing is re-split retroactively, so it is never charged twice.
 * </ul>
 *
 * <p>Pure: no clock, no database. {@code DebtPaymentService} serialises writers per debt with a row
 * lock, so two payments recorded at once cannot both find the month unbooked.
 */
public final class MonthInterest {

    /** Why a payment carries the interest it does. On the wire, in the preview. */
    public enum Charge {
        /** The first payment in its month: it carries the month's interest. */
        CARRIED,
        /** Another payment already carries this month's interest, so this one is all principal. */
        ALREADY_CARRIED,
        /** Nothing to charge: a zero rate, nothing owed, or interest that rounds to zero. */
        NONE
    }

    /**
     * The interest one payment carries, and why.
     *
     * @param month the calendar month the decision was taken for — the payment's own
     */
    public record Decision(Money interest, Charge charge, YearMonth month) {

        public Decision {
            Objects.requireNonNull(interest, "interest");
            Objects.requireNonNull(charge, "charge");
            Objects.requireNonNull(month, "month");
        }

        /** Whether, once this payment is recorded, its month's interest is booked by it. */
        public boolean booksTheMonth() {
            return charge == Charge.CARRIED;
        }

        /** Redacted: the interest is a fact about what this household owes. */
        @Override
        public String toString() {
            return "Decision[charge=" + charge + ", month=" + month + "]";
        }
    }

    private MonthInterest() {}

    /**
     * Whether {@code month}'s interest is already booked for {@code debtId}.
     *
     * @param rows stored payments; rows of other debts and other months are ignored here rather
     *     than trusted to have been filtered out by the query
     * @param except a payment to leave out — the one being corrected — or null
     */
    public static boolean isBooked(
            UUID debtId, YearMonth month, Collection<MonthInterestRow> rows, UUID except) {
        Objects.requireNonNull(debtId, "debtId");
        Objects.requireNonNull(month, "month");
        for (MonthInterestRow row : rows) {
            if (row.debtId().equals(debtId)
                    && YearMonth.from(row.paidDate()).equals(month)
                    && row.interest().signum() != 0
                    && !row.paymentId().equals(except)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What was recorded against {@code debtId} in {@code month} and counts as paid towards it: a
     * projection made mid-month plans only what is still to come (ADR-0043). A statement correction
     * is not a payment. A weekly check-in's surplus is paid <em>on top of</em> the plan
     * (weekly-check-in.md, decision 5), so it does not reduce what the plan still pays this month —
     * otherwise paying the surplus could never move a payoff date.
     */
    public static Money paidIn(
            UUID debtId, YearMonth month, Collection<MonthInterestRow> rows, Currency currency) {
        Money paid = Money.zero(currency);
        for (MonthInterestRow row : rows) {
            if (row.debtId().equals(debtId)
                    && YearMonth.from(row.paidDate()).equals(month)
                    && row.provenance() != PaymentProvenance.STATEMENT_CORRECTION
                    && row.provenance() != PaymentProvenance.CHECK_IN) {
                paid = paid.plus(Money.of(row.amount(), currency));
            }
        }
        return paid.isNegative() ? Money.zero(currency) : paid;
    }

    /**
     * The interest a payment dated {@code paidDate} carries.
     *
     * @param balanceBefore what is owed before it — the figure a month's interest is taken against
     * @param apr the annual rate, as a percentage
     * @param rows the debt's stored payments in that month (see {@link #isBooked})
     * @param except a payment to leave out, or null
     */
    public static Decision decide(
            UUID debtId,
            LocalDate paidDate,
            Money balanceBefore,
            BigDecimal apr,
            Collection<MonthInterestRow> rows,
            UUID except,
            DebtTermsSolver solver) {
        YearMonth month = YearMonth.from(paidDate);
        Money zero = Money.zero(balanceBefore.currency());
        if (isBooked(debtId, month, rows, except)) {
            return new Decision(zero, Charge.ALREADY_CARRIED, month);
        }
        if (!balanceBefore.isPositive()) {
            // A settled or overpaid balance accrues nothing; interestFor would return a negative
            // figure, which would be reported as interest somebody was charged.
            return new Decision(zero, Charge.NONE, month);
        }
        Money interest = solver.interestFor(balanceBefore, apr);
        return interest.isPositive()
                ? new Decision(interest, Charge.CARRIED, month)
                : new Decision(zero, Charge.NONE, month);
    }
}
