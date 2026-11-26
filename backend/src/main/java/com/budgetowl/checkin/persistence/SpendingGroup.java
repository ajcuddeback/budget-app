package com.budgetowl.checkin.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's unattached, non-transfer, base-currency rows in one category (or none), with the
 * outflows and inflows summed apart. Small by construction: at most a day per row of the range
 * times the household's categories (capped at 200) plus one.
 *
 * <p>Which groups count as unbudgeted spending is decided in Java, once, by {@code
 * UnbudgetedSpending}: the query only groups, so the definition cannot be split between SQL and
 * code.
 *
 * @param outflow the sum of the negative rows, so zero or negative
 * @param inflow the sum of the positive rows, so zero or positive
 */
public record SpendingGroup(
        LocalDate date, UUID categoryId, BigDecimal outflow, BigDecimal inflow) {

    @Override
    public String toString() {
        return "SpendingGroup[date=" + date + "]";
    }
}
