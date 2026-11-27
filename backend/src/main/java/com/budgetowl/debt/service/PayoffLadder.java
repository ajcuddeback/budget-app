package com.budgetowl.debt.service;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The payoff ladder: an order, an allocation, and a roll-down (docs/features/debt-plan.md).
 *
 * <p><b>Allocation is the ladder.</b> Every active debt gets its minimum. The surplus ({@code
 * monthlyAmount − Σ minimums}) goes <em>entirely</em> to rung 1. When rung 1 clears, its minimum
 * <b>and</b> the surplus roll down to rung 2, and so on — <em>"Pay the top one hard. Everything
 * below gets its minimum until the rung above clears."</em> Both named strategies use the same
 * roll-down; <b>the only difference between them is the order</b>, which is why {@link #rank} is
 * the only place either name appears.
 *
 * <p>Every month of every rung is the same recurrence a single debt's projection uses, run by
 * {@link DebtTermsSolver#interestFor}: one rounding per debt per month, {@code HALF_EVEN} to the
 * currency's minor unit, and the principal takes the remainder. That rounding is also why avalanche
 * never costing more than snowball holds to within pennies rather than exactly (ADR-0036, {@code
 * PayoffLadderTest}).
 *
 * <p><b>A plan never overpays a debt.</b> No rung is paid more than it owes; what a rung does not
 * need flows down to the next one <em>in the same month</em>, so the monthly amount is spent
 * exactly until the month the last debt clears. See {@link #run} for why this differs from a single
 * debt's projection, where the last payment does overshoot and is not clamped.
 *
 * <p><b>The cap is a control.</b> {@link #MAX_MONTHS} months, shared with the solver, because a
 * plan computes both strategies over every debt from one cheap request. A debt that has not cleared
 * by then has no payoff month and the plan has no debt-free month — the plan still renders, and
 * still produces dates for the debts that do clear.
 *
 * <p>Stateless and thread-safe. No clock: the first month arrives as an argument.
 */
public final class PayoffLadder {

    /** Shared with {@link DebtTermsSolver#MAX_MONTHS}: one bound, one reason, one number. */
    public static final int MAX_MONTHS = DebtTermsSolver.MAX_MONTHS;

    private final DebtTermsSolver solver;

    public PayoffLadder(DebtTermsSolver solver) {
        this.solver = Objects.requireNonNull(solver, "solver");
    }

    /**
     * Ranks the debts and runs the plan month by month.
     *
     * @param strategy the order to attack in. A caller that renders the comparison card runs this
     *     twice, once per named strategy, which is the only supported way to get both
     * @param monthlyAmount what the household puts towards debt every month, minimums included.
     *     Below the sum of the minimums the answer is {@link PayoffProjection.None}
     * @param debts the active debts, in any order, all in one currency
     * @param firstMonth the month the plan's first payment falls in. One month for the whole
     *     ladder, necessarily: the rungs share a monthly amount, so they share a timeline
     * @throws IllegalArgumentException if {@code debts} is empty — a household with no debt is an
     *     empty state on the screen, not a plan of nothing, and must not be told it is debt-free
     *     this month
     * @throws CurrencyMismatchException if the debts or the monthly amount disagree on currency.
     *     The plan's totals are per currency and a converted figure is never the input to stored
     *     arithmetic (ADR-0022), so grouping is the caller's job and mixing is a fault
     */
    public PayoffProjection project(
            PayoffStrategy strategy,
            Money monthlyAmount,
            List<LadderDebt> debts,
            YearMonth firstMonth) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        Objects.requireNonNull(firstMonth, "firstMonth");
        if (debts.isEmpty()) {
            throw new IllegalArgumentException("A plan needs at least one debt");
        }
        for (LadderDebt debt : debts) {
            if (!debt.balance().currency().equals(monthlyAmount.currency())) {
                throw new CurrencyMismatchException(
                        monthlyAmount.currency(), debt.balance().currency());
            }
        }

        List<LadderDebt> order = rank(strategy, debts);
        Money minimums = Money.zero(monthlyAmount.currency());
        for (LadderDebt debt : order) {
            minimums = minimums.plus(debt.minimumPayment());
        }
        if (monthlyAmount.compareTo(minimums) < 0 || !monthlyAmount.isPositive()) {
            // Below the minimums is the documented case. The second clause is the one that is
            // easy to miss: a household whose minimums are all ZERO has minimums == 0, so a
            // monthly amount of zero is NOT "below" them — and every rung is then paid nothing,
            // every balance grows, nothing ever clears, and all 600 months are iterated and
            // RETAINED for every rung. Fifty debts at the cap, three strategies per plan read,
            // from an unmetered GET: a plan that allocates nothing is no plan, and the figures
            // computed from one would all be fiction anyway.
            return new PayoffProjection.None(Projection.Reason.BELOW_MINIMUMS);
        }

        return run(order, monthlyAmount, firstMonth);
    }

    /**
     * The order each strategy puts the debts in, written out so the tie-breaks are visible.
     *
     * <ul>
     *   <li>{@link PayoffStrategy#AVALANCHE}: APR descending, then balance ascending, then name,
     *       then id.
     *   <li>{@link PayoffStrategy#SNOWBALL}: balance ascending, then APR descending, then name,
     *       then id.
     *   <li>{@link PayoffStrategy#CUSTOM}: {@code customOrder} ascending, then the avalanche
     *       ordering for anything unordered.
     * </ul>
     *
     * <p>Each chain ends at the id, so the order is <b>total</b>: two debts can tie on rate,
     * balance and name, and they still rank the same way on every read. A plan that reshuffles
     * between two reads of the same data is a plan the household cannot act on.
     */
    public static List<LadderDebt> rank(PayoffStrategy strategy, List<LadderDebt> debts) {
        Objects.requireNonNull(strategy, "strategy");
        Comparator<LadderDebt> byApr = Comparator.comparing(LadderDebt::apr).reversed();
        Comparator<LadderDebt> byBalance = Comparator.comparing(LadderDebt::balance);
        Comparator<LadderDebt> byIdentity =
                Comparator.comparing(LadderDebt::name).thenComparing(LadderDebt::id);
        Comparator<LadderDebt> avalanche = byApr.thenComparing(byBalance).thenComparing(byIdentity);

        Comparator<LadderDebt> comparator =
                switch (strategy) {
                    case AVALANCHE -> avalanche;
                    case SNOWBALL -> byBalance.thenComparing(byApr).thenComparing(byIdentity);
                    // Nulls last, so a household that has placed two of its five debts gets those
                    // two at the top and the rest in the cheapest order rather than at random.
                    case CUSTOM ->
                            Comparator.<LadderDebt, Integer>comparing(
                                            LadderDebt::customOrder,
                                            Comparator.nullsLast(Comparator.naturalOrder()))
                                    .thenComparing(avalanche);
                };
        return debts.stream().sorted(comparator).toList();
    }

    /**
     * The simulation. One month at a time, every debt, because the whole point of the roll-down is
     * that what each debt is paying depends on what the others have already cleared.
     *
     * <p><b>No month of the plan overpays a debt.</b> Each active rung pays its minimum, capped at
     * what it actually owes, and everything left in the month's pot — the surplus, the minimums of
     * the rungs that have cleared, and any part of a minimum its own debt did not need — flows down
     * the ladder from the top active rung. So the monthly amount is spent exactly, every month, and
     * the roll-down happens in the month a rung clears rather than the month after.
     *
     * <p>This is the one place the ladder is not the literal recurrence a single debt's projection
     * runs. A single debt keeps paying its payment and the last row overshoots, because that is
     * what the household will actually do and the screen says so. A <em>plan</em> is an allocation
     * of one monthly figure across several debts, and projecting that a household would hand a
     * lender $175 more than the payoff figure while the next debt accrues interest is a fiction —
     * one that also costs the plan its headline property, that attacking the highest rate first is
     * never the more expensive order. Without this, it is: a snowball that clears a small debt in
     * one month stops that debt's interest a month earlier and can come out ahead.
     */
    private PayoffProjection run(
            List<LadderDebt> order, Money monthlyAmount, YearMonth firstMonth) {
        int count = order.size();
        Currency currency = monthlyAmount.currency();
        List<Money> owed = new ArrayList<>(count);
        List<Money> interestPaid = new ArrayList<>(count);
        List<YearMonth> payoffMonths = new ArrayList<>(count);
        List<List<ProjectionMonth>> rows = new ArrayList<>(count);
        Money paidThisMonth = Money.zero(currency);
        List<Money> deductions = new ArrayList<>(count);
        for (LadderDebt debt : order) {
            owed.add(debt.balance());
            interestPaid.add(Money.zero(currency));
            payoffMonths.add(null);
            rows.add(new ArrayList<>());
            paidThisMonth = paidThisMonth.plus(debt.firstMonth().alreadyPaid());
            deductions.add(debt.firstMonth().alreadyPaid());
        }
        List<Money> standing = List.of();

        for (int monthNumber = 1; monthNumber <= MAX_MONTHS; monthNumber++) {
            boolean anyActive = false;
            for (int rung = 0; rung < count; rung++) {
                anyActive |= owed.get(rung).isPositive();
            }
            if (!anyActive) {
                break;
            }

            // Interest first, on the opening balance, for every active debt. Then the minimums,
            // then whatever is left over, down the ladder.
            YearMonth month = firstMonth.plusMonths(monthNumber - 1L);
            List<Money> interest = new ArrayList<>(count);
            List<Money> due = new ArrayList<>(count);
            for (int rung = 0; rung < count; rung++) {
                Money balance = owed.get(rung);
                if (!balance.isPositive()) {
                    interest.add(Money.zero(currency));
                    due.add(Money.zero(currency));
                    continue;
                }
                // Once per debt per month (ADR-0043): a payment already recorded this month carried
                // month 1's interest, and the balance includes it.
                Money monthsInterest =
                        monthNumber == 1 && order.get(rung).firstMonth().interestBooked()
                                ? Money.zero(currency)
                                : solver.interestFor(balance, order.get(rung).apr());
                interest.add(monthsInterest);
                due.add(balance.plus(monthsInterest));
            }

            List<Money> paid;
            if (monthNumber == 1) {
                // The plan's standing allocation, as if nothing were paid yet: what the page says
                // each debt is paying every month.
                standing = allocate(order, owed, due, monthlyAmount, null);
                // Count it as paid (ADR-0043): month 1 is the current month, so only what is
                // still to come of it is planned. The month's amount loses everything already
                // paid towards the debts in it, and each debt's minimum what was paid towards
                // that debt. The SAME cash goes into month 1 whichever order the ladder takes, so
                // the strategies still compare like for like. Overpaying the month plans nothing
                // more and is never carried forward: the balance already reflects it.
                Money stillToCome = monthlyAmount.minus(paidThisMonth);
                paid =
                        allocate(
                                order,
                                owed,
                                due,
                                stillToCome.isPositive() ? stillToCome : Money.zero(currency),
                                deductions);
            } else {
                paid = allocate(order, owed, due, monthlyAmount, null);
            }

            for (int rung = 0; rung < count; rung++) {
                if (!owed.get(rung).isPositive()) {
                    continue;
                }
                ProjectionMonth row =
                        ProjectionMonth.of(
                                monthNumber,
                                month,
                                owed.get(rung),
                                paid.get(rung),
                                interest.get(rung));
                rows.get(rung).add(row);
                interestPaid.set(rung, interestPaid.get(rung).plus(interest.get(rung)));
                owed.set(rung, row.balanceAfter());
                if (row.clearsTheDebt()) {
                    payoffMonths.set(rung, month);
                }
            }
        }

        return assemble(order, interestPaid, payoffMonths, rows, standing, currency);
    }

    /**
     * One month's allocation of {@code budget}: every active rung its minimum first, capped at what
     * it owes; then whatever is left down the ladder from the top.
     *
     * <p>With the whole monthly amount as the budget this is exactly the roll-down: the amount is
     * at least the sum of the minimums, so every floor is met, and a cleared rung's minimum is
     * still in the budget to flow down. In month 1 the budget can be smaller, because part of the
     * month is already paid. <b>A minimum is still met</b> — it is owed whatever was paid to
     * another debt — and only the extra goes: overpaying one debt this month never plans another's
     * minimum away, and never plans a negative payment.
     *
     * @param deductions what each rung has already had this month, off its minimum; null for none
     */
    private static List<Money> allocate(
            List<LadderDebt> order,
            List<Money> owed,
            List<Money> due,
            Money budget,
            List<Money> deductions) {
        int count = order.size();
        Currency currency = budget.currency();
        List<Money> paid = new ArrayList<>(count);
        Money left = budget;
        for (int rung = 0; rung < count; rung++) {
            if (!owed.get(rung).isPositive()) {
                paid.add(Money.zero(currency));
                continue;
            }
            Money minimum = order.get(rung).minimumPayment();
            if (deductions != null) {
                minimum = minimum.minus(deductions.get(rung));
            }
            Money floor = smaller(atLeastZero(minimum), due.get(rung));
            paid.add(floor);
            left = left.minus(floor);
        }
        for (int rung = 0; rung < count && left.isPositive(); rung++) {
            if (!owed.get(rung).isPositive()) {
                continue;
            }
            Money room = due.get(rung).minus(paid.get(rung));
            if (!room.isPositive()) {
                continue;
            }
            Money given = smaller(room, left);
            paid.set(rung, paid.get(rung).plus(given));
            left = left.minus(given);
        }
        return paid;
    }

    private static Money smaller(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Money atLeastZero(Money money) {
        return money.isNegative() ? Money.zero(money.currency()) : money;
    }

    private PayoffProjection assemble(
            List<LadderDebt> order,
            List<Money> interestPaid,
            List<YearMonth> payoffMonths,
            List<List<ProjectionMonth>> rows,
            List<Money> standing,
            Currency currency) {
        List<PayoffProjection.Rung> rungs = new ArrayList<>(order.size());
        Money totalInterest = Money.zero(currency);
        YearMonth debtFree = null;
        boolean everyDebtClears = true;
        for (int rung = 0; rung < order.size(); rung++) {
            LadderDebt debt = order.get(rung);
            List<ProjectionMonth> months = rows.get(rung);
            Money minimum = debt.minimumPayment();
            // The standing allocation, read off month 1 before anything already paid this month
            // is counted: what the debt is paying every month. Rung 1's extra is the surplus;
            // every other rung's is zero.
            Money extra = standing.get(rung).minus(minimum);
            YearMonth payoffMonth = payoffMonths.get(rung);
            rungs.add(
                    new PayoffProjection.Rung(
                            rung + 1,
                            debt,
                            minimum,
                            extra,
                            payoffMonth,
                            interestPaid.get(rung),
                            months));
            totalInterest = totalInterest.plus(interestPaid.get(rung));
            if (payoffMonth == null) {
                everyDebtClears = false;
            } else if (debtFree == null || payoffMonth.isAfter(debtFree)) {
                debtFree = payoffMonth;
            }
        }
        YearMonth firstWin = payoffMonths.get(0);
        return new PayoffProjection.Ranked(
                rungs, everyDebtClears ? debtFree : null, firstWin, totalInterest);
    }
}
