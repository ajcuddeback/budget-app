package com.budgetowl.bill.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * One month's bills and what they come to.
 *
 * <p>Totalling across bills is safe where totalling across accounts is not (ADR-0022): every bill
 * in a household is denominated in the household's base currency, so nothing is converted.
 *
 * @param total what the month's bills come to in all — <b>paid ones included</b>, at what was
 *     really paid. Rent that has already gone is not money still available, so a total that counted
 *     only the unpaid ones would rise every time somebody ticked a bill off
 * @param outstanding the part of {@code total} not yet settled. The secondary figure: "£340 of
 *     £1,200 still to pay"
 * @param overdue the part of {@code outstanding} whose due date has passed
 */
public record BillsForPeriod(List<BillView> bills, Money total, Money outstanding, Money overdue) {

    /** Redacted — see {@code BillView}. */
    @Override
    public String toString() {
        return "BillsForPeriod[bills=" + bills.size() + "]";
    }
}
