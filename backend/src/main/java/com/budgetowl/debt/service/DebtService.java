package com.budgetowl.debt.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.debt.domain.BalanceSource;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.domain.DebtKind;
import com.budgetowl.debt.domain.DebtPayment;
import com.budgetowl.debt.domain.PaymentProvenance;
import com.budgetowl.debt.persistence.DebtPaymentRepository;
import com.budgetowl.debt.persistence.DebtRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Debts: what a household owes, on what terms, and what it has paid.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes, another household's row a {@code 404}
 * and never a {@code 403}. The database carries the same rule structurally: the account, bill,
 * transaction and occurrence foreign keys are all composite on {@code (id, household_id)}.
 *
 * <p>Five rules are specific to this service:
 *
 * <ul>
 *   <li><b>Only entered terms are stored.</b> A solved field is {@code NULL} in the row and derived
 *       on every read, so nullability is the entered/solved flag and there is no second copy of a
 *       derivable fact to drift.
 *   <li><b>An over-determined set is refused, never trimmed</b> ({@code 422
 *       terms-over-determined}). A number the household typed must not be quietly replaced by a
 *       computed one; the client is what releases a field.
 *   <li><b>A minimum that never clears the balance is accepted and stored.</b> Refusing to record a
 *       debt because its minimum is too small tells somebody in trouble that their situation is
 *       invalid input. The projection comes back absent with {@code never-clears} beside it.
 *   <li><b>A per-household cap</b> on debts, enforced the way {@code CategoryService} enforces its
 *       200: {@code countByHouseholdId} before the insert. An unbounded number of debts turns a
 *       loop of cheap {@code POST}s into an expensive {@code GET} of two projections.
 *   <li><b>Nothing here writes a transaction</b> (ADR-0035).
 * </ul>
 *
 * <p><b>Nothing here logs an amount, a rate, a name or a servicer.</b> A servicer is the sharpest
 * field this app holds: one string, no amount attached, and <em>Nelnet</em> still says this
 * household has student debt. Audit lines carry ids.
 */
@Service
public class DebtService {

    /**
     * The cap on a household's debts.
     *
     * <p>A threat-model control (docs/features/debt-plan.md, Security considerations): {@code GET
     * /api/debt-plan} projects <b>both</b> strategies over every debt, at O(debts × months) each,
     * from a single cheap request — so an unbounded number of debts is a remote amplification of a
     * {@code POST} loop into an expensive {@code GET}. Fifty is far more than the designs' three
     * and far more than any real household, and it is small enough to be a bound.
     */
    public static final int MAXIMUM_PER_HOUSEHOLD = 50;

    /**
     * The cap on payments recorded against one debt.
     *
     * <p>Logging a payment is an unbounded write otherwise: a caller holding write access could
     * walk dates and add a row per request until the disk filled — on a machine in somebody's
     * house. Two thousand is a monthly payment for 166 years, and it is also what makes the detail
     * page's unpaged read of the history safe.
     */
    public static final int MAXIMUM_PAYMENTS_PER_DEBT = 2_000;

    private static final Logger log = LoggerFactory.getLogger(DebtService.class);

    /** Places on the progress percentage. A ratio beside two exact figures, not money. */
    private static final int PERCENT_SCALE = 1;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final MembershipService memberships;
    private final DebtRepository debts;
    private final DebtPaymentRepository payments;
    private final DebtBalanceService balances;
    private final PayoffPlanService plans;
    private final DebtLinks links;
    private final DebtLocks locks;

    public DebtService(
            MembershipService memberships,
            DebtRepository debts,
            DebtPaymentRepository payments,
            DebtBalanceService balances,
            PayoffPlanService plans,
            DebtLinks links,
            DebtLocks locks) {
        this.memberships = memberships;
        this.debts = debts;
        this.payments = payments;
        this.balances = balances;
        this.plans = plans;
        this.links = links;
        this.locks = locks;
    }

    // ----------------------------------------------------------------------------------- debts

    /** Every debt, with its derived balance, its rung and what the plan has it paying. */
    @Transactional(readOnly = true)
    public DebtsView list(UUID actorId, boolean includeArchived) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        List<DebtFacts> facts = balances.factsFor(household.id(), includeArchived);
        Map<UUID, PlanAllocation> allocations = plans.allocationsFor(household, facts);

