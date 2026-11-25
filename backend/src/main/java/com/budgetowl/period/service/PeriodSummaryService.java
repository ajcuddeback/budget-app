package com.budgetowl.period.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.service.BillService;
import com.budgetowl.bill.service.BillView;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.bill.service.OccurrenceView;
import com.budgetowl.common.Money;
import com.budgetowl.debt.service.DebtFigures;
import com.budgetowl.debt.service.DebtService;
import com.budgetowl.envelope.service.EnvelopeService;
import com.budgetowl.goal.service.GoalService;
import com.budgetowl.goal.service.GoalViews.SetAside;
import com.budgetowl.income.service.IncomeForPeriod;
import com.budgetowl.income.service.IncomeService;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
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
 * <p><b>Slice 11 added the fifth term</b>: what the month's goals set aside ({@code
 * docs/features/goals.md}, decision 2). It is one object, read once, and the same object is both
 * the figure reported and the figure subtracted — so the term on the page and the term taken off
 * {@code left} cannot be two readings of two definitions, which is the defect slice 10 shipped and
 * fixed ({@code docs/memory/gotchas.md}, "a figure composed from two services").
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
    private final GoalService goals;

    public PeriodSummaryService(
            IncomeService income,
            BillService bills,
            EnvelopeService envelopes,
            DebtService debts,
            GoalService goals) {
        this.income = income;
        this.bills = bills;
        this.envelopes = envelopes;
        this.debts = debts;
        this.goals = goals;
    }

    /**
     * The bills whose amounts {@code due.total()} actually carries, this month.
     *
     * <p><b>Read off the same object the bills term is summed from</b>, which is the whole point:
     * the counted-once rule and the bills term then cannot disagree, because there is one list and
     * one reading of it. Deriving it separately — "the debt has a {@code bill_id}", or "the bill is
     * active" — is how a minimum comes to be counted in neither term:
     *
     * <ul>
     *   <li>a <b>retired</b> bill projects no occurrences, so it adds nothing to the total;
     *   <li>a <b>yearly</b> bill projects none in eleven months out of twelve;
     *   <li>a <b>skipped</b> occurrence is explicitly excluded from the total.
     * </ul>
     *
     * <p>In all three the debt's link survives, so the debt looks covered while the bills term does
     * not carry it, and {@code left} rises by a minimum the household still owes. A bill with
     * nothing but skipped occurrences is correctly absent: the household said it is not paying it
     * this month, so that money is free and the minimum counts.
     */
    private static Set<UUID> billsThisTermCarries(BillsForPeriod due) {
        Set<UUID> carried = new HashSet<>();
        for (BillView bill : due.bills()) {
            for (OccurrenceView occurrence : bill.occurrences()) {
                if (occurrence.status() != OccurrenceStatus.SKIPPED) {
                    carried.add(bill.id());
                    break;
                }
            }
        }
        return carried;
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
        DebtFigures owed = debts.figuresFor(actorId, billsThisTermCarries(due));
        SetAside goalsTerm = goals.setAsideFor(actorId, period);

        // The fourth term is the minimums NOT already carried by a bill row. Subtracting
        // `minimums` instead would count the car payment twice — once as its bill and once as its
        // debt — and understate what is left by a figure that looks reasonable. The fifth is
        // `goalsTerm.total()`, the same object reported below: there is no second reading of it.
        Money left =
                expected.total()
                        .minus(due.total())
                        .minus(allocated)
                        .minus(owed.minimumsNotInBills())
                        .minus(goalsTerm.total());
        Money extra = owed.planExtra();

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
                goalsTerm.total(),
                goalsTerm.goalsCounted(),
                extra,
                // Reported, never subtracted (decision 3): the plan's extra is the household's own
                // figure, and the page only says in words when it is more than what is left.
                extra != null && extra.compareTo(left) > 0,
                left);
    }
}
