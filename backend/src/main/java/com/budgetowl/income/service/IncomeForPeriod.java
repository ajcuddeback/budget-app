package com.budgetowl.income.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * One month's income sources and what they come to.
 *
 * @param total the sum of every active source's {@code expectedForPeriod}. An <b>expectation</b>,
 *     not a record of money that arrived: only a real transaction is ever income (ADR-0025)
 */
public record IncomeForPeriod(List<IncomeView> sources, Money total) {

    /** Redacted — see {@code IncomeView}. */
    @Override
    public String toString() {
        return "IncomeForPeriod[sources=" + sources.size() + "]";
    }
}