        List<DebtView> views = new ArrayList<>(facts.size());
        for (DebtFacts debt : facts) {
            views.add(view(debt, allocations.get(debt.id())));
        }
        // Three figures, not four: the split of the minimums depends on which bills a given
        // month's bills term carries, and this request names no month. See DebtsView.
        // No plan extra either: the list reports none, so it is not computed for it.
        DebtFigures figures = figures(facts, household.baseCurrency(), Set.of(), null);
        return new DebtsView(
                List.copyOf(views),
                plans.selectedStrategyFor(household.id()),
                household.baseCurrency().getCurrencyCode(),
                figures.owed(),
                figures.minimums(),
                figures.count());
    }

    /** One debt, with the two lines its chart draws. */
    @Transactional(readOnly = true)
    public DebtDetailView get(UUID actorId, UUID debtId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());
        return detail(household, debt);
    }

    /**
     * The four debt figures the Bills &amp; Income summary carries.
     *
     * <p>Composed by {@code PeriodSummaryService} rather than queried there, so there is one
     * definition of "what the household owes" and the page's headline cannot drift from the Owed
     * card printed beside it.
     *
     * @param billsAlreadyCounted the ids of the bills whose amounts the <b>same month's</b> bills
     *     term already carries — taken from the very {@code BillsForPeriod} that term is summed
     *     from, never re-derived here. That is what makes the counted-once rule true by
     *     construction instead of by two services agreeing: a retired bill, a yearly one outside
     *     its month and a skipped occurrence are all absent from this set, and the minimum
     *     therefore counts, which is right in all three cases
     * @throws com.budgetowl.common.ForbiddenException if the caller is not a member. A {@code
     *     VIEWER} may read this, as it may read everything
     */
    @Transactional(readOnly = true)
    public DebtFigures figuresFor(UUID actorId, Set<UUID> billsAlreadyCounted) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        List<DebtFacts> facts = balances.factsFor(household.id(), false);
        return figures(
                facts,
                household.baseCurrency(),
                billsAlreadyCounted,
                plans.extraFor(household, facts).orElse(null));
    }

    /**
     * Records a debt.
     *
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code debt-limit-reached}
     * @throws NotFoundException if the account or the bill is not this household's
     * @throws InvalidRequestException {@code terms-over-determined} ({@code 422}), {@code
     *     terms-unsolvable} ({@code 422}), or {@code validation-failed} naming the field
     */
    @Transactional
    public DebtDetailView create(UUID actorId, NewDebt requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        if (debts.countByHouseholdId(household.id()) >= MAXIMUM_PER_HOUSEHOLD) {
            throw new ConflictException(
                    ErrorCode.DEBT_LIMIT_REACHED,
                    "a household is limited to " + MAXIMUM_PER_HOUSEHOLD + " debts");
        }

        Account account =
                requested.accountId() == null
                        ? null
                        : links.requireAccount(requested.accountId(), household.id());
        Currency currency = currencyFor(requested.currency(), account, household);

        Money balance =
                AmountText.parse(
                        requested.balance(), currency, AmountText.Sign.NOT_NEGATIVE, "balance");
        if (!balance.isPositive()) {
            // A debt CREATED at zero is nothing to reduce. Distinct from a debt that has REACHED
            // zero, which is how one is paid off — and from an overpaid one, which is negative and
            // must still be renderable. The solver answers the same way about the same figure.
            throw new InvalidRequestException(
                    ErrorCode.TERMS_UNSOLVABLE,
                    "a debt starts from a positive balance",
                    Map.of("field", "balance"));
        }
        LocalDate balanceDate = requireNotInTheFuture(requested.balanceDate(), "balanceDate");

        Debt debt =
                Debt.of(
                        household,
                        requested.name(),
                        kindNamed(requested.kind()),
                        balance,
                        balanceDate,
                        AmountText.parse(
                                requested.minimumPayment(),
                                currency,
                                AmountText.Sign.NOT_NEGATIVE,
                                "minimumPayment"));
        debt.servicedBy(requested.servicer());
        if (requested.originalBalance() != null) {
            debt.setOriginalBalance(
                    AmountText.parse(
                            requested.originalBalance(),
                            currency,
                            AmountText.Sign.POSITIVE,
                            "originalBalance"));
        }
        if (requested.apr() != null) {
            debt.enterApr(rate(requested.apr()));
        }
        if (requested.payment() != null) {
            debt.enterPayment(
                    AmountText.parse(
                            requested.payment(), currency, AmountText.Sign.POSITIVE, "payment"));
        }
        enterHorizon(debt, requested.monthsLeft(), requested.payoffMonth());
        if (account != null) {
            debt.heldAt(account.id());
        }
        if (requested.billId() != null) {
            debt.minimumPaidBy(links.requireBill(requested.billId(), household.id()));
        }

        requireWithinDegreesOfFreedom(debt);
        requireDeterminedAtCreation(debt);

        Debt saved = save(debt);
        requireSolvableTerms(saved);
        log.info(
                "debt created householdId={} debtId={} byUserId={} kind={}",
                household.id(),
                saved.id(),
                actorId,
                saved.kind());
        return detail(household, saved);
    }

    /**
     * The terms editor: a three-way change per solver field.
     *
     * <p>Absent leaves a field alone, an explicit null <b>releases</b> it to the solver, and a
     * value enters it. It never infers "absent means release": a field dropped by accident would
     * silently change what the household believes about its own payoff date.
     *
     * @throws ConflictException {@code stale-version}, {@code debt-paid-off}
     * @throws InvalidRequestException {@code terms-over-determined}, {@code terms-unsolvable}
     */
    @Transactional
    public DebtDetailView update(UUID actorId, UUID debtId, DebtChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());
        Currency currency = debt.currency();

        requireCurrentVersion(debt.version(), changes.version());
        requireNotSettled(debt);

        if (changes.name() != null) {
            debt.rename(changes.name());
        }
        if (changes.kind() != null) {
            debt.reclassify(kindNamed(changes.kind()));
        }
        switch (changes.servicer()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> debt.servicedBy(null);
            case Change.SetTo<String> named -> debt.servicedBy(named.value());
        }
        if (changes.minimumPayment() != null) {
            debt.setMinimumPayment(
                    AmountText.parse(
                            changes.minimumPayment(),
                            currency,
                            AmountText.Sign.NOT_NEGATIVE,
                            "minimumPayment"));
        }
        switch (changes.originalBalance()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> debt.setOriginalBalance(null);
            case Change.SetTo<String> baseline ->
                    debt.setOriginalBalance(
                            AmountText.parse(
                                    baseline.value(),
                                    currency,
                                    AmountText.Sign.POSITIVE,
                                    "originalBalance"));
        }

        boolean termsTouched = applyTerms(debt, changes, currency);

        switch (changes.accountId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> debt.heldAt(null);
            case Change.SetTo<UUID> held -> {
                Account account = links.requireAccount(held.value(), household.id());
                if (!account.currency().equals(currency)) {
                    // The account owns the currency of anything held at it, and a debt's currency
                    // is not independently settable. Refused rather than silently reinterpreting
                    // every figure already recorded against this debt.
                    throw new InvalidRequestException(
                            ErrorCode.VALIDATION_FAILED,
                            "this account is in a different currency from this debt",
                            Map.of("field", "accountId"));
                }
                debt.heldAt(account.id());
            }
        }
        switch (changes.billId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> debt.minimumPaidBy(null);
            case Change.SetTo<UUID> paidBy ->
                    debt.minimumPaidBy(links.requireBill(paidBy.value(), household.id()));
        }
        switch (changes.customOrder()) {
            case Change.Unchanged<Integer> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<Integer> ignored -> debt.placeAt(null);
            case Change.SetTo<Integer> placed -> debt.placeAt(placed.value());
        }

        requireWithinDegreesOfFreedom(debt);
        Debt saved = save(debt);
        if (termsTouched) {
            // Only when the terms moved. A debt whose balance has fallen past its entered horizon
            // can be legitimately unsolvable, and refusing to rename it would make the row
            // unmaintainable.
            requireSolvableTerms(saved);
        }
        log.info(
                "debt updated householdId={} debtId={} byUserId={}",
                household.id(),
                debtId,
                actorId);
        return detail(household, saved);
    }

    /** Takes the debt and its own payment history. No transactions, ever. */
    @Transactional
    public void delete(UUID actorId, UUID debtId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        debts.delete(require(debtId, householdId));
        log.info("debt deleted householdId={} debtId={} byUserId={}", householdId, debtId, actorId);
    }

    /** Idempotent: archiving an archived debt is the state the caller asked for. */
    @Transactional
    public DebtDetailView archive(UUID actorId, UUID debtId) {
        return setArchived(actorId, debtId, true);
    }

    @Transactional
    public DebtDetailView unarchive(UUID actorId, UUID debtId) {
        return setArchived(actorId, debtId, false);
    }

    /**
     * Confirms or corrects the balance from a statement.
     *
     * <p><b>A statement wins</b>, and the gap is logged rather than applied silently: where the
     * figure disagrees with the arithmetic, a {@code STATEMENT_CORRECTION} row records the
     * difference and the anchor moves to the confirmed figure. History is never adjusted to agree
     * with the present.
     *
     * <p>This is also the one write a settled debt accepts: a correction is how a household says
     * the balance came back.
     */
    @Transactional
    public DebtDetailView confirmBalance(
            UUID actorId, UUID debtId, BalanceConfirmation confirmation) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        // Locked as every payment write is: the correction is taken against the derived balance,
        // and a payment logged in between would leave the anchor disagreeing with the statement.
        Debt debt = locks.lock(debtId, household.id());
        Currency currency = debt.currency();

        Money confirmed =
                AmountText.parse(
                        confirmation.balance(), currency, AmountText.Sign.NOT_NEGATIVE, "balance");
        LocalDate asOf = requireNotInTheFuture(confirmation.balanceDate(), "balanceDate");

        Money derived = balances.factsFor(debt).balance();
        Money difference = confirmed.minus(derived);
        if (!difference.isZero()) {
            // This writes a payment row, so it is bound by the same cap as logging one. Checked
            // only inside the `if`: a confirmation that agrees with the derived figure writes
            // nothing, and refusing it at the cap would make a debt at 2,000 payments impossible
            // to confirm the balance of.
            DebtPaymentService.requireRoomForAPayment(payments, household.id(), debt.id());

            // The row runs the other way to a payment: a statement that says the balance is
            // HIGHER is a negative amount against a rising balance, which is why no sign
            // constraint exists on these columns.
            payments.saveAndFlush(
                    annotated(
                            DebtPayment.of(
                                    debt,
                                    asOf,
                                    difference.negated(),
                                    Money.zero(currency),
                                    derived,
                                    PaymentProvenance.STATEMENT_CORRECTION),
                            confirmation.note()));
        }

        // The anchor is the figure as at the START of its date, and the derivation re-applies the
        // payments recorded on that date. So the anchor has to absorb them, or the balance on
        // screen would differ from the statement figure by exactly the payments the household
        // logged that day — which is the one case where "the anchor is the statement" and "a
        // payment made today moves the balance" cannot both be literally true.
        debt.anchorAt(confirmed.plus(sameDayPrincipal(debt, asOf)), asOf, BalanceSource.CONFIRMED);
        if (confirmed.isPositive()) {
            debt.reopen();
        } else {
            debt.settle(balances.now());
        }
        Debt saved = save(debt);
        log.info(
                "debt balance confirmed householdId={} debtId={} byUserId={} corrected={}",
                household.id(),
                debtId,
                actorId,
                !difference.isZero());
        return detail(household, saved);
    }

    // ------------------------------------------------------------------------ view assembly

    /**
     * One debt's page.
     *
     * <p>The allocation comes from the <b>whole</b> ladder, never from this debt alone: a ladder of
     * one would hand it the entire surplus and the page would promise a payment the plan does not
     * make. That mistake is invisible on a household with one debt, which is exactly the shape of
     * the test somebody writes first.
     */
    private DebtDetailView detail(Household household, Debt debt) {
        List<DebtFacts> all = balances.factsFor(household.id(), true);
        DebtFacts facts = null;
        for (DebtFacts candidate : all) {
            if (candidate.id().equals(debt.id())) {
                facts = candidate;
            }
        }
        if (facts == null) {
            throw new NotFoundException("no such debt");
        }
        PlanAllocation allocation = plans.allocationsFor(household, all).get(facts.id());
        Terms terms = terms(facts, allocation);

        BalanceSeries.Series pace =
                terms.months().isEmpty()
                        ? BalanceSeries.Series.empty()
                        : BalanceSeries.of(
                                facts.firstPaymentMonth(),
                                BalanceSeries.openings(terms.months()),
                                PayoffPlanService.MAXIMUM_SERIES_POINTS);
        BalanceSeries.Series atMinimum = minimumSeries(facts);
        return new DebtDetailView(
                view(facts, allocation, terms),
                pace.points(),
                atMinimum.points(),
                Math.max(pace.stride(), atMinimum.stride()),
                terms.payment() != null && terms.payment().equals(facts.debt().minimumPayment()));
    }

    /**
     * The <em>"if you'd paid the minimum"</em> line.
     *
     * <p>Empty where the minimum never clears the balance — the common real case for a card, since
     * a real minimum is a percentage of the balance and a household entering last month's flat
     * figure hits it legitimately. A line that is not there is the honest rendering of a comparison
     * that does not exist.
     */
    private BalanceSeries.Series minimumSeries(DebtFacts facts) {
        if (!facts.balance().isPositive()) {
            return BalanceSeries.Series.empty();
        }
        Projection projection =
                balances.solver()
                        .project(
                                facts.balance(),
                                facts.apr(),
                                facts.debt().minimumPayment(),
                                facts.firstPaymentMonth(),
                                facts.firstMonth());
        if (projection instanceof Projection.Cleared cleared) {
            return BalanceSeries.of(
                    facts.firstPaymentMonth(),
                    BalanceSeries.openings(cleared.months()),
                    PayoffPlanService.MAXIMUM_SERIES_POINTS);
        }
        return BalanceSeries.Series.empty();
    }

    private DebtView view(DebtFacts facts, PlanAllocation allocation) {
        return view(facts, allocation, terms(facts, allocation));
    }

    private DebtView view(DebtFacts facts, PlanAllocation allocation, Terms terms) {
        Debt debt = facts.debt();
        Money paidOff = facts.paidOffSoFar().orElse(null);
        return new DebtView(
                debt.id(),
                debt.name(),
                debt.kind().name(),
                debt.servicer().orElse(null),
                debt.currency().getCurrencyCode(),
                facts.balance(),
                facts.balanceSource().name(),
                facts.balanceAsOf(),
                facts.estimatedMonths(),
                facts.stale(),
                facts.anchoredBalance(),
                debt.anchorDate(),
                facts.aprDerivable() ? facts.apr() : null,
                facts.aprSource(),
                facts.aprDerivable(),
                debt.minimumPayment(),
                debt.originalBalance().orElse(null),
                paidOff,
                progressPercent(debt.originalBalance().orElse(null), facts.balance()),
                terms.payment(),
                terms.paymentSource(),
                terms.monthsLeft(),
                terms.payoffMonth(),
                terms.horizonSource(),
                terms.totalInterest(),
                terms.reason(),
                allocation == null ? null : allocation.rung(),
                allocation == null ? null : allocation.minimum(),
                allocation == null ? null : allocation.extra(),
                debt.accountId().orElse(null),
                debt.billId().orElse(null),
                debt.customOrder().orElse(null),
                debt.isArchived(),
                debt.paidOffAt().orElse(null),
                facts.paymentCount(),
                debt.version());
    }

    /**
     * Fills in whichever solver field is not supplied, and says where each figure came from.
     *
     * <p>Three cases, which are the three the feature doc sets out:
     *
     * <ol>
     *   <li>a payment and a horizon entered: the <b>rate</b> was the target, and it is already
     *       solved in the facts — the installment-loan case;
     *   <li>a horizon entered and no payment: the <b>payment</b> is the target, so the plan shows
     *       this debt's <em>required</em> payment rather than its allocation;
     *   <li>otherwise the <b>horizon</b> is the target, paid with the entered payment, the plan's
     *       allocation, or — with no plan yet — the minimum, which is what makes "enough to rank
     *       them" true for a debt one minute old.
     * </ol>
     */
    private Terms terms(DebtFacts facts, PlanAllocation allocation) {
        Debt debt = facts.debt();
        Money balance = facts.balance();
        Money entered = debt.payment().orElse(null);
        Horizon horizon = DebtBalanceService.horizonOf(debt, facts.firstPaymentMonth());

        if (!balance.isPositive()) {
            // Nothing to project: a settled or overpaid debt has no horizon, and its payment is
            // whatever the household last said it was.
            return new Terms(
                    entered,
                    entered == null ? TermSource.FROM_PLAN : TermSource.ENTERED,
                    debt.monthsLeft().orElse(null),
                    debt.payoffMonth().orElse(null),
                    debt.hasEnteredHorizon() ? TermSource.ENTERED : TermSource.SOLVED,
                    null,
                    null,
                    List.of());
        }

        if (entered != null && horizon != null) {
            Projection projection =
                    balances.solver()
                            .project(
                                    balance,
                                    facts.apr(),
                                    entered,
                                    facts.firstPaymentMonth(),
                                    facts.firstMonth());
            return horizonEntered(debt, entered, TermSource.ENTERED, projection);
        }
        if (horizon != null) {
            TermsSolution solution =
                    balances.solver()
                            .solve(
                                    DebtTerms.solvingPayment(
                                                    balance,
                                                    facts.apr(),
                                                    horizon,
                                                    facts.firstPaymentMonth())
                                            .withFirstMonth(facts.firstMonth()));
            return switch (solution) {
                case TermsSolution.Solved solved ->
                        horizonEntered(
                                debt,
                                solved.terms().payment(),
                                TermSource.SOLVED,
                                solved.projection());
                case TermsSolution.Unsolvable ignored ->
                        new Terms(
                                null,
                                TermSource.SOLVED,
                                debt.monthsLeft().orElse(null),
                                debt.payoffMonth().orElse(null),
                                TermSource.ENTERED,
                                null,
                                ErrorCode.TERMS_UNSOLVABLE.code(),
                                List.of());
            };
        }

        Money paying = entered;
        TermSource payingSource = TermSource.ENTERED;
        if (paying == null) {
            // From the plan, and the minimum where the plan has no order to give — a monthly
            // amount below the minimums produces no projection, and the debt is still paying
            // something.
            paying = allocation == null ? debt.minimumPayment() : allocation.monthlyAmount();
            payingSource = TermSource.FROM_PLAN;
        }
        Projection projection =
                balances.solver()
                        .project(
                                balance,
                                facts.apr(),
                                paying,
                                facts.firstPaymentMonth(),
                                facts.firstMonth());
        if (projection instanceof Projection.Cleared cleared) {
            return new Terms(
                    paying,
                    payingSource,
                    cleared.monthsLeft(),
                    cleared.payoffMonth(),
                    TermSource.SOLVED,
                    cleared.totalInterest(),
                    null,
                    cleared.months());
        }
        return new Terms(
                paying,
                payingSource,
                null,
                null,
                TermSource.SOLVED,
                null,
                ((Projection.None) projection).reason().code(),
                List.of());
    }

    /** The two cases where the household entered the horizon, so both faces are reported. */
    private static Terms horizonEntered(
            Debt debt, Money paying, TermSource payingSource, Projection projection) {
        Integer monthsLeft = debt.monthsLeft().orElse(null);
        YearMonth payoffMonth = debt.payoffMonth().orElse(null);
        if (projection instanceof Projection.Cleared cleared) {
            return new Terms(
                    paying,
                    payingSource,
                    monthsLeft == null ? Integer.valueOf(cleared.monthsLeft()) : monthsLeft,
                    payoffMonth == null ? cleared.payoffMonth() : payoffMonth,
                    TermSource.ENTERED,
                    cleared.totalInterest(),
                    null,
                    cleared.months());
        }
        return new Terms(
                paying,
                payingSource,
                monthsLeft,
                payoffMonth,
                TermSource.ENTERED,
                null,
                ((Projection.None) projection).reason().code(),
                List.of());
    }

    /**
     * How much of the original balance has gone.
     *
     * <p>Absent when there is no baseline, rather than 0%: a progress bar at zero on a revolving
     * card is a claim nobody made. {@code HALF_UP} rather than money's {@code HALF_EVEN} for the
     * reason {@code TransferService}'s implied rate gives — this is a ratio printed beside two
     * exact figures, nothing sums it, and no balance depends on it.
     */
    private static BigDecimal progressPercent(Money original, Money balance) {
        if (original == null || !original.isPositive()) {
            return null;
        }
        return original.minus(balance)
                .amount()
                .multiply(ONE_HUNDRED)
                .divide(original.amount(), PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static DebtFigures figures(
            List<DebtFacts> facts, Currency base, Set<UUID> billsAlreadyCounted, Money planExtra) {
        Money owed = Money.zero(base);
        Money minimums = Money.zero(base);
        Money notInBills = Money.zero(base);
        int count = 0;
        for (DebtFacts debt : facts) {
            if (debt.debt().isArchived() || debt.debt().isPaidOff()) {
                continue;
            }
            count++;
            if (!debt.debt().currency().equals(base)) {
                // Counted, listed, and not added in: a combined figure across currencies would
                // need a rate the instance may not have (ADR-0022).
                continue;
            }
            owed = owed.plus(debt.balance());
            minimums = minimums.plus(debt.debt().minimumPayment());
            // "In bills" means THIS MONTH'S BILLS TERM ACTUALLY CARRIED IT — not that the debt
            // names a bill. Retired, yearly-and-not-due, and skipped all leave the link in place
            // while contributing nothing to the total, and treating the debt as covered would
            // leave that minimum counted in neither term, raising `left` by it silently.
            if (debt.debt().billId().filter(billsAlreadyCounted::contains).isEmpty()) {
                notInBills = notInBills.plus(debt.debt().minimumPayment());
            }
        }
        return new DebtFigures(owed, count, minimums, notInBills, planExtra);
    }

    // ------------------------------------------------------------------------------- internals

    private DebtDetailView setArchived(UUID actorId, UUID debtId, boolean archived) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());
        if (archived) {
            debt.archive();
        } else {
            debt.unarchive();
        }
        Debt saved = save(debt);
        log.info(
                "debt {} householdId={} debtId={} byUserId={}",
                archived ? "archived" : "unarchived",
                household.id(),
                debtId,
                actorId);
        return detail(household, saved);
    }

    /**
     * Applies the three-way change to each solver field.
     *
     * @return whether any of them moved, which is what decides if the result has to solve
     */
    private boolean applyTerms(Debt debt, DebtChanges changes, Currency currency) {
        boolean touched = false;
        switch (changes.apr()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned: left exactly as it was, entered or solved.
            }
            case Change.Cleared<String> ignored -> {
                debt.releaseApr();
                touched = true;
            }
            case Change.SetTo<String> rate -> {
                debt.enterApr(rate(rate.value()));
                touched = true;
            }
        }
        switch (changes.payment()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> {
                debt.releasePayment();
                touched = true;
            }
            case Change.SetTo<String> made -> {
                debt.enterPayment(
                        AmountText.parse(
                                made.value(), currency, AmountText.Sign.POSITIVE, "payment"));
                touched = true;
            }
        }

        boolean monthsMentioned = !(changes.monthsLeft() instanceof Change.Unchanged<Integer>);
        boolean monthMentioned = !(changes.payoffMonth() instanceof Change.Unchanged<YearMonth>);
        if (changes.monthsLeft() instanceof Change.SetTo<Integer> months
                && changes.payoffMonth() instanceof Change.SetTo<YearMonth>) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "monthsLeft and payoffMonth are one unknown with two faces; send one",
                    Map.of("field", "payoffMonth", "months", months.value()));
        }
        if (changes.monthsLeft() instanceof Change.SetTo<Integer> months) {
            debt.enterMonthsLeft(months.value());
            touched = true;
        } else if (changes.payoffMonth() instanceof Change.SetTo<YearMonth> month) {
            debt.enterPayoffMonth(month.value());
            touched = true;
        } else if (monthsMentioned || monthMentioned) {
            // Either face released releases the horizon: they are one unknown.
            debt.releaseHorizon();
            touched = true;
        }
        return touched;
    }

    private static void enterHorizon(Debt debt, Integer monthsLeft, YearMonth payoffMonth) {
        if (monthsLeft != null && payoffMonth != null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "monthsLeft and payoffMonth are one unknown with two faces; send one",
                    Map.of("field", "payoffMonth"));
        }
        if (monthsLeft != null) {
            debt.enterMonthsLeft(monthsLeft);
        } else if (payoffMonth != null) {
            debt.enterPayoffMonth(payoffMonth);
        }
    }

    /**
     * Refuses an over-determined set, <b>naming the fields</b>.
     *
     * <p>Never by dropping one: a number the household typed must not be quietly replaced by a
     * computed one. {@code ck_debts_degrees_of_freedom} says the same thing where no caller can
     * bypass it, and {@link #save} turns its violation into this same answer.
     */
    private static void requireWithinDegreesOfFreedom(Debt debt) {
        if (debt.isOverDetermined()) {
            throw new InvalidRequestException(
                    ErrorCode.TERMS_OVER_DETERMINED,
                    "apr, payment and the horizon are three values for a two-value equation",
                    Map.of("fields", List.of("apr", "payment", "payoffMonth")));
        }
        if (debt.hasBothFacesOfTheHorizon()) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "monthsLeft and payoffMonth are one unknown with two faces; send one",
                    Map.of("field", "payoffMonth"));
        }
    }

    /**
     * A new debt needs enough to project from: a rate, or a payment and a horizon together.
     *
     * <p>A rate on its own is <b>enough</b> — the payment then comes from the plan, or falls back
     * to the minimum — which is what makes onboarding's three fields (balance, APR, minimum) a
     * complete debt.
     */
    private static void requireDeterminedAtCreation(Debt debt) {
        if (debt.apr().isEmpty() && !(debt.payment().isPresent() && debt.hasEnteredHorizon())) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a rate is required unless a payment and a horizon are both given",
                    Map.of("field", "apr"));
        }
    }

    /**
     * Refuses terms with no answer in them, naming the field at fault.
     *
     * <p>A horizon the balance cannot reach at any non-negative rate, a payoff month in the past, a
     * payment that clears at every rate. Distinct from {@code never-clears}, which is a term set
     * that is <b>accepted and stored</b> with its projection reported absent.
     */
    private void requireSolvableTerms(Debt debt) {
        DebtFacts facts = balances.factsFor(debt);
        YearMonth firstPaymentMonth = facts.firstPaymentMonth();
        if (debt.payoffMonth().isPresent()
                && !Horizon.isSolvableSpan(debt.payoffMonth().orElseThrow(), firstPaymentMonth)) {
            // Both directions, from one predicate. A payoff month in the past and one eleven
            // billion months out are the same answer — a well-formed request the solver cannot
            // answer — and bounding only the near side left the far side to overflow into a 500.
            throw unsolvable(
                    "payoffMonth",
                    "a payoff month outside the "
                            + DebtTermsSolver.MAX_MONTHS
                            + " months a projection covers");
        }
        if (!facts.aprDerivable() && debt.apr().isEmpty()) {
            throw unsolvable(
                    "apr", "no rate follows from this balance, payment and horizon together");
        }
        if (debt.payment().isEmpty() && debt.hasEnteredHorizon()) {
            Horizon horizon = DebtBalanceService.horizonOf(debt, firstPaymentMonth);
            if (horizon == null) {
                throw unsolvable(
                        "monthsLeft", "a horizon beyond " + DebtTermsSolver.MAX_MONTHS + " months");
            }
            TermsSolution solution =
                    balances.solver()
                            .solve(
                                    DebtTerms.solvingPayment(
                                                    facts.balance(),
                                                    facts.apr(),
                                                    horizon,
                                                    firstPaymentMonth)
                                            .withFirstMonth(facts.firstMonth()));
            if (solution instanceof TermsSolution.Unsolvable refused) {
                throw unsolvable(refused.field().code(), refused.detail());
            }
        }
    }

    private static InvalidRequestException unsolvable(String field, String detail) {
        return new InvalidRequestException(
                ErrorCode.TERMS_UNSOLVABLE, detail, Map.of("field", field));
    }

    /**
     * The currency a debt is denominated in.
     *
     * <p>The account's where it is held at one — the account owns that fact, exactly as it does for
     * a transaction — otherwise the household's base currency, or whatever the caller asked for.
     * Asking for one that disagrees with the account is refused rather than ignored: a client that
     * believed it had set a currency and had not would be reading every figure in the wrong unit.
     */
    private static Currency currencyFor(String requested, Account account, Household household) {
        if (account != null) {
            if (requested != null
                    && !requested.equalsIgnoreCase(account.currency().getCurrencyCode())) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a debt held at an account is in that account's currency",
                        Map.of("field", "currency"));
            }
            return account.currency();
        }
        if (requested == null) {
            return household.baseCurrency();
        }
        try {
            return Currency.getInstance(requested.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown currency", Map.of("field", "currency"));
        }
    }

    private static BigDecimal rate(String text) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text.strip());
        } catch (NumberFormatException malformed) {
            // The rejected text is never echoed back: an APR is a statement about somebody's
            // credit rating.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unparseable rate", Map.of("field", "apr"));
        }
        if (parsed.scale() > 3) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a rate has at most three decimal places",
                    Map.of("field", "apr"));
        }
        if (parsed.signum() < 0 || parsed.compareTo(new BigDecimal("1000")) >= 0) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a rate is at least 0% and under 1000%",
                    Map.of("field", "apr"));
        }
        return parsed;
    }

    private static DebtKind kindNamed(String name) {
        if (name == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "a debt has a kind", Map.of("field", "kind"));
        }
        try {
            return DebtKind.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown debt kind", Map.of("field", "kind"));
        }
    }

    /** A balance's date: see {@link DebtDates} — not before 1900, not after today anywhere. */
    private LocalDate requireNotInTheFuture(LocalDate date, String field) {
        return DebtDates.requirePlausible(date, field, balances.now());
    }

    /**
     * The principal the anchor's own date will re-apply, which the anchor therefore has to carry.
     *
     * <p>Zero in the ordinary case — a statement dated after the last payment — so the anchor is
     * the statement figure exactly.
     */
    private Money sameDayPrincipal(Debt debt, LocalDate asOf) {
        BigDecimal paid = payments.principalPaidOnOrAfter(debt.householdId(), debt.id(), asOf);
        return paid == null ? Money.zero(debt.currency()) : Money.of(paid, debt.currency());
    }

    private static DebtPayment annotated(DebtPayment payment, String note) {
        payment.annotate(note);
        return payment;
    }

    private Debt require(UUID debtId, UUID householdId) {
        return debts.findByIdAndHouseholdId(debtId, householdId)
                .orElseThrow(() -> new NotFoundException("no such debt"));
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the debt changed since it was read");
        }
    }

    /**
     * A settled debt takes no new terms.
     *
     * <p>Confirming a balance is the way back: it is the one write that reopens one, because a
     * correction is a household saying the balance came back rather than editing a plan.
     */
    private static void requireNotSettled(Debt debt) {
        if (debt.isPaidOff()) {
            throw new ConflictException(
                    ErrorCode.DEBT_PAID_OFF,
                    "this debt is paid off; confirm a balance to reopen it");
        }
    }

    /**
     * Saves and flushes, turning the degrees-of-freedom constraint's violation into the same {@code
     * 422} the edge gives.
     *
     * <p>The edge check above is the good path; this is the control. {@code
     * ck_debts_degrees_of_freedom} is what still holds for an importer or a caller nobody has
     * written yet, and a {@code 500} is the wrong way to report a rule the API documents.
     */
    private Debt save(Debt debt) {
        try {
            return debts.saveAndFlush(debt);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ck_debts_degrees_of_freedom")) {
                throw new InvalidRequestException(
                        ErrorCode.TERMS_OVER_DETERMINED,
                        "the stored terms over-determine the equation",
                        Map.of("fields", List.of("apr", "payment", "payoffMonth")));
            }
            if (mentions(violation, "ux_debts_bill_id")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "that bill already pays another debt's minimum");
            }
            throw violation;
        }
    }

    /** See {@code BillService.mentions} — matched on our own constraint name, not on text. */
    private static boolean mentions(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The terms, filled in, with the provenance of each figure and the ledger they imply.
     *
     * @param months the projection's rows, empty where there is no projection. Not exposed: the
     *     chart's bounded series is built from it and the 600 rows themselves never leave the
     *     service
     */
    private record Terms(
            Money payment,
            TermSource paymentSource,
            Integer monthsLeft,
            YearMonth payoffMonth,
            TermSource horizonSource,
            Money totalInterest,
            String reason,
            List<ProjectionMonth> months) {}
}
