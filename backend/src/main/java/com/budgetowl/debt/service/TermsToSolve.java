package com.budgetowl.debt.service;

import java.time.YearMonth;

/**
 * A term set to solve without saving it — the editor's and the add form's live preview.
 *
 * <p><b>Exactly one</b> of {@code apr}, {@code payment} and the horizon is left out; that one is
 * the answer. All three is {@code 422 terms-over-determined} and fewer than two is a validation
 * failure, because one equation among four quantities has three degrees of freedom and neither
 * shape has an answer in it.
 *
 * @param balance always supplied, never solved. It is the one number a household can read off a
 *     statement, and an app that derived what you owe from a rate and a payment schedule would have
 *     replaced a fact with an inference and then shown it in the headline position
 * @param firstPaymentMonth the month the first period falls in, or null for this month. What turns
 *     a month count into a payoff month and back
 */
public record TermsToSolve(
        String balance,
        String currency,
        String apr,
        String payment,
        Integer monthsLeft,
        YearMonth payoffMonth,
        YearMonth firstPaymentMonth) {

    /** Redacted: a balance and a rate are the two most disclosing figures this app handles. */
    @Override
    public String toString() {
        return "TermsToSolve[firstPaymentMonth=" + firstPaymentMonth + "]";
    }
}
