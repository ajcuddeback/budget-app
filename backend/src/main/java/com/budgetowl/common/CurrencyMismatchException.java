package com.budgetowl.common;

import java.util.Currency;

/**
 * Thrown when arithmetic is attempted across two currencies (ADR-0006).
 *
 * <p>Unchecked on purpose: it is a programming error, not a condition to recover from. Adding GBP
 * to EUR has no correct answer, and silently picking one is how a budget quietly becomes wrong.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super(
                "Cannot combine "
                        + left.getCurrencyCode()
                        + " with "
                        + right.getCurrencyCode()
                        + ": convert explicitly with a rate and a date (ADR-0022)");
    }
}
