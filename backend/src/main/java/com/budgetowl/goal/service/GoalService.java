package com.budgetowl.goal.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.goal.domain.Goal;
import com.budgetowl.goal.domain.GoalContribution;
import com.budgetowl.goal.domain.GoalContributionStep;
import com.budgetowl.goal.persistence.GoalContributionRepository;
import com.budgetowl.goal.persistence.GoalContributionStepRepository;
import com.budgetowl.goal.persistence.GoalPeriodTotal;
import com.budgetowl.goal.persistence.GoalRepository;
import com.budgetowl.goal.service.GoalCommands.ContributionChanges;
import com.budgetowl.goal.service.GoalCommands.DraftGoal;
import com.budgetowl.goal.service.GoalCommands.GoalChanges;
import com.budgetowl.goal.service.GoalCommands.GoalPreview;
import com.budgetowl.goal.service.GoalCommands.NewContribution;
import com.budgetowl.goal.service.GoalCommands.NewGoal;
import com.budgetowl.goal.service.GoalViews.ContributionView;
import com.budgetowl.goal.service.GoalViews.DraftView;
import com.budgetowl.goal.service.GoalViews.GoalView;
import com.budgetowl.goal.service.GoalViews.Page;
import com.budgetowl.goal.service.GoalViews.SetAside;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.transaction.persistence.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goals: what a household is saving for, how far along it is, and when it gets there.
 *
 * <p>Scoping and roles work as everywhere else. Membership is resolved first and every repository
 * call is given that household's id; another household's row is a {@code 404}. <b>On every write
 * the role is checked before the goal is looked up</b>, so a {@code VIEWER} sending an unknown id
 * gets {@code 403} and learns nothing about which ids exist (threat model, attack 3). The database
 * carries the same scoping structurally: every foreign key is composite on {@code (id,
 * household_id)}.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>Progress comes from recorded contributions</b> (decision 1). Recording one writes no
 *       transaction and moves no account balance (ADR-0035's reasoning). {@code saved}, {@code
 *       remaining} and {@code reached} are derived here and stored nowhere.
 *   <li><b>The plan is effective-dated</b> (decision 4) and changes from the current period
 *       forwards only, so a past month's left-over figure is never rewritten.
 *   <li><b>One rule for a month's contribution</b>, {@link GoalLedger#monthContribution}, read by
 *       the list, the detail page and the period summary alike.
 *   <li><b>Nothing is recorded on its own.</b> There is no job and no scheduler: every figure in
 *       {@code saved} traces to something a person typed.
 * </ul>
 *
 * <p><b>Nothing here logs a name, an amount or a note.</b> Audit lines carry ids.
 */
@Service
public class GoalService {

    /**
     * Goals per household, archived ones included. A cost control (threat model, attack 6): every
     * list read and every period summary projects every goal. Mirrors the debt cap.
     */
    public static final int MAXIMUM_PER_HOUSEHOLD = 50;

    /** Contributions per goal. Bounds the {@code SUM} behind every figure, as the debt cap does. */
    public static final int MAXIMUM_CONTRIBUTIONS_PER_GOAL = 2_000;

    /**
     * How far ahead a plan change may start. Refusing the past alone did not bound the plan: a
     * member could chain changes month by month to year 9999, and every list read and period
     * summary loads every step (slice-11 security audit, medium 1). The app offers this month or
     * next; two years leaves room for a client that plans ahead.
     */
    public static final int MAXIMUM_PLAN_LEAD_MONTHS = 24;

    /**
     * Plan steps per goal, kept history included. With the lead capped, a plan grows by at most one
     * step a month of real time; this is the backstop if that ever stops being true.
     */
    public static final int MAXIMUM_PLAN_STEPS_PER_GOAL = 240;

    /**
     * The window a target month must fall in. Not a product rule: a year outside it is a request
     * with no meaning in it, and an unbounded one reaches the database's date range.
     */
    private static final int EARLIEST_YEAR = 1900;

