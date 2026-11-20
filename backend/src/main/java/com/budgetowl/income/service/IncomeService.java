package com.budgetowl.income.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.income.domain.IncomeSource;
import com.budgetowl.income.persistence.IncomeSourceRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Income: money the household expects to arrive on a schedule.
 *
 * <p>Scoping and roles work as everywhere else. Two rules are specific to this service:
 *
 * <ul>
 *   <li><b>Income projects no occurrences.</b> Nobody ticks their salary off; they notice it
 *       arrived. A period's expected income is the schedule counted and multiplied, which is why a
 *       fortnightly wage is worth more in a three-payday month — and why that is the month the
 *       household actually has rather than a figure to be averaged flat.
 *   <li><b>{@code sourceOfTruth} is always {@code MANUAL_ESTIMATE}.</b> {@code AUTO}, derived from
 *       history, needs reporting (slice 13). The field exists now so a derived number and a typed
 *       one never become the same kind of claim.
 * </ul>
 *
 * <p><b>Nothing here logs an amount.</b> An income figure is somebody's salary.
 */
@Service
public class IncomeService {

    /** The cap on a household's income sources. See {@code BillService.MAXIMUM_PER_HOUSEHOLD}. */
    public static final int MAXIMUM_PER_HOUSEHOLD = 200;

    private static final Logger log = LoggerFactory.getLogger(IncomeService.class);

    private final IncomeSourceRepository sources;
    private final AccountRepository accounts;
    private final MembershipService memberships;

    public IncomeService(
            IncomeSourceRepository sources,
            AccountRepository accounts,
            MembershipService memberships) {
        this.sources = sources;
        this.accounts = accounts;
        this.memberships = memberships;
    }

    /** One month's income sources, each with what it is worth that month, plus the total. */
    @Transactional(readOnly = true)
    public IncomeForPeriod list(UUID actorId, YearMonth period, boolean includeInactive) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();

        List<IncomeSource> found = sources.findAllForHousehold(household.id(), includeInactive);
        List<IncomeView> views = views(household, found, period);

