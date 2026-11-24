package com.budgetowl.debt.service;

import com.budgetowl.common.AmountText;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.domain.PayoffPlan;
import com.budgetowl.debt.domain.PayoffPlanSnapshot;
import com.budgetowl.debt.persistence.DebtRepository;
import com.budgetowl.debt.persistence.PayoffPlanRepository;
import com.budgetowl.debt.persistence.PayoffPlanSnapshotRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payoff plan: an order, an allocation, and when the household is free.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes. There is exactly one plan per household
 * (ADR-0026), which is why the route is singular and why the household is the key.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>Both named strategies are computed on every read</b>, whichever is selected. The
 *       comparison card is a product requirement rather than a view of the selection, and the
 *       designs refuse to pick: <em>"Avalanche costs $780 less. Snowball clears the Visa three
 *       months sooner. Either is a good answer."</em>
 *   <li><b>Nothing derived is stored.</b> The order, the allocation, the payoff months and the
 *       totals are projected from the plan's two figures and the debts, every time.
 *   <li><b>A snapshot is written when the plan changes</b>, and never otherwise. It cannot be
 *       backfilled, so without it <em>"4 months earlier than last month's plan"</em> has nothing to
 *       compare against.
 *   <li><b>The chart series is bounded independently of the solver's iteration cap</b>
 *       (docs/features/debt-plan.md, Security considerations). The projection may iterate 600
 *       months internally; the response carries at most {@link #MAXIMUM_SERIES_POINTS} points per
 *       strategy per currency.
 * </ul>
 *
 * <p><b>Nothing here logs an amount.</b> The plan is a statement of disposable income.
 */
@Service
public class PayoffPlanService {

    /**
     * Points in one chart line, however many months the plan runs.
     *
     * <p>A threat-model control, not a display preference: two strategies × every debt × up to 600
     * months is a response a single cheap {@code GET} can ask for. Beyond this the line is thinned
     * — every {@code n}th month, with the first and last always kept — so a fifty-year plan is a
     * readable line rather than six hundred points nobody plots.
     */
    public static final int MAXIMUM_SERIES_POINTS = 60;

    private static final Logger log = LoggerFactory.getLogger(PayoffPlanService.class);

    private final MembershipService memberships;
    private final PayoffPlanRepository plans;
    private final PayoffPlanSnapshotRepository snapshots;
    private final DebtRepository debts;
    private final DebtBalanceService balances;
    private final PayoffLadder ladder;

    public PayoffPlanService(
            MembershipService memberships,
            PayoffPlanRepository plans,
            PayoffPlanSnapshotRepository snapshots,
            DebtRepository debts,
            DebtBalanceService balances,
            PayoffLadder ladder) {
        this.memberships = memberships;
        this.plans = plans;
        this.snapshots = snapshots;
        this.debts = debts;
        this.balances = balances;
        this.ladder = ladder;
    }

    /** The plan as it stands. A {@code VIEWER} may read this, as it may read everything. */
    @Transactional(readOnly = true)
    public PlanView plan(UUID actorId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        PayoffPlan plan = plans.findByHouseholdId(household.id()).orElse(null);
        return view(
                household,
                strategyOf(plan),
                monthlyAmountOf(plan, household.baseCurrency()),
                plan == null ? 0L : plan.version(),
                balances.factsFor(household.id(), false));
    }

    /**
     * The what-if: <em>"what would £50 more a month buy me?"</em>
     *
     * <p><b>Computed, never stored.</b> It is a {@code GET} with query parameters and it writes
     * nothing — not the amount it was asked about, not a snapshot, not an audit row.
     *
     * @param strategy null to keep the selected one
     * @param monthlyAmount a decimal string, or null to keep the plan's
     */
    @Transactional(readOnly = true)
    public PlanView projection(UUID actorId, String strategy, String monthlyAmount) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        PayoffPlan plan = plans.findByHouseholdId(household.id()).orElse(null);
        Currency currency = household.baseCurrency();
        return view(
                household,
                strategy == null ? strategyOf(plan) : strategyNamed(strategy),
                monthlyAmount == null
                        ? monthlyAmountOf(plan, currency)
                        : AmountText.parse(
                                monthlyAmount,
                                currency,
                                AmountText.Sign.NOT_NEGATIVE,
                                "monthlyAmount"),
                plan == null ? 0L : plan.version(),
                balances.factsFor(household.id(), false));
    }

    /**
     * Sets the strategy, the monthly amount and the custom order.
     *
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}. <b>Setting a strategy
     *     is a write</b>, even though it stores two values
     * @throws ConflictException {@code stale-version}
     * @throws NotFoundException if the custom order names a debt that is not this household's
     */
    @Transactional
    public PlanView update(UUID actorId, PlanChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Currency currency = household.baseCurrency();

        PayoffPlan plan =
                plans.findByHouseholdId(household.id())
                        .orElseGet(
                                () ->
                                        PayoffPlan.of(
                                                household,
                                                PayoffStrategy.AVALANCHE.name(),
                                                Money.zero(currency)));
        requireCurrentVersion(plan.version(), changes.version());

        PayoffStrategy before = strategyNamed(plan.strategy());
        Money amountBefore = plan.monthlyAmount(currency);
        if (changes.strategy() != null) {
            plan.attackIn(strategyNamed(changes.strategy()).name());
        }
        if (changes.monthlyAmount() != null) {
            plan.commit(
                    AmountText.parse(
                            changes.monthlyAmount(),
                            currency,
                            AmountText.Sign.NOT_NEGATIVE,
                            "monthlyAmount"));
        }
        boolean orderChanged = applyCustomOrder(household.id(), changes.customOrder());
        boolean changed =
                orderChanged
                        || before != strategyNamed(plan.strategy())
                        || !amountBefore.equals(plan.monthlyAmount(currency));

        // saveAndFlush, not save: @Version is bumped at flush, and flush would otherwise happen at
        // commit — AFTER the view is built. The response would carry the version the caller already
        // had, and its next PUT would be refused as stale (AdminService documents the same trap).
        PayoffPlan saved = plans.saveAndFlush(plan);
        PlanView view =
                view(
                        household,
                        strategyNamed(saved.strategy()),
                        saved.monthlyAmount(currency),
                        saved.version(),
                        balances.factsFor(household.id(), false));

        if (changed) {
            // Only on a real change. A snapshot per no-op PUT would fill the table with rows that
            // compare a plan to itself — and ux_payoff_plan_snapshots_household_taken_at_currency
            // exists to stop a retry writing two answers to "what did the plan say last time".
            takeSnapshot(household, saved, view);
        }
        log.info(
                "debt plan updated householdId={} byUserId={} strategy={} changed={}",
                household.id(),
                actorId,
                saved.strategy(),
                changed);
        return view;
    }

    /**
     * What the plan allocates to each debt, under the selected strategy.
     *
     * <p>The source of the third label in the terms panel — <em>from your plan</em> — and of the
     * rung and the "paying" column in the debt list. {@code DebtService} calls this with the
     * household it has already resolved from the caller's membership and the facts it has already
     * derived, so one request does not resolve the household twice or derive the same balances
     * twice.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PlanAllocation> allocationsFor(Household household, List<DebtFacts> facts) {
        PayoffPlan plan = plans.findByHouseholdId(household.id()).orElse(null);
        PayoffStrategy strategy = strategyOf(plan);
        Currency base = household.baseCurrency();
        Map<UUID, PlanAllocation> allocations = new HashMap<>();
        for (Map.Entry<Currency, List<DebtFacts>> group : groupByCurrency(facts).entrySet()) {
            Money amount = groupAmount(plan, base, group.getKey(), group.getValue());
            PayoffProjection projection =
                    ladder.project(strategy, amount, ladderDebts(group.getValue()), firstMonth());
            if (projection instanceof PayoffProjection.Ranked ranked) {
                for (PayoffProjection.Rung rung : ranked.rungs()) {
                    allocations.put(
                            rung.debt().id(),
                            new PlanAllocation(
                                    rung.rung(),
                                    rung.minimum(),
                                    rung.extra(),
                                    rung.payoffMonth(),
                                    rung.totalInterest()));
                }
            }
        }
        return Map.copyOf(allocations);
    }

    /**
     * The selected strategy's name, with no projection computed.
     *
     * <p>The debt list needs the label — <em>"Avalanche order"</em> — and nothing else from the
     * plan, and computing both strategies to read one string would make the cheapest read on the
     * page the most expensive query behind it.
     */
    @Transactional(readOnly = true)
    public String selectedStrategyFor(UUID householdId) {
        return strategyOf(plans.findByHouseholdId(householdId).orElse(null)).name();
    }

    // ------------------------------------------------------------------------------- internals

    private PlanView view(
            Household household,
            PayoffStrategy strategy,
            Money monthlyAmount,
            long version,
            List<DebtFacts> facts) {
        Currency base = household.baseCurrency();
        Map<Currency, List<DebtFacts>> grouped = groupByCurrency(facts);

        List<PlanView.CurrencyGroup> groups = new ArrayList<>(grouped.size());
        Money minimumsInBase = Money.zero(base);
        int ranked = 0;
        for (Map.Entry<Currency, List<DebtFacts>> entry : grouped.entrySet()) {
            Currency currency = entry.getKey();
            List<DebtFacts> inCurrency = entry.getValue();
            ranked += inCurrency.size();
            Money minimums = totalMinimums(inCurrency);
            if (currency.equals(base)) {
                minimumsInBase = minimums;
            }
            boolean planCurrency = currency.equals(base);
            Money amount = planCurrency ? monthlyAmount : minimums;
            groups.add(group(strategy, currency, planCurrency, amount, minimums, inCurrency));
        }
        return new PlanView(
                strategy.name(),
                monthlyAmount,
                minimumsInBase,
                ranked,
                List.copyOf(groups),
                version);
    }

    private PlanView.CurrencyGroup group(
            PayoffStrategy strategy,
            Currency currency,
            boolean planCurrency,
            Money amount,
            Money minimums,
            List<DebtFacts> inCurrency) {
        List<LadderDebt> ladderDebts = ladderDebts(inCurrency);
        Map<UUID, DebtFacts> byId = new HashMap<>();
        for (DebtFacts facts : inCurrency) {
            byId.put(facts.id(), facts);
        }

        Map<PayoffStrategy, PayoffProjection> runs = new EnumMap<>(PayoffStrategy.class);
        for (PayoffStrategy candidate :
                List.of(PayoffStrategy.AVALANCHE, PayoffStrategy.SNOWBALL, strategy)) {
            runs.computeIfAbsent(
                    candidate, named -> ladder.project(named, amount, ladderDebts, firstMonth()));
        }

        Money owed = Money.zero(currency);
        for (DebtFacts facts : inCurrency) {
            owed = owed.plus(facts.balance());
        }
        Money surplus = amount.minus(minimums);
        boolean belowMinimums = amount.compareTo(minimums) < 0;

        return new PlanView.CurrencyGroup(
                currency.getCurrencyCode(),
                planCurrency,
                amount,
                minimums,
                belowMinimums ? Money.zero(currency) : surplus,
                owed,
                belowMinimums,
                projection(strategy, runs.get(strategy), byId, currency, true),
                projection(
                        PayoffStrategy.AVALANCHE,
                        runs.get(PayoffStrategy.AVALANCHE),
                        byId,
                        currency,
                        false),
                projection(
                        PayoffStrategy.SNOWBALL,
                        runs.get(PayoffStrategy.SNOWBALL),
                        byId,
                        currency,
                        false));
    }

    /**
     * One strategy's answer, with the rungs only where a screen draws them.
     *
     * @param withRungs true for the selected strategy. The two comparison projections carry their
     *     five figures and their line, and no rungs: the table is drawn once
     */
    private PlanView.StrategyProjection projection(
            PayoffStrategy strategy,
            PayoffProjection projected,
            Map<UUID, DebtFacts> byId,
            Currency currency,
            boolean withRungs) {
        if (projected instanceof PayoffProjection.None none) {
            return new PlanView.StrategyProjection(
                    strategy.name(),
                    null,
                    null,
                    null,
                    none.reason().code(),
                    List.of(),
                    List.of(),
                    1);
        }
        PayoffProjection.Ranked ranked = (PayoffProjection.Ranked) projected;
        List<PlanView.Rung> rungs = new ArrayList<>(ranked.rungs().size());
        if (withRungs) {
            for (PayoffProjection.Rung rung : ranked.rungs()) {
                DebtFacts facts = byId.get(rung.debt().id());
                Debt debt = facts.debt();
                rungs.add(
                        new PlanView.Rung(
                                rung.rung(),
                                debt.id(),
                                debt.name(),
                                debt.kind().name(),
                                debt.servicer().orElse(null),
                                facts.balance(),
                                facts.aprDerivable() ? facts.apr() : null,
                                facts.aprDerivable(),
                                rung.minimum(),
                                rung.extra(),
                                rung.monthlyAmount(),
                                rung.payoffMonth(),
                                rung.totalInterest()));
            }
        }
        BalanceSeries.Series series = series(ranked, currency);
        return new PlanView.StrategyProjection(
                strategy.name(),
                ranked.debtFreeMonth(),
                ranked.firstWinMonth(),
                ranked.totalInterest(),
                ranked.clearsEveryDebt() ? null : Projection.Reason.NEVER_CLEARS.code(),
                List.copyOf(rungs),
                series.points(),
                series.stride());
    }

    /**
     * The balance-to-zero line: what the household still owes at the start of each month, across
     * every debt in the currency.
     *
     * <p>Summed over the rungs, because the chart's headline is the plan's own total — <em>"$26,480
     * → $0 · 31 months on the current plan"</em> — and a rung that has already cleared contributes
     * nothing to a month rather than dropping out of the line. Bounded by {@link BalanceSeries},
     * independently of the solver's iteration cap.
     */
    private BalanceSeries.Series series(PayoffProjection.Ranked ranked, Currency currency) {
        int months = 0;
        for (PayoffProjection.Rung rung : ranked.rungs()) {
            months = Math.max(months, rung.months().size());
        }
        if (months == 0) {
            return BalanceSeries.Series.empty();
        }
        List<Money> opening = new ArrayList<>(months + 1);
        for (int month = 1; month <= months; month++) {
            Money owed = Money.zero(currency);
            for (PayoffProjection.Rung rung : ranked.rungs()) {
                List<ProjectionMonth> rows = rung.months();
                if (month <= rows.size()) {
                    owed = owed.plus(rows.get(month - 1).balance());
                }
            }
            opening.add(owed);
        }
        // Where the line lands: what is left after the last month any rung pays in, clamped. A
        // plan whose final payment overshoots has not left the household owing a negative amount.
        Money last = Money.zero(currency);
        for (PayoffProjection.Rung rung : ranked.rungs()) {
            List<ProjectionMonth> rows = rung.months();
            Money after = rows.get(rows.size() - 1).balanceAfter();
            if (after.isPositive()) {
                last = last.plus(after);
            }
        }
        opening.add(last);

        YearMonth firstMonth = ranked.rungs().get(0).months().get(0).month();
        return BalanceSeries.of(firstMonth, opening, MAXIMUM_SERIES_POINTS);
    }

    /**
     * Writes each currency's projection as it stands now.
     *
     * <p>One instant for every row, which is what {@code
     * ux_payoff_plan_snapshots_household_taken_at_currency} reads as "one plan change": two
     * currencies correctly get two rows, and a double-call cannot get two rows for the same
     * currency at the same instant.
     */
    private void takeSnapshot(Household household, PayoffPlan plan, PlanView view) {
        Instant takenAt = balances.now();
        for (PlanView.CurrencyGroup group : view.groups()) {
            PlanView.StrategyProjection selected = group.selected();
            snapshots.save(
                    PayoffPlanSnapshot.of(
                            household,
                            takenAt,
                            plan.strategy(),
                            group.monthlyAmount(),
                            selected.debtFreeMonth(),
                            selected.debtFreeMonth() == null ? null : selected.totalInterest()));
        }
    }

    /**
     * Applies the household's own order: rung 1 first, and <b>every debt it leaves out is
     * cleared</b>.
     *
     * @return whether anything actually moved, which is what decides if a snapshot is taken
     */
    private boolean applyCustomOrder(UUID householdId, List<UUID> order) {
        if (order == null) {
            return false;
        }
        Map<UUID, Integer> positions = new HashMap<>();
        for (int index = 0; index < order.size(); index++) {
            UUID debtId = order.get(index);
            if (positions.putIfAbsent(debtId, index) != null) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a debt appears twice in the custom order",
                        Map.of("field", "customOrder"));
            }
        }
        boolean moved = false;
        for (Debt debt : debts.findAllForHousehold(householdId, true)) {
            Integer position = positions.remove(debt.id());
            if (!java.util.Objects.equals(debt.customOrder().orElse(null), position)) {
                debt.placeAt(position);
                debts.saveAndFlush(debt);
                moved = true;
            }
        }
        if (!positions.isEmpty()) {
            // An id that is not this household's. A 404, as everywhere, and before anything is
            // committed — the whole order is one instruction.
            throw new NotFoundException("no such debt");
        }
        return moved;
    }

    /**
     * The debts the ladder ranks: active, positive, grouped by currency.
     *
     * <p>A rate is never null here — a solved one is solved before the plan runs — and a rate that
     * could be neither entered nor derived arrives as zero, which is recorded as a known gap in the
     * feature doc rather than hidden: the debt keeps its minimum and its place rather than
     * disappearing from the plan.
     */
    private static List<LadderDebt> ladderDebts(List<DebtFacts> facts) {
        List<LadderDebt> ladderDebts = new ArrayList<>(facts.size());
        for (DebtFacts debt : facts) {
            ladderDebts.add(
                    new LadderDebt(
                            debt.id(),
                            debt.debt().name(),
                            debt.balance(),
                            debt.apr(),
                            debt.debt().minimumPayment(),
                            debt.debt().customOrder().orElse(null)));
        }
        return List.copyOf(ladderDebts);
    }

    /** The household's base currency first, then the rest alphabetically — a stable order. */
    private Map<Currency, List<DebtFacts>> groupByCurrency(List<DebtFacts> facts) {
        Map<Currency, List<DebtFacts>> grouped = new LinkedHashMap<>();
        List<DebtFacts> ranked = new ArrayList<>();
        for (DebtFacts debt : facts) {
            if (debt.isInThePlan()) {
                ranked.add(debt);
            }
        }
        ranked.sort(Comparator.comparing(debt -> debt.debt().currency().getCurrencyCode()));
        for (DebtFacts debt : ranked) {
            grouped.computeIfAbsent(debt.debt().currency(), currency -> new ArrayList<>())
                    .add(debt);
        }
        return grouped;
    }

    private static Money totalMinimums(List<DebtFacts> facts) {
        Money minimums = Money.zero(facts.get(0).debt().currency());
        for (DebtFacts debt : facts) {
            minimums = minimums.plus(debt.debt().minimumPayment());
        }
        return minimums;
    }

    /**
     * What a currency group's projection is driven by.
     *
     * <p>The plan's monthly amount for the household's own currency, and that group's <b>own
     * minimums</b> for any other. The surplus exists in one currency and cannot be allocated in
     * another without a rate the instance may not have (ADR-0022), so a second currency is
     * projected at its minimums and the group reports the amount it assumed.
     */
    private static Money groupAmount(
            PayoffPlan plan, Currency base, Currency currency, List<DebtFacts> facts) {
        return plan != null && currency.equals(base)
                ? plan.monthlyAmount(currency)
                : totalMinimums(facts);
    }

    private YearMonth firstMonth() {
        return YearMonth.from(balances.today());
    }

    private static PayoffStrategy strategyOf(PayoffPlan plan) {
        return plan == null ? PayoffStrategy.AVALANCHE : strategyNamed(plan.strategy());
    }

    private static Money monthlyAmountOf(PayoffPlan plan, Currency currency) {
        return plan == null ? Money.zero(currency) : plan.monthlyAmount(currency);
    }

    private static PayoffStrategy strategyNamed(String name) {
        try {
            return PayoffStrategy.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown strategy", Map.of("field", "strategy"));
        }
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the plan changed since it was read");
        }
    }
}
