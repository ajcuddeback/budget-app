package com.budgetowl.period.service;

import com.budgetowl.bill.service.BillService;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.common.Money;
import com.budgetowl.envelope.service.EnvelopeService;
import com.budgetowl.income.service.IncomeForPeriod;
import com.budgetowl.income.service.IncomeService;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one figure the Bills &amp; Income page exists for.
 *
 * <p>Composed from the three services that own the terms rather than querying their tables, so
 * there is exactly one definition of "the month's bills" and one of "this month's income" — and the
 * headline cannot drift from the blocks printed underneath it, which is the defect this shape
 * exists to prevent.
 *
 * <p>Read-only throughout, and scoping is each service's own: this one holds no household id and
 * runs no query.
 */
@Service
public class PeriodSummaryService {

    private final IncomeService income;
    private final BillService bills;
    private final EnvelopeService envelopes;

    public PeriodSummaryService(
            IncomeService income, BillService bills, EnvelopeService envelopes) {
        this.income = income;
        this.bills = bills;
        this.envelopes = envelopes;
    }

    /**
     * @throws com.budgetowl.common.ForbiddenException if the caller is not a member. A {@code
     *     VIEWER} may read this, as it may read everything
     */
    @Transactional(readOnly = true)
    public PeriodSummary summarise(UUID actorId, YearMonth period) {
        IncomeForPeriod expected = income.list(actorId, period, false);
        BillsForPeriod due = bills.list(actorId, period, false);
        Money allocated = envelopes.allocatedFor(actorId, period);

        return new PeriodSummary(
                period,
                expected.total(),
                due.total(),
                due.outstanding(),
                due.overdue(),
                allocated,
                expected.total().minus(due.total()).minus(allocated));
    }
}
