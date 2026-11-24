package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * The household's debts, and the three figures a screen can have without naming a month.
 *
 * <p><b>Not paged</b>, for the reason {@code BillsForPeriod} is not: the plan ranks every debt and
 * these totals are sums over all of them, so a page would make the order and the figures either
 * wrong or a second request. {@code DebtService.MAXIMUM_PER_HOUSEHOLD} is the bound, and it is a
 * threat-model control rather than a convenience.
 *
 * @param owed the total balance, <b>in the household's base currency only</b>. Debts in another
 *     currency are listed and counted and not added in: a combined figure would need a rate the
 *     instance may not have (ADR-0022)
 * @param debtMinimums every active debt's minimum, added up. This is the figure the plan page's
 *     footer shows beside the counted-once sentence — <em>"Minimums · each counted once in Bills
 *     &amp; Income — $645"</em>. The <b>split</b> of it ({@code debtMinimumsNotInBills}) is
 *     deliberately <b>not</b> here: it depends on which bills a given month's bills term carries,
 *     which this request does not name a month for. It lives on {@code GET
 *     /api/periods/&#123;period&#125;/summary} alone, so there is one definition of it
 * @param debtCount how many debts are active: not archived, not settled. A count needs no currency,
 *     so this one spans them all
 */
public record DebtsView(
        List<DebtView> debts,
        String strategy,
        String currency,
        Money owed,
        Money debtMinimums,
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
