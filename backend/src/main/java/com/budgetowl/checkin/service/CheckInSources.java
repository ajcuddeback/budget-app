package com.budgetowl.checkin.service;

import com.budgetowl.bill.service.BillService;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.common.Money;
import com.budgetowl.debt.service.DebtPaymentService;
import com.budgetowl.debt.service.DebtService;
import com.budgetowl.debt.service.DebtView;
import com.budgetowl.debt.service.PayoffPlanService;
import com.budgetowl.debt.service.PayoffPlanService.OneOffPayoff;
import com.budgetowl.debt.service.PlanView;
import com.budgetowl.goal.service.GoalService;
import com.budgetowl.goal.service.GoalViews.GoalView;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import com.budgetowl.household.service.HouseholdService;
import com.budgetowl.income.service.IncomeForPeriod;
import com.budgetowl.income.service.IncomeService;
import com.budgetowl.period.service.PeriodSummaryService;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the check-in reads from the features that own it, <b>through their services</b>, so
 * each figure has one definition (the slice-10 lesson in docs/memory/gotchas.md). Above all, {@code
 * left} is read through {@link PeriodSummaryService} and nothing else (decision 1).
 *
 * <p>Every call passes the caller's id, so each owning service resolves the household from the
 * caller's membership itself, and a {@code VIEWER} may read through every one of them. Nothing here
 * writes.
 */
@Service
public class CheckInSources {

    private final BillService bills;
    private final IncomeService income;
    private final PeriodSummaryService summaries;
    private final GoalService goals;
    private final DebtService debts;
    private final DebtPaymentService payments;
    private final PayoffPlanService plans;
    private final HouseholdService households;

    public CheckInSources(
            BillService bills,
            IncomeService income,
            PeriodSummaryService summaries,
            GoalService goals,
            DebtService debts,
            DebtPaymentService payments,
            PayoffPlanService plans,
            HouseholdService households) {
        this.bills = bills;
        this.income = income;
        this.summaries = summaries;
        this.goals = goals;
        this.debts = debts;
        this.payments = payments;
        this.plans = plans;
        this.households = households;
    }

    /**
     * The month's active bills, through the same {@code BillsForPeriod} Bills &amp; Income reads.
     */
    @Transactional(readOnly = true)
    public BillsForPeriod bills(UUID actorId, YearMonth month) {
        return bills.list(actorId, month, false);
    }

    @Transactional(readOnly = true)
    public IncomeForPeriod income(UUID actorId, YearMonth month) {
        return income.list(actorId, month, false);
    }

    /** The shipped "What's left" figure, all five terms. */
    @Transactional(readOnly = true)
    public Money left(UUID actorId, YearMonth month) {
        return summaries.summarise(actorId, month).left();
    }

    @Transactional(readOnly = true)
    public Money checkInDebtPayments(UUID actorId, YearMonth month) {
        return payments.checkInPaymentsIn(actorId, month);
    }

    /**
     * Unarchived goals, in {@code /goals} order. Capped at 50 per household by the goal service.
     */
    @Transactional(readOnly = true)
    public List<GoalView> goals(UUID actorId) {
        return goals.list(actorId, false, 0, GoalService.MAXIMUM_PER_HOUSEHOLD).content();
    }

    /** Unarchived debts, each with its rung under the selected strategy. */
    @Transactional(readOnly = true)
    public List<DebtView> debts(UUID actorId) {
        return debts.list(actorId, false).debts();
    }

    @Transactional(readOnly = true)
    public boolean hasPlan(UUID actorId) {
        return plans.hasPlan(actorId);
    }

    /** The plan's debt-free month in the base currency, or empty. */
    @Transactional(readOnly = true)
    public Optional<YearMonth> debtFreeMonth(UUID actorId) {
        PlanView plan = plans.plan(actorId);
        for (PlanView.CurrencyGroup group : plan.groups()) {
            if (group.planCurrency()) {
                return Optional.ofNullable(group.selected().debtFreeMonth());
            }
        }
        return Optional.empty();
    }

    /**
     * What a one-off payment of {@code amount} would do, by the real payment path's split
     * (discarded) and the plan's own ladder.
     */
    @Transactional(readOnly = true)
    public OneOffPayoff oneOffPayment(UUID actorId, UUID debtId, Money amount) {
        Money after = payments.balanceAfterCheckInPayment(actorId, debtId, amount);
        return plans.oneOffPayment(actorId, debtId, after);
    }

    /** Members' display names by user id, for "answered by". */
    @Transactional(readOnly = true)
    public Map<UUID, String> memberNames(UUID actorId) {
        Map<UUID, String> names = new HashMap<>();
        for (HouseholdMemberSummary member : households.members(actorId)) {
            names.put(member.userId(), member.displayName());
        }
        return names;
    }
}