    private static final int LATEST_YEAR = 9999;

    private static final Logger log = LoggerFactory.getLogger(GoalService.class);

    private final MembershipService memberships;
    private final GoalRepository goals;
    private final GoalContributionStepRepository steps;
    private final GoalContributionRepository contributions;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Clock clock;

    public GoalService(
            MembershipService memberships,
            GoalRepository goals,
            GoalContributionStepRepository steps,
            GoalContributionRepository contributions,
            AccountRepository accounts,
            TransactionRepository transactions,
            Clock clock) {
        this.memberships = memberships;
        this.goals = goals;
        this.steps = steps;
        this.contributions = contributions;
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------ reads

    /** Every goal, each with its figures, in the list's fixed order. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<GoalView> list(UUID actorId, boolean includeArchived, int page, int size) {
        Household household = memberships.requireMembership(actorId).household();
        org.springframework.data.domain.Page<Goal> found =
                goals.findForHousehold(household.id(), includeArchived, pageable(page, size));
        return new Page<>(
                views(household.id(), found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public GoalView get(UUID actorId, UUID goalId) {
        Household household = memberships.requireMembership(actorId).household();
        return view(household.id(), require(goalId, household.id()));
    }

    /**
     * The fifth term of the left-over figure for {@code period}: {@code Σ monthContribution(g, P)}
     * over unarchived goals in the household's base currency.
     *
     * <p>Archived goals are excluded, as envelopes are: a retired goal is not part of the month's
     * plan. Goals in another currency are excluded for the reason {@code owed} is base-currency
     * only (ADR-0022): a combined figure would need a rate the instance may not have.
     *
     * <p>The starting amount is in no month: it was set aside before tracking began, and {@link
     * GoalLedger#monthContribution} never reads it.
     *
     * @throws com.budgetowl.common.ForbiddenException if the caller is not a member. A {@code
     *     VIEWER} may read this, as it may read everything
     */
    @Transactional(readOnly = true)
    public SetAside setAsideFor(UUID actorId, YearMonth period) {
        Household household = memberships.requireMembership(actorId).household();
        Currency base = household.baseCurrency();
        List<Goal> counted = goals.findUnarchivedInCurrency(household.id(), base.getCurrencyCode());
        Money total = Money.zero(base);
        for (GoalLedger ledger : ledgers(household.id(), counted).values()) {
            total = total.plus(ledger.monthContribution(period));
        }
        return new SetAside(total, counted.size());
    }

    /** The plan's steps, oldest first. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<PlanStep> plan(UUID actorId, UUID goalId, int page, int size) {
        Household household = memberships.requireMembership(actorId).household();
        Goal goal = require(goalId, household.id());
        org.springframework.data.domain.Page<GoalContributionStep> found =
                steps.findPlan(household.id(), goal.id(), pageable(page, size));
        List<PlanStep> content = new ArrayList<>(found.getNumberOfElements());
        for (GoalContributionStep step : found.getContent()) {
            content.add(
                    new PlanStep(
                            step.fromPeriod(), Money.of(step.monthlyAmount(), goal.currency())));
        }
        return new Page<>(
                List.copyOf(content),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /** The history, newest first, in the server's order. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<ContributionView> history(UUID actorId, UUID goalId, int page, int size) {
        Household household = memberships.requireMembership(actorId).household();
        // Resolved first so that a goal belonging to nobody the caller is a member of is a 404 for
        // the goal rather than an empty history, which would read as "nothing saved".
        Goal goal = require(goalId, household.id());
        org.springframework.data.domain.Page<GoalContribution> found =
                contributions.findHistory(household.id(), goal.id(), pageable(page, size));
        List<ContributionView> content = new ArrayList<>(found.getNumberOfElements());
        for (GoalContribution contribution : found.getContent()) {
            content.add(contributionView(goal, contribution));
        }
        return new Page<>(
                List.copyOf(content),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    // ----------------------------------------------------------------------------------- writes

    /**
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code goal-limit-reached}, {@code goal-name-taken}
     * @throws NotFoundException if the account is not this household's
     * @throws InvalidRequestException {@code validation-failed} naming the field
     */
    @Transactional
    public GoalView create(UUID actorId, NewGoal requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        if (goals.countByHouseholdId(household.id()) >= MAXIMUM_PER_HOUSEHOLD) {
            throw new ConflictException(
                    ErrorCode.GOAL_LIMIT_REACHED,
                    "a household is limited to " + MAXIMUM_PER_HOUSEHOLD + " goals");
        }

        Account account = null;
        Currency currency;
        if (requested.accountId() != null) {
            if (requested.currency() != null) {
                // The account owns that fact. Accepting both and ignoring one would leave a client
                // that believed it had set a currency reading every figure in the wrong unit.
                throw invalid(
                        "a goal kept in an account is in that account's currency", "currency");
            }
            account = requireAccount(requested.accountId(), household.id());
            currency = account.currency();
        } else {
            currency =
                    requested.currency() == null
                            ? household.baseCurrency()
                            : currencyNamed(requested.currency());
        }

        Goal goal =
                Goal.of(
                        household,
                        requested.name(),
                        amount(
                                requested.targetAmount(),
                                currency,
                                AmountText.Sign.POSITIVE,
                                "targetAmount"));
        goal.aimFor(withinRange(requested.targetMonth(), "targetMonth"));
        if (requested.startingAmount() != null) {
            goal.startFrom(
                    amount(
                            requested.startingAmount(),
                            currency,
                            AmountText.Sign.NOT_NEGATIVE,
                            "startingAmount"));
        }
        goal.keptIn(account == null ? null : account.id());

        Goal saved = saveDistinctly(goal);
        if (requested.monthlyContribution() != null) {
            steps.saveAndFlush(
                    GoalContributionStep.from(
                            saved,
                            currentPeriod(),
                            amount(
                                    requested.monthlyContribution(),
                                    currency,
                                    AmountText.Sign.NOT_NEGATIVE,
                                    "monthlyContribution")));
        }
        log.info(
                "goal created householdId={} goalId={} byUserId={}",
                household.id(),
                saved.id(),
                actorId);
        return view(household.id(), saved);
    }

    /**
     * A partial update. Three-way on {@code targetMonth} and {@code accountId}: absent is unchanged
     * and {@code null} clears. The currency never changes.
     *
     * @throws ConflictException {@code stale-version}, {@code goal-name-taken}
     * @throws InvalidRequestException {@code goal-currency-mismatch} ({@code 422}) on linking an
     *     account in another currency
     */
    @Transactional
    public GoalView update(UUID actorId, UUID goalId, GoalChanges changes) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        requireCurrentVersion(goal.version(), changes.version(), "goal");

        apply(goal, changes.name(), changes.targetAmount(), changes.startingAmount());
        switch (changes.targetMonth()) {
            case Change.Unchanged<YearMonth> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<YearMonth> ignored -> goal.aimFor(null);
            case Change.SetTo<YearMonth> month ->
                    goal.aimFor(withinRange(month.value(), "targetMonth"));
        }
        switch (changes.accountId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> goal.keptIn(null);
            case Change.SetTo<UUID> linked -> {
                Account account = requireAccount(linked.value(), household.id());
                if (!account.currency().equals(goal.currency())) {
                    throw new InvalidRequestException(
                            ErrorCode.GOAL_CURRENCY_MISMATCH,
                            "the account is in a different currency from the goal",
                            Map.of("field", "accountId"));
                }
                goal.keptIn(account.id());
            }
        }

        Goal saved = saveDistinctly(goal);
        log.info(
                "goal updated householdId={} goalId={} byUserId={}",
                household.id(),
                goalId,
                actorId);
        return view(household.id(), saved);
    }

