package com.budgetowl.bill.service;

import com.budgetowl.bill.domain.Bill;
import com.budgetowl.bill.domain.BillOccurrence;
import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.domain.StatusSource;
import com.budgetowl.bill.persistence.BillOccurrenceRepository;
import com.budgetowl.bill.persistence.BillRepository;
import com.budgetowl.category.domain.Category;
import com.budgetowl.category.persistence.CategoryRepository;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.schedule.ScheduleKind;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.math.BigDecimal;
import java.time.Clock;
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
 * Bills: what a household expects to pay, and what it says it paid.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes, another household's row a {@code 404}.
 * The database carries the same rule structurally: the bill, category and transaction foreign keys
 * are composite on {@code (id, household_id)}.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>Nothing here writes a transaction</b> (ADR-0025). Marking a bill paid records a plan
 *       fact. Only a real transaction is ever an expense, and this app is not the authority on
 *       whether money moved.
 *   <li><b>Occurrences are projected, not generated</b> (ADR-0032). Listing a period computes them
 *       from each bill's schedule and writes nothing; a row appears only when somebody records
 *       something. There is no job and no clock in the write path.
 *   <li><b>Overdue is derived per request.</b> It is the only thing here that reads the clock, so
 *       an instance that was switched off for a month is correct the moment it comes back.
 *   <li><b>A retired bill keeps its history and takes no new records.</b> It is retired, not
 *       erased.
 * </ul>
 *
 * <p><b>Nothing here logs an amount.</b> A bill's figure is somebody's rent. Audit lines carry ids,
 * dates and statuses.
 */
@Service
public class BillService {

    /**
     * The cap on a household's bills.
     *
     * <p>The list is not paged — the Bills &amp; Income page shows every bill and its headline is a
     * sum over all of them, so paging would make that figure either wrong or a second query. The
     * cap is what makes not paging safe rather than merely convenient.
     */
    public static final int MAXIMUM_PER_HOUSEHOLD = 200;

    /**
     * The cap on rows recorded against one bill.
     *
     * <p>An {@code IRREGULAR} bill accepts a record against <b>any</b> date, because it projects
     * nothing and recording is what brings its occurrences into being (ADR-0032). That is the right
     * design and it is also an unbounded write: a caller holding write access could walk dates and
     * add a row per request until the disk filled — on a machine in somebody's house. The bound is
     * generous enough that no real household meets it (a weekly bill recorded without a gap for
     * ninety years) and small enough to be a bound.
     */
    public static final int MAXIMUM_OCCURRENCES_PER_BILL = 5_000;

    private static final Logger log = LoggerFactory.getLogger(BillService.class);

    private final BillRepository bills;
    private final BillOccurrenceRepository occurrences;
    private final CategoryRepository categories;
    private final MembershipService memberships;
    private final Clock clock;

    public BillService(
            BillRepository bills,
            BillOccurrenceRepository occurrences,
            CategoryRepository categories,
            MembershipService memberships,
            Clock clock) {
        this.bills = bills;
        this.occurrences = occurrences;
        this.categories = categories;
        this.memberships = memberships;
        this.clock = clock;
    }

    // ----------------------------------------------------------------------------------- bills

    /**
     * One month's bills, each with the occurrences its schedule implies, plus the month's total.
     */
    @Transactional(readOnly = true)
    public BillsForPeriod list(UUID actorId, YearMonth period, boolean includeInactive) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();

        List<Bill> found = bills.findAllForHousehold(household.id(), includeInactive);
        List<BillView> views = views(household, found, period);

