package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * The household's debts, and the four figures the Bills &amp; Income page needs from them.
 *
 * <p><b>Not paged</b>, for the reason {@code BillsForPeriod} is not: the plan ranks every debt and
 * these totals are sums over all of them, so a page would make the order and the figures either
 * wrong or a second request. {@code DebtService.MAXIMUM_PER_HOUSEHOLD} is the bound, and it is a
 * threat-model control rather than a convenience.
 *
 * @param owed the total balance, <b>in the household's base currency only</b>. Debts in another
 *     currency are listed and counted and not added in: a combined figure would need a rate the
 *     instance may not have (ADR-0022)
 * @param debtMinimums every active debt's minimum, added up
 * @param debtMinimumsNotInBills the part of {@code debtMinimums} that is <b>not</b> already a bill
 *     row — the only debt figure Bills &amp; Income subtracts, so a minimum that is also a bill is
 *     counted once
 * @param debtCount how many debts are active: not archived, not settled. A count needs no currency,
 *     so this one spans them all
 */
public record DebtsView(
        List<DebtView> debts,
        String strategy,
        String currency,
        Money owed,
        Money debtMinimums,
        Money debtMinimumsNotInBills,
        int debtCount) {

    public DebtsView {
        debts = List.copyOf(debts);
    }

    /** Redacted — see {@code DebtView}. */
    @Override
    public String toString() {
        return "DebtsView[debts=" + debts.size() + "]";
    }
}