    @Transactional
    public GoalView archive(UUID actorId, UUID goalId) {
        return setArchived(actorId, goalId, true);
    }

    /**
     * @throws ConflictException {@code goal-name-taken} if an unarchived goal now has the name
     */
    @Transactional
    public GoalView unarchive(UUID actorId, UUID goalId) {
        return setArchived(actorId, goalId, false);
    }

    /**
     * Deletes a goal, its plan and its contributions. <b>No transaction goes with it</b>: a
     * contribution only ever pointed at one, as evidence. Permitted with history, because archive
     * is the ordinary path and this is the deliberate one.
     */
    @Transactional
    public void delete(UUID actorId, UUID goalId) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        goals.delete(goal);
        log.info(
                "goal deleted householdId={} goalId={} byUserId={}",
                household.id(),
                goalId,
                actorId);
    }

    /**
     * "From {@code fromPeriod} on, the plan is this amount" — every later step replaced. Sending
     * the same instruction twice leaves the same plan.
     *
     * @throws InvalidRequestException {@code goal-plan-period-past} ({@code 422}) for a month
     *     before the current one
     * @throws ConflictException {@code goal-archived}, {@code stale-version} on the goal's version
     */
    @Transactional
    public GoalView setPlan(
            UUID actorId, UUID goalId, YearMonth fromPeriod, String monthlyAmount, long version) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        requireCurrentVersion(goal.version(), version, "goal");
        requireNotArchived(goal);
        withinRange(fromPeriod, "fromPeriod");
        if (fromPeriod.isBefore(currentPeriod())) {
            throw new InvalidRequestException(
                    ErrorCode.GOAL_PLAN_PERIOD_PAST,
                    "the plan changes from the current month forwards; correct a past month with a"
                            + " contribution",
                    Map.of("field", "fromPeriod"));
        }
        if (fromPeriod.isAfter(currentPeriod().plusMonths(MAXIMUM_PLAN_LEAD_MONTHS))) {
            throw new InvalidRequestException(
                    ErrorCode.GOAL_PLAN_PERIOD_TOO_FAR,
                    "the plan can change at most "
                            + MAXIMUM_PLAN_LEAD_MONTHS
                            + " months ahead of the current month",
                    Map.of("field", "fromPeriod"));
        }
        Money amount =
                amount(
                        monthlyAmount,
                        goal.currency(),
                        AmountText.Sign.NOT_NEGATIVE,
                        "monthlyAmount");

        GoalLedger ledger = ledgers(household.id(), List.of(goal)).get(goal.id());
        List<PlanStep> planned = ledger.stepsWith(fromPeriod, amount);
        if (!planned.equals(ledger.steps())) {
            if (planned.size() > MAXIMUM_PLAN_STEPS_PER_GOAL) {
                throw new ConflictException(
                        ErrorCode.GOAL_LIMIT_REACHED,
                        "a goal's plan is limited to " + MAXIMUM_PLAN_STEPS_PER_GOAL + " steps");
            }
            steps.deleteFrom(household.id(), goal.id(), fromPeriod.atDay(1));
            for (PlanStep step : planned) {
                if (!step.fromPeriod().isBefore(fromPeriod)) {
                    steps.saveAndFlush(
                            GoalContributionStep.from(
                                    goal, step.fromPeriod(), step.monthlyAmount()));
                }
            }
            goal.planChanged(Instant.now(clock));
            goal = goals.saveAndFlush(goal);
        }
        log.info(
                "goal plan set householdId={} goalId={} byUserId={} fromPeriod={}",
                household.id(),
                goalId,
                actorId,
                fromPeriod);
        return view(household.id(), goal);
    }

    /**
     * Records money set aside, or — negative — taken out.
     *
     * @throws ConflictException {@code goal-archived}, {@code goal-limit-reached}, {@code conflict}
     *     for a transaction already attached to a contribution
     * @throws NotFoundException if the transaction is not this household's
     */
    @Transactional
    public ContributionView contribute(UUID actorId, UUID goalId, NewContribution requested) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        requireNotArchived(goal);
        if (contributions.countByHouseholdIdAndGoalId(household.id(), goal.id())
                >= MAXIMUM_CONTRIBUTIONS_PER_GOAL) {
            throw new ConflictException(
                    ErrorCode.GOAL_LIMIT_REACHED,
                    "a goal is limited to " + MAXIMUM_CONTRIBUTIONS_PER_GOAL + " contributions");
        }

        GoalContribution contribution =
                GoalContribution.to(
                        goal,
                        requireNoLaterThanTomorrow(requested.date()),
                        amount(
                                requested.amount(),
                                goal.currency(),
                                AmountText.Sign.NON_ZERO,
                                "amount"));
        contribution.annotate(requested.note());
        if (requested.transactionId() != null) {
            contribution.evidencedBy(
                    requireUnattachedTransaction(requested.transactionId(), household.id(), null));
        }

        GoalContribution saved = saveContribution(contribution);
        log.info(
                "goal contribution recorded householdId={} goalId={} contributionId={}"
                        + " byUserId={}",
                household.id(),
                goalId,
                saved.id(),
                actorId);
        return contributionView(goal, saved);
    }

    /**
     * Corrects a contribution. Three-way on {@code note} and {@code transactionId}. A contribution
     * of the right household but another goal is a {@code 404} (threat model, attack 4).
     */
    @Transactional
    public ContributionView correct(
            UUID actorId, UUID goalId, UUID contributionId, ContributionChanges changes) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        GoalContribution contribution = requireContribution(contributionId, goal, household.id());
        requireCurrentVersion(contribution.version(), changes.version(), "contribution");

        if (changes.date() != null) {
            contribution.on(requireNoLaterThanTomorrow(changes.date()));
        }
        if (changes.amount() != null) {
            contribution.correctAmount(
                    amount(changes.amount(), goal.currency(), AmountText.Sign.NON_ZERO, "amount"),
                    goal);
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> contribution.annotate(null);
            case Change.SetTo<String> written -> contribution.annotate(written.value());
        }
        switch (changes.transactionId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> contribution.evidencedBy(null);
            case Change.SetTo<UUID> linked ->
                    contribution.evidencedBy(
                            requireUnattachedTransaction(
                                    linked.value(), household.id(), contribution.id()));
        }

        GoalContribution saved = saveContribution(contribution);
        log.info(
                "goal contribution corrected householdId={} goalId={} contributionId={}"
                        + " byUserId={}",
                household.id(),
                goalId,
                contributionId,
                actorId);
        return contributionView(goal, saved);
    }

    /** Removes one contribution. The transaction it pointed at, if any, is untouched. */
    @Transactional
    public void removeContribution(UUID actorId, UUID goalId, UUID contributionId) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        contributions.delete(requireContribution(contributionId, goal, household.id()));
        log.info(
                "goal contribution removed householdId={} goalId={} contributionId={}"
                        + " byUserId={}",
                household.id(),
                goalId,
                contributionId,
                actorId);
    }

    // --------------------------------------------------------------------------------- previews

    /**
     * What this goal would report with the plan, target, month or starting amount substituted.
     * <b>Writes nothing.</b> Built from the same ledger and the same {@link GoalLedger#stepsWith}
     * the writes use, so a preview cannot disagree with what saving then reports.
     *
     * <p>A {@code POST}, so the amount travels in a body rather than a URL every layer logs; and
     * therefore a write as far as roles go, refused to a {@code VIEWER}, who never sees the dialogs
     * it serves.
     */
    @Transactional(readOnly = true)
    public GoalView preview(UUID actorId, UUID goalId, GoalPreview requested) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        Currency currency = goal.currency();
        GoalLedger ledger = ledgers(household.id(), List.of(goal)).get(goal.id());

        Money target =
                requested.targetAmount() == null
                        ? ledger.target()
                        : amount(
                                requested.targetAmount(),
                                currency,
                                AmountText.Sign.POSITIVE,
                                "targetAmount");
        Money start =
                requested.startingAmount() == null
                        ? ledger.startingAmount()
                        : amount(
                                requested.startingAmount(),
                                currency,
                                AmountText.Sign.NOT_NEGATIVE,
                                "startingAmount");
        YearMonth month =
                switch (requested.targetMonth()) {
                    case Change.Unchanged<YearMonth> ignored -> ledger.targetMonth();
                    case Change.Cleared<YearMonth> ignored -> null;
                    case Change.SetTo<YearMonth> set -> withinRange(set.value(), "targetMonth");
                };
        List<PlanStep> plan =
                requested.monthlyAmount() == null
                        ? ledger.steps()
                        : ledger.stepsWith(
                                currentPeriod(),
                                amount(
                                        requested.monthlyAmount(),
                                        currency,
                                        AmountText.Sign.NOT_NEGATIVE,
                                        "monthlyAmount"));
        GoalLedger substituted = new GoalLedger(target, start, month, plan, ledger.recorded());

        return new GoalView(
                goal.id(),
                goal.name(),
                currency,
                target,
                month,
                start,
                goal.accountId().orElse(null),
                goal.isArchived(),
                GoalProjection.figures(substituted, currentPeriod()),
                goal.createdAt(),
                goal.updatedAt(),
                goal.version());
    }

    /**
     * Pure arithmetic for the add form, before a goal exists. <b>Reads no household data</b> and
     * writes nothing, so it discloses nothing because it knows nothing. A {@code POST} for the
     * reason {@link #preview} is.
     */
    @Transactional(readOnly = true)
    public DraftView previewDraft(UUID actorId, DraftGoal draft) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Currency currency =
                draft.currency() == null
                        ? household.baseCurrency()
                        : currencyNamed(draft.currency());
        Money start =
                draft.startingAmount() == null
                        ? Money.zero(currency)
                        : amount(
                                draft.startingAmount(),
                                currency,
                                AmountText.Sign.NOT_NEGATIVE,
                                "startingAmount");
        List<PlanStep> plan =
                draft.monthlyAmount() == null
                        ? List.of()
                        : List.of(
                                new PlanStep(
                                        currentPeriod(),
                                        amount(
                                                draft.monthlyAmount(),
                                                currency,
                                                AmountText.Sign.NOT_NEGATIVE,
                                                "monthlyAmount")));
        GoalLedger ledger =
                new GoalLedger(
                        amount(
                                draft.targetAmount(),
                                currency,
                                AmountText.Sign.POSITIVE,
                                "targetAmount"),
                        start,
                        withinRange(draft.targetMonth(), "targetMonth"),
                        plan,
                        Map.of());
        return new DraftView(currency, GoalProjection.figures(ledger, currentPeriod()));
    }

    // -------------------------------------------------------------------------------- internals

    private GoalView setArchived(UUID actorId, UUID goalId, boolean archived) {
        Household household = memberships.requireWriteAccess(actorId).household();
        Goal goal = require(goalId, household.id());
        if (archived) {
            goal.archive();
        } else {
            goal.unarchive();
        }
        Goal saved = saveDistinctly(goal);
        log.info(
                "goal {} householdId={} goalId={} byUserId={}",
                archived ? "archived" : "unarchived",
                household.id(),
                goalId,
                actorId);
        return view(household.id(), saved);
    }

    private void apply(Goal goal, String name, String targetAmount, String startingAmount) {
        if (name != null) {
            goal.rename(name);
        }
        if (targetAmount != null) {
            goal.retarget(
                    amount(
                            targetAmount,
                            goal.currency(),
                            AmountText.Sign.POSITIVE,
                            "targetAmount"));
        }
        if (startingAmount != null) {
            goal.startFrom(
                    amount(
                            startingAmount,
                            goal.currency(),
                            AmountText.Sign.NOT_NEGATIVE,
                            "startingAmount"));
        }
    }

    private GoalView view(UUID householdId, Goal goal) {
        return views(householdId, List.of(goal)).getFirst();
    }

    /** Views in the order given, with every goal's ledger read in two batched queries. */
    private List<GoalView> views(UUID householdId, List<Goal> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, GoalLedger> ledgers = ledgers(householdId, found);
        YearMonth current = currentPeriod();
        List<GoalView> views = new ArrayList<>(found.size());
        for (Goal goal : found) {
            GoalLedger ledger = ledgers.get(goal.id());
            views.add(
                    new GoalView(
                            goal.id(),
                            goal.name(),
                            goal.currency(),
                            goal.targetAmount(),
                            goal.targetMonth().orElse(null),
                            goal.startingAmount(),
                            goal.accountId().orElse(null),
                            goal.isArchived(),
                            GoalProjection.figures(ledger, current),
                            goal.createdAt(),
                            goal.updatedAt(),
                            goal.version()));
        }
        return List.copyOf(views);
    }

    /**
     * Each goal's ledger: its plan in one query and its contributions, summed per month, in one
     * more. <b>The only way figures are read</b>, for a list, a detail page and the summary alike.
     */
    private Map<UUID, GoalLedger> ledgers(UUID householdId, Collection<Goal> found) {
        Map<UUID, GoalLedger> ledgers = new HashMap<>();
        if (found.isEmpty()) {
            return ledgers;
        }
        List<UUID> ids = found.stream().map(Goal::id).toList();
        Map<UUID, Goal> byId = new HashMap<>();
        Map<UUID, List<PlanStep>> plans = new HashMap<>();
        Map<UUID, Map<YearMonth, Money>> recorded = new HashMap<>();
        for (Goal goal : found) {
            byId.put(goal.id(), goal);
            plans.put(goal.id(), new ArrayList<>());
            recorded.put(goal.id(), new HashMap<>());
        }
        for (GoalContributionStep step : steps.findForGoals(householdId, ids)) {
            Goal goal = byId.get(step.goalId());
            plans.get(goal.id())
                    .add(
                            new PlanStep(
                                    step.fromPeriod(),
                                    Money.of(step.monthlyAmount(), goal.currency())));
        }
        for (GoalPeriodTotal total : contributions.totalsByPeriod(householdId, ids)) {
            Goal goal = byId.get(total.goalId());
            recorded.get(goal.id())
                    .put(YearMonth.from(total.period()), Money.of(total.total(), goal.currency()));
        }
        for (Goal goal : found) {
            ledgers.put(
                    goal.id(),
                    new GoalLedger(
                            goal.targetAmount(),
                            goal.startingAmount(),
                            goal.targetMonth().orElse(null),
                            plans.get(goal.id()),
                            recorded.get(goal.id())));
        }
        return ledgers;
    }

    private static ContributionView contributionView(Goal goal, GoalContribution contribution) {
        return new ContributionView(
                contribution.id(),
                contribution.goalId(),
                contribution.contributedOn(),
                contribution.period(),
                Money.of(contribution.amount(), goal.currency()),
                contribution.note().orElse(null),
                contribution.transactionId().orElse(null),
                contribution.createdAt(),
                contribution.updatedAt(),
                contribution.version());
    }

    private Goal require(UUID goalId, UUID householdId) {
        return goals.findByIdAndHouseholdId(goalId, householdId)
                .orElseThrow(() -> new NotFoundException("no such goal"));
    }

    private GoalContribution requireContribution(UUID contributionId, Goal goal, UUID householdId) {
        return contributions
                .findInGoal(contributionId, goal.id(), householdId)
                .orElseThrow(() -> new NotFoundException("no such contribution"));
    }

    /**
     * The account a goal is kept in, resolved through the household (threat model, attack 1). An
     * archived account is accepted: a retired container does not unmake the goal.
     */
    private Account requireAccount(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    /**
     * The transaction a contribution points at, resolved through the household (threat model,
     * attack 2), and attached to no other contribution. The look-up is the readable answer; {@code
     * ux_goal_contributions_transaction_id} is the control.
     *
     * @param self the contribution being corrected, which may keep its own link; null on create
     */
    private UUID requireUnattachedTransaction(UUID transactionId, UUID householdId, UUID self) {
        UUID resolved =
                transactions
                        .findByIdAndHouseholdId(transactionId, householdId)
                        .orElseThrow(() -> new NotFoundException("no such transaction"))
                        .id();
        contributions
                .findByTransaction(householdId, resolved)
                .filter(existing -> !existing.id().equals(self))
                .ifPresent(
                        existing -> {
                            throw alreadyAttached();
                        });
        return resolved;
    }

    private static ConflictException alreadyAttached() {
        return new ConflictException(
                ErrorCode.CONFLICT, "this transaction is already attached to a contribution");
    }

    private static void requireNotArchived(Goal goal) {
        if (goal.isArchived()) {
            throw new ConflictException(
                    ErrorCode.GOAL_ARCHIVED, "the goal is archived; restore it to change it");
        }
    }

    /** See {@code AccountService.requireCurrentVersion}: the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected, String what) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the " + what + " changed since it was read");
        }
    }

    /**
     * A contribution is a record of something done, so it is dated no later than tomorrow on the
     * server's clock. The one day of slack is for a household east of the server at half past
     * midnight. Intent belongs in the plan.
     */
    private LocalDate requireNoLaterThanTomorrow(LocalDate date) {
        if (date == null) {
            throw invalid("a date is required", "date");
        }
        if (date.isAfter(LocalDate.now(clock).plusDays(1))) {
            throw invalid("a contribution cannot be dated in the future", "date");
        }
        if (date.getYear() < EARLIEST_YEAR) {
            throw invalid("a contribution's date is out of range", "date");
        }
        return date;
    }

    private static YearMonth withinRange(YearMonth month, String field) {
        if (month != null && (month.getYear() < EARLIEST_YEAR || month.getYear() > LATEST_YEAR)) {
            throw invalid(
                    "a month must be between " + EARLIEST_YEAR + " and " + LATEST_YEAR, field);
        }
        return month;
    }

    private YearMonth currentPeriod() {
        return YearMonth.now(clock);
    }

    /** Saves and flushes, turning the name index's violation into {@code 409 goal-name-taken}. */
    private Goal saveDistinctly(Goal goal) {
        try {
            return goals.saveAndFlush(goal);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_goals_household_name_unarchived")) {
                throw new ConflictException(
                        ErrorCode.GOAL_NAME_TAKEN, "a goal with this name already exists");
            }
            throw violation;
        }
    }

    /**
     * Saves a contribution, turning the transaction index's violation into a {@code 409}: two
     * members attaching the same transaction at once both pass the look-up, and one of them still
     * has to be told.
     */
    private GoalContribution saveContribution(GoalContribution contribution) {
        try {
            return contributions.saveAndFlush(contribution);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_goal_contributions_transaction_id")) {
                throw alreadyAttached();
            }
            throw violation;
        }
    }

    /** Matched on our own constraint name, never on driver text. See {@code EnvelopeService}. */
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
     * Parses an amount and refuses one more precise than the goal's currency: a scale-2 amount for
     * a yen goal is a {@code 400}, not a figure rounded behind somebody's back.
     */
    private static Money amount(
            String text, Currency currency, AmountText.Sign sign, String field) {
        Money parsed = AmountText.parse(text, currency, sign, field);
        int allowed = GoalProjection.minorUnitScale(currency);
        if (new java.math.BigDecimal(text.strip()).stripTrailingZeros().scale() > allowed) {
            throw invalid(
                    "an amount in "
                            + currency.getCurrencyCode()
                            + " has at most "
                            + allowed
                            + " decimal places",
                    field);
        }
        return parsed;
    }

    private static Currency currencyNamed(String code) {
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("unknown currency", "currency");
        }
    }

    private static InvalidRequestException invalid(String detail, String field) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }
}
