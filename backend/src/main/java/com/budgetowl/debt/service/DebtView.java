package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * One debt as a surface sees it: what is stored, what was derived, and <b>which of the two each
 * figure is</b>.
 *
 * <p>Every solver cell comes with its {@link TermSource}, because a figure the app worked out must
 * never be mistaken for one the household asserted. That is the whole point of the terms panel and
 * the reason this record is as wide as it is — pairing a value with its provenance is what the
 * screen renders.
 *
 * @param balance what is owed now, derived. {@code balanceSource} says what kind of figure it is
 * @param estimatedMonths how many months were assumed to get there; {@code balanceStale} is true
 *     past the estimation cap, where the figure reverts to the last one somebody confirmed
 * @param apr null only when a rate was neither entered nor derivable, which {@code aprDerivable}
 *     distinguishes from "nobody has told us yet"
 * @param payment what this debt is paying: entered, solved from an entered horizon, or allocated by
 *     the plan — {@code paymentSource} says which
 * @param monthsLeft and {@code payoffMonth}: two faces of one unknown, both reported because both
 *     are rendered. Null together when there is no horizon, with {@code projectionReason} saying
 *     why — {@code never-clears} is a fact about the terms rather than a fault in them
 * @param totalInterestLeft a sum over the projection. Always derived, never stored
 * @param rung null for a debt that is not in the ladder: archived, settled, or at zero
 * @param progressPercent how much of {@code originalBalance} has gone. <b>Absent when there is no
 *     original balance</b> rather than rendered as 0%
 */
public record DebtView(
        UUID id,
        String name,
        String kind,
        String servicer,
        String currency,
        Money balance,
        String balanceSource,
        LocalDate balanceAsOf,
        int estimatedMonths,
        boolean balanceStale,
        Money anchorBalance,
        LocalDate anchorDate,
        BigDecimal apr,
        TermSource aprSource,
        boolean aprDerivable,
        Money minimumPayment,
        Money originalBalance,
        Money paidOffSoFar,
        BigDecimal progressPercent,
        Money payment,
        TermSource paymentSource,
        Integer monthsLeft,
        YearMonth payoffMonth,
        TermSource horizonSource,
        Money totalInterestLeft,
        String projectionReason,
        Integer rung,
        Money planMinimum,
        Money planExtra,
        UUID accountId,
        UUID billId,
        Integer customOrder,
        boolean archived,
        Instant paidOffAt,
        long paymentCount,
        long version) {

    /**
     * Redacted, as every view in this codebase is: a generated one carries the debt's name, its
     * servicer, its balance and its rate — the four most disclosing fields the app holds — and a
     * view is one careless log statement away from being printed.
     */
    @Override
    public String toString() {
        return "DebtView[id=" + id + ", archived=" + archived + "]";
    }
}
