package com.budgetowl.period.service;

import com.budgetowl.bill.service.BillService;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.common.Money;
import com.budgetowl.debt.service.DebtFigures;
import com.budgetowl.debt.service.DebtService;
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
 * <p>Composed from the four services that own the terms rather than querying their tables, so there
 * is exactly one definition of "the month's bills", one of "this month's income" and one of "what
 * the household owes" — and the headline cannot drift from the blocks printed underneath it, which
 * is the defect this shape exists to prevent.
 *
 * <p><b>Slice 10 added the fourth term.</b> {@code left} now also loses the debt minimums that are
 * not already a bill row, so a minimum that is both is counted once (the owner's decision, in
 * {@code docs/features/debt-plan.md}). The term is shown beside the other three so the subtraction
 * is visible rather than mysterious.
 *
 * <p>Read-only throughout, and scoping is each service's own: this one holds no household id and
 * runs no query.
 */
@Service
public class PeriodSummaryService {

    private final IncomeService income;
    private final BillService bills;
    private final EnvelopeService envelopes;
    private final DebtService debts;

    public PeriodSummaryService(
            IncomeService income, BillService bills, EnvelopeService envelopes, DebtService debts) {
        this.income = income;
        this.bills = bills;
        this.envelopes = envelopes;
        this.debts = debts;
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
        DebtFigures owed = debts.figuresFor(actorId);

        return new PeriodSummary(
                period,
                expected.total(),
                due.total(),
                due.outstanding(),
                due.overdue(),
                allocated,
                owed.owed(),
                owed.count(),
                owed.minimums(),
                owed.minimumsNotInBills(),
                // The fourth term is the minimums NOT already carried by a bill row. Subtracting
                // `minimums` instead would count the car payment twice — once as its bill and once
                // as its debt — and understate what is left by a figure that looks reasonable.
                expected.total()
                        .minus(due.total())
                        .minus(allocated)
                        .minus(owed.minimumsNotInBills()));
    }
}