        Money total = Money.zero(household.baseCurrency());
        for (IncomeView view : views) {
            // A RETIRED source is not income the household expects, even when the caller asked to
            // see it. Counting it would overstate what there is to spend — and by an amount the
            // size of a salary.
            if (view.active()) {
                total = total.plus(view.expectedForPeriod());
            }
        }
        return new IncomeForPeriod(views, total);
    }

    @Transactional(readOnly = true)
    public IncomeView get(UUID actorId, UUID sourceId, YearMonth period) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        return views(household, List.of(require(sourceId, household.id())), period).getFirst();
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code income-name-taken}
     */
    @Transactional
    public IncomeView create(UUID actorId, NewIncome requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        if (sources.countByHouseholdId(household.id()) >= MAXIMUM_PER_HOUSEHOLD) {
            throw new ConflictException(
                    ErrorCode.CONFLICT,
                    "this household already has the most income sources we allow");
        }

        Currency currency = household.baseCurrency();
        IncomeSource source =
                IncomeSource.of(
                        household,
                        requested.name(),
                        amount(requested.amount(), currency),
                        requested.recurrence(),
                        currency);
        source.landsIn(requireAccountOrNull(requested.accountId(), household.id()));

        IncomeSource saved = saveDistinctly(source);
        log.info(
                "income source created householdId={} incomeSourceId={} byUserId={} schedule={}",
                household.id(),
                saved.id(),
                actorId,
                saved.recurrence().kind());
        return views(household, List.of(saved), null).getFirst();
    }

    @Transactional
    public IncomeView update(UUID actorId, UUID sourceId, IncomeChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        IncomeSource source = require(sourceId, household.id());
        Currency currency = household.baseCurrency();

        requireCurrentVersion(source.version(), changes.version());

        if (changes.name() != null) {
            source.rename(changes.name());
        }
        if (changes.amount() != null) {
            source.expect(amount(changes.amount(), currency), currency);
        }
        if (changes.recurrence() != null) {
            source.rescheduleTo(changes.recurrence());
        }
        switch (changes.accountId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> source.landsIn(null);
            case Change.SetTo<UUID> into ->
                    source.landsIn(requireAccount(into.value(), household.id()).id());
        }
        if (changes.active() != null) {
            if (changes.active()) {
                source.activate();
            } else {
                source.deactivate();
            }
        }

        IncomeSource saved = saveDistinctly(source);
        log.info(
                "income source updated householdId={} incomeSourceId={} byUserId={} active={}",
                household.id(),
                sourceId,
                actorId,
                saved.isActive());
        return views(household, List.of(saved), null).getFirst();
    }

    /**
     * Deletes a source. Nothing in the ledger changes: an income source was never a transaction.
     */
    @Transactional
    public void delete(UUID actorId, UUID sourceId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        IncomeSource source = require(sourceId, householdId);

        sources.delete(source);
        log.info(
                "income source deleted householdId={} incomeSourceId={} byUserId={}",
                householdId,
                sourceId,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * @param period the month to count arrivals in, or null for a source on its own — see {@code
     *     BillService.views} for why a write answers with the second kind
     */
    private List<IncomeView> views(
            Household household, List<IncomeSource> found, YearMonth period) {
        if (found.isEmpty()) {
            return List.of();
        }
        Currency currency = household.baseCurrency();
        Map<UUID, String> accountNames = accountNamesFor(household.id(), found);

        List<IncomeView> views = new ArrayList<>(found.size());
        for (IncomeSource source : found) {
            List<LocalDate> dates =
                    period == null ? List.of() : source.recurrence().datesIn(period);
            Money amount = Money.of(source.amount(), currency);
            // The whole of this feature's income arithmetic: the figure, as many times as it
            // arrives. A fortnightly wage is worth three of itself in a three-payday month.
            Money expected = amount.times(dates.size());
            views.add(
                    new IncomeView(
                            source.id(),
                            source.name(),
                            amount,
                            source.recurrence(),
                            source.accountId().orElse(null),
                            source.accountId().map(accountNames::get).orElse(null),
                            source.sourceOfTruth(),
                            source.isActive(),
                            dates,
                            expected,
                            source.createdAt(),
                            source.updatedAt(),
                            source.version()));
        }
        return views;
    }

    private Map<UUID, String> accountNamesFor(UUID householdId, List<IncomeSource> found) {
        Set<UUID> accountIds = new HashSet<>();
        for (IncomeSource source : found) {
            source.accountId().ifPresent(accountIds::add);
        }
        Map<UUID, String> names = new HashMap<>();
        if (accountIds.isEmpty()) {
            return names;
        }
        for (Account account : accounts.findAllByHouseholdIdAndIdIn(householdId, accountIds)) {
            names.put(account.id(), account.name());
        }
        return names;
    }

    private IncomeSource require(UUID sourceId, UUID householdId) {
        return sources.findByIdAndHouseholdId(sourceId, householdId)
                .orElseThrow(() -> new NotFoundException("no such income source"));
    }

    private Account requireAccount(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    private UUID requireAccountOrNull(UUID accountId, UUID householdId) {
        return accountId == null ? null : requireAccount(accountId, householdId).id();
    }

    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the income source changed since it was read");
        }
    }

    private IncomeSource saveDistinctly(IncomeSource source) {
        try {
            return sources.saveAndFlush(source);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_income_sources_household_name")) {
                throw new ConflictException(
                        ErrorCode.INCOME_NAME_TAKEN,
                        "an income source with this name already exists");
            }
            throw violation;
        }
    }

    /** See {@code EnvelopeService.mentions} — matched on our own constraint name, not on text. */
    private static boolean mentions(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private static Money amount(String text, Currency currency) {
        return AmountText.parse(text, currency, AmountText.Sign.NOT_NEGATIVE, "amount");
    }
}