        Currency currency = household.baseCurrency();
        Money total = Money.zero(currency);
        Money outstanding = Money.zero(currency);
        Money overdue = Money.zero(currency);
        for (BillView view : views) {
            // A RETIRED bill contributes nothing to the month's figures even when the caller asked
            // to see it. It projects no occurrences, so in practice its only rows are the ones
            // recorded before it was retired — and a period where it was genuinely paid keeps
            // counting it, which is why this is a loop over occurrences and not over bills.
            for (OccurrenceView occurrence : view.occurrences()) {
                switch (occurrence.status()) {
                    case SKIPPED -> {
                        // It did not apply. Not an unpaid bill and not a gap.
                    }
                    case PAID -> total = total.plus(occurrence.actualAmount());
                    case DUE -> {
                        total = total.plus(occurrence.expectedAmount());
                        outstanding = outstanding.plus(occurrence.expectedAmount());
                        if (occurrence.overdue()) {
                            overdue = overdue.plus(occurrence.expectedAmount());
                        }
                    }
                }
            }
        }
        return new BillsForPeriod(views, total, outstanding, overdue);
    }

    @Transactional(readOnly = true)
    public BillView get(UUID actorId, UUID billId, YearMonth period) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        return views(household, List.of(require(billId, household.id())), period).getFirst();
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code bill-name-taken}, {@code bill-limit-reached}
     */
    @Transactional
    public BillView create(UUID actorId, NewBill requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        if (bills.countByHouseholdId(household.id()) >= MAXIMUM_PER_HOUSEHOLD) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "this household already has the most bills we allow");
        }

        Currency currency = household.baseCurrency();
        Bill bill =
                Bill.of(
                        household,
                        requested.name(),
                        amount(requested.expectedAmount(), currency),
                        requested.recurrence(),
                        currency);
        bill.fileUnder(requireCategoryOrNull(requested.categoryId(), household.id()));
        bill.variesBy(
                requested.amountVaries(),
                tolerance(requested.amountVaries(), requested.tolerance(), currency));

        Bill saved = saveDistinctly(bill);
        log.info(
                "bill created householdId={} billId={} byUserId={} schedule={}",
                household.id(),
                saved.id(),
                actorId,
                saved.recurrence().kind());
        return views(household, List.of(saved), null).getFirst();
    }

    /**
     * A partial update. The schedule and the varies/tolerance pair each move wholesale.
     *
     * <p>Changing the schedule re-projects periods nobody recorded anything against, the past
     * included. ADR-0032 explains why that is the right trade and how a settled period is kept.
     */
    @Transactional
    public BillView update(UUID actorId, UUID billId, BillChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Bill bill = require(billId, household.id());
        Currency currency = household.baseCurrency();

        requireCurrentVersion(bill.version(), changes.version(), "bill");

        if (changes.name() != null) {
            bill.rename(changes.name());
        }
        if (changes.expectedAmount() != null) {
            bill.expect(amount(changes.expectedAmount(), currency), currency);
        }
        if (changes.recurrence() != null) {
            bill.rescheduleTo(changes.recurrence());
        }
        switch (changes.categoryId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> bill.fileUnder(null);
            case Change.SetTo<UUID> filed ->
                    bill.fileUnder(requireCategory(filed.value(), household.id()).id());
        }
        if (changes.amountVaries() != null) {
            bill.variesBy(
                    changes.amountVaries(),
                    tolerance(changes.amountVaries(), changes.tolerance(), currency));
        } else if (changes.tolerance() != null) {
            // A tolerance without saying whether the amount varies is ambiguous: it would set a
            // number that means nothing on a fixed bill. Refused rather than guessed at.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "send amountVaries alongside a tolerance",
                    Map.of("field", "tolerance"));
        }
        if (changes.active() != null) {
            if (changes.active()) {
                bill.activate();
            } else {
                bill.deactivate();
            }
        }

        Bill saved = saveDistinctly(bill);
        log.info(
                "bill updated householdId={} billId={} byUserId={} active={}",
                household.id(),
                billId,
                actorId,
                saved.isActive());
        return views(household, List.of(saved), null).getFirst();
    }

    /**
     * Deletes a bill and everything recorded about it.
     *
     * <p><b>No transaction is deleted.</b> A bill is a plan; a transaction is a record of something
     * that happened in the world and outlives any plan it was filed under (ADR-0025). Retiring is
     * the usual answer and keeps the history — this is for a bill that was a mistake.
     */
    @Transactional
    public void delete(UUID actorId, UUID billId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Bill bill = require(billId, householdId);

        bills.delete(bill);
        log.info("bill deleted householdId={} billId={} byUserId={}", householdId, billId, actorId);
    }

    // ----------------------------------------------------------------------------- occurrences

    /**
     * Records what the household says about one occurrence — the one-tap action on the page.
     *
     * <p><b>Idempotent, and it creates the row.</b> The URL is valid before anything exists at it
     * (ADR-0032), so this is the whole write path: an upsert on {@code (billId, dueDate)}.
     *
     * @param dueDate must be a date the bill's schedule implies, or one a row already exists at. An
     *     {@code IRREGULAR} bill is the exception: it projects nothing by definition, so recording
     *     against a date is what brings the occurrence into being
     * @throws NotFoundException if the date is not an occurrence of this bill and cannot become one
     * @throws ConflictException {@code bill-inactive}
     */
    @Transactional
    public OccurrenceView record(
            UUID actorId, UUID billId, LocalDate dueDate, StatusRecord recorded) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Bill bill = require(billId, household.id());
        Currency currency = household.baseCurrency();

        BillOccurrence occurrence =
                occurrences.findOne(household.id(), billId, dueDate).orElse(null);

        if (occurrence == null) {
            if (!bill.isActive()) {
                throw new ConflictException(
                        ErrorCode.BILL_INACTIVE,
                        "this bill is retired; restore it to record against it");
            }
            if (!projects(bill, dueDate)) {
                throw new NotFoundException("this bill has no occurrence on that date");
            }
            if (occurrences.countForBill(household.id(), billId) >= MAXIMUM_OCCURRENCES_PER_BILL) {
                throw new ConflictException(
                        ErrorCode.CONFLICT,
                        "this bill already has the most recorded occurrences we allow");
            }
            occurrence =
                    BillOccurrence.recordedFor(
                            bill,
                            YearMonth.from(dueDate),
                            dueDate,
                            Money.of(bill.expectedAmount(), currency));
        }

        switch (recorded.status()) {
            case DUE -> occurrence.markDue();
            case SKIPPED -> occurrence.markSkipped();
            case PAID ->
                    occurrence.markPaid(
                            recorded.actualAmount() == null
                                    // One tap: "I paid it" means "I paid what was budgeted".
                                    ? Money.of(occurrence.expectedAmount(), currency)
                                    : amount(recorded.actualAmount(), currency),
                            recorded.paidDate() == null ? today() : recorded.paidDate(),
                            null,
                            StatusSource.MANUAL,
                            currency);
        }

        BillOccurrence saved = saveOccurrenceDistinctly(occurrence);
        log.info(
                "bill occurrence recorded householdId={} billId={} dueDate={} status={}"
                        + " byUserId={}",
                household.id(),
                billId,
                dueDate,
                saved.status(),
                actorId);
        return occurrenceView(bill, new ProjectedOccurrence(saved.dueDate(), saved), currency);
    }

    /**
     * Forgets what was recorded, so the date reads as its projection again.
     *
     * <p>Not the same as marking it {@code DUE}: that is an assertion, and this is the absence of
     * one. A household that ticked the wrong bill off wants the record gone, not replaced with a
     * different record.
     */
    @Transactional
    public void forget(UUID actorId, UUID billId, LocalDate dueDate) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        require(billId, householdId);

        BillOccurrence occurrence =
                occurrences
                        .findOne(householdId, billId, dueDate)
                        .orElseThrow(
                                () -> new NotFoundException("nothing is recorded for that date"));
        occurrences.delete(occurrence);
        log.info(
                "bill occurrence forgotten householdId={} billId={} dueDate={} byUserId={}",
                householdId,
                billId,
                dueDate,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    /** Whether the schedule puts an occurrence on this date — the only gate on creating a row. */
    private static boolean projects(Bill bill, LocalDate dueDate) {
        // IRREGULAR projects nothing by definition, and ADR-0032 is explicit that recording
        // against a period is what creates its occurrence. Anything else would make the schedule
        // unusable rather than merely unpredictable.
        if (bill.recurrence().kind() == ScheduleKind.IRREGULAR) {
            return true;
        }
        return bill.recurrence().datesIn(YearMonth.from(dueDate)).contains(dueDate);
    }

    /**
     * @param period the month to project, or <b>null</b> for a bill on its own, with no occurrences
     *     at all. A write returns the second kind: which month the client is looking at is the
     *     client's question, and answering it from the server's clock would make a created bill
     *     carry a different month from the list it is about to appear in
     */
    private List<BillView> views(Household household, List<Bill> found, YearMonth period) {
        if (found.isEmpty()) {
            return List.of();
        }
        Currency currency = household.baseCurrency();

        Map<UUID, List<BillOccurrence>> recordedByBill = new HashMap<>();
        if (period != null) {
            for (BillOccurrence row : occurrences.findForPeriod(household.id(), period.atDay(1))) {
                recordedByBill.computeIfAbsent(row.billId(), id -> new ArrayList<>()).add(row);
            }
        }

        Map<UUID, Category> categoriesById = categoriesFor(household.id(), found);

        List<BillView> views = new ArrayList<>(found.size());
        for (Bill bill : found) {
            List<ProjectedOccurrence> merged =
                    period == null
                            ? List.of()
                            : OccurrenceProjection.merge(
                                    // A retired bill projects nothing. Its recorded rows still
                                    // appear, so a month it was genuinely paid in reads correctly.
                                    bill.isActive() ? bill.recurrence().datesIn(period) : List.of(),
                                    recordedByBill.getOrDefault(bill.id(), List.of()));

            List<OccurrenceView> occurrenceViews = new ArrayList<>(merged.size());
            for (ProjectedOccurrence occurrence : merged) {
                occurrenceViews.add(occurrenceView(bill, occurrence, currency));
            }

            Category category = bill.categoryId().map(categoriesById::get).orElse(null);
            String parentName =
                    category == null
                            ? null
                            : category.parentId()
                                    .map(categoriesById::get)
                                    .map(Category::name)
                                    .orElse(null);

            views.add(
                    new BillView(
                            bill.id(),
                            bill.name(),
                            category == null ? null : category.id(),
                            category == null ? null : category.name(),
                            parentName,
                            Money.of(bill.expectedAmount(), currency),
                            bill.recurrence(),
                            bill.amountVaries(),
                            bill.tolerance().orElse(null),
                            bill.isActive(),
                            List.copyOf(occurrenceViews),
                            bill.createdAt(),
                            bill.updatedAt(),
                            bill.version()));
        }
        return views;
    }

    /**
     * One occurrence, recorded or merely projected.
     *
     * <p>A projected occurrence takes its expected amount from the bill as it is now; a recorded
     * one keeps the figure it was recorded against. That is the whole point of copying it in.
     */
    private OccurrenceView occurrenceView(
            Bill bill, ProjectedOccurrence occurrence, Currency currency) {
        BillOccurrence row = occurrence.recorded();
        if (row == null) {
            Money expected = Money.of(bill.expectedAmount(), currency);
            return new OccurrenceView(
                    bill.id(),
                    occurrence.dueDate(),
                    expected,
                    OccurrenceStatus.DUE,
                    null,
                    null,
                    null,
                    StatusSource.MANUAL,
                    false,
                    occurrence.dueDate().isBefore(today()),
                    false,
                    null);
        }
        Money expected = Money.of(row.expectedAmount(), currency);
        Money actual = row.actualAmount().map(figure -> Money.of(figure, currency)).orElse(null);
        return new OccurrenceView(
                bill.id(),
                row.dueDate(),
                expected,
                row.status(),
                actual,
                row.paidDate().orElse(null),
                row.transactionId().orElse(null),
                row.statusSource(),
                true,
                row.isOverdue(today()),
                surprising(bill, expected, actual),
                row.version());
    }

    /**
     * Whether what was paid is further from what was budgeted than the bill says is unremarkable.
     *
     * <p>A bill with a fixed amount has no tolerance, and any difference at all is worth showing. A
     * bill that varies says how much variation is normal, so that a £134 payment on a £120 phone
     * bill is not flagged and a £300 one is.
     */
    private static boolean surprising(Bill bill, Money expected, Money actual) {
        if (actual == null) {
            return false;
        }
        BigDecimal difference = actual.minus(expected).abs().amount();
        BigDecimal allowed = bill.tolerance().orElse(BigDecimal.ZERO);
        return difference.compareTo(allowed) > 0;
    }

    private Map<UUID, Category> categoriesFor(UUID householdId, List<Bill> found) {
        Set<UUID> categoryIds = new HashSet<>();
        for (Bill bill : found) {
            bill.categoryId().ifPresent(categoryIds::add);
        }
        Map<UUID, Category> byId = new HashMap<>();
        if (categoryIds.isEmpty()) {
            return byId;
        }
        for (Category category : categories.findAllByHouseholdIdAndIdIn(householdId, categoryIds)) {
            byId.put(category.id(), category);
        }
        Set<UUID> parentIds = new HashSet<>();
        for (Category category : byId.values()) {
            category.parentId().filter(id -> !byId.containsKey(id)).ifPresent(parentIds::add);
        }
        if (!parentIds.isEmpty()) {
            for (Category parent : categories.findAllByHouseholdIdAndIdIn(householdId, parentIds)) {
                byId.put(parent.id(), parent);
            }
        }
        return byId;
    }

    private Bill require(UUID billId, UUID householdId) {
        return bills.findByIdAndHouseholdId(billId, householdId)
                .orElseThrow(() -> new NotFoundException("no such bill"));
    }

    private Category requireCategory(UUID categoryId, UUID householdId) {
        return categories
                .findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new NotFoundException("no such category"));
    }

    private UUID requireCategoryOrNull(UUID categoryId, UUID householdId) {
        return categoryId == null ? null : requireCategory(categoryId, householdId).id();
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected, String what) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the " + what + " changed since it was read");
        }
    }

    private Bill saveDistinctly(Bill bill) {
        try {
            return bills.saveAndFlush(bill);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_bills_household_name")) {
                throw new ConflictException(
                        ErrorCode.BILL_NAME_TAKEN, "a bill with this name already exists");
            }
            throw violation;
        }
    }

    /**
     * Saves an occurrence, turning the identity index's violation into a {@code 409}.
     *
     * <p>The look-up above is the good path; this is the control. Two members tapping "paid" on the
     * same occurrence at the same moment both find no row, and one of them still has to be told —
     * and the thing to tell them is that it is already recorded, not that something broke.
     */
    private BillOccurrence saveOccurrenceDistinctly(BillOccurrence occurrence) {
        try {
            return occurrences.saveAndFlush(occurrence);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_bill_occurrences_bill_due_date")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "this occurrence was recorded a moment ago");
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

    /**
     * A tolerance belongs to a bill that varies, and a varying bill needs one to mean anything.
     *
     * <p>{@code Bill.variesBy} enforces the same rule — this is where the message that names the
     * field comes from, and the entity is the control.
     */
    private static BigDecimal tolerance(boolean varies, String text, Currency currency) {
        if (!varies) {
            if (text != null) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a bill with a fixed amount has no tolerance",
                        Map.of("field", "tolerance"));
            }
            return null;
        }
        if (text == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a bill whose amount varies needs a tolerance",
                    Map.of("field", "tolerance"));
        }
        // Parsed through the same path as any other amount: a tolerance is money.
        return AmountText.parse(text, currency, AmountText.Sign.NOT_NEGATIVE, "tolerance").amount();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
