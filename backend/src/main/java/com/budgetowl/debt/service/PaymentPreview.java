package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.Objects;

/**
 * What logging a payment would record, before it is recorded.
 *
 * <p>Read off the very row {@code DebtPaymentService.log} would save, so it is the same split and
 * the same closing balance the household gets once it commits — not an estimate of them. {@code
 * interest + principal = amount} exactly, for the same reason the stored row's does.
 *
 * @param interest what this payment would pay in interest, rounded once, half-even, to the
 *     currency's minor unit
 * @param principal the rest of the payment: what it would take off the balance. Negative when the
 *     payment is smaller than the month's interest it carries
 * @param balanceBefore what is owed now — the balance the split is taken against
 * @param balanceAfter what would be owed once it is recorded. Can go below zero, which the screen
 *     says is an overpayment rather than clamping it, and can be <em>above</em> {@code
 *     balanceBefore} when the payment carries a month's interest larger than itself
 * @param monthInterest why the interest is what it is — the month's first payment carries it, a
 *     later one does not (ADR-0043). The clients say which, so a balance that rises after a payment
 *     is explained by the server rather than by arithmetic in the browser
 * @param interestMonth the calendar month that decision was taken for
 */
public record PaymentPreview(
        Money interest,
        Money principal,
        Money balanceBefore,
        Money balanceAfter,
        MonthInterest.Charge monthInterest,
        YearMonth interestMonth) {

    public PaymentPreview {
        Objects.requireNonNull(monthInterest, "monthInterest");
        Objects.requireNonNull(interestMonth, "interestMonth");
    }

    /** Whether recording it would book its month's interest. */
    public boolean booksTheMonth() {
        return monthInterest == MonthInterest.Charge.CARRIED;
    }

    /** Redacted: four amounts, and two of them are what this household owes. */
    @Override
    public String toString() {
        return "PaymentPreview[monthInterest=" + monthInterest + "]";
    }
}
