package com.budgetowl.report.persistence;

import java.math.BigDecimal;

/**
 * One month's sums for one currency, straight out of the grouped query. Raw and signed: the service
 * turns them into the report's figures and checks they reconcile.
 *
 * <p>A constructor-expression projection, not an entity, so Hibernate's {@code EntityPrinter} has
 * nothing to print, and the column order cannot be misread at the call site.
 *
 * @param income {@code Σ amount} over rows in an {@code INCOME} category
 * @param expense {@code Σ amount} over rows in an {@code EXPENSE} category: negative when money
 *     left
 * @param uncategorisedIn {@code Σ amount} over uncategorised rows with {@code amount > 0}
 * @param uncategorisedOut {@code Σ amount} over uncategorised rows with {@code amount < 0}:
 *     negative
 * @param net {@code Σ amount} over every row, whatever its category
 * @param uncategorisedCount how many uncategorised rows, in either direction
 * @param count how many rows
 */
public record MonthTotals(
        Integer year,
        Integer month,
        String currency,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal uncategorisedIn,
        BigDecimal uncategorisedOut,
        BigDecimal net,
        Long uncategorisedCount,
        Long count) {

    /** The period and the currency only. Never a figure (docs/features/reporting.md, attack 6). */
    @Override
    public String toString() {
        return "MonthTotals[" + year + "-" + month + ", currency=" + currency + "]";
    }
}
