package com.budgetowl.checkin.service;

import com.budgetowl.checkin.domain.AnswerChoice;
import com.budgetowl.checkin.domain.AnswerShape;
import com.budgetowl.checkin.domain.CheckIn;
import com.budgetowl.checkin.domain.CheckInAnswer;
import com.budgetowl.checkin.domain.CheckInRefusal;
import com.budgetowl.checkin.domain.CheckInRefusal.Stale;
import com.budgetowl.checkin.domain.CheckInStatus;
import com.budgetowl.checkin.domain.IsoWeek;
import com.budgetowl.checkin.domain.ItemKeys;
import com.budgetowl.checkin.domain.StaleReason;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.CheckInAnswerRepository;
import com.budgetowl.checkin.persistence.CheckInRepository;
import com.budgetowl.checkin.service.CheckInViews.HistoryEntry;
import com.budgetowl.checkin.service.CheckInViews.Page;
import com.budgetowl.checkin.service.CheckInViews.WeekView;
import com.budgetowl.checkin.service.Picture.Item;
import com.budgetowl.checkin.service.Picture.Target;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly check-in (docs/features/weekly-check-in.md).
 *
 * <p><b>On every write the role is checked first</b>, before the week, the draft or the item is
 * looked up, so a {@code VIEWER} gets {@code 403} and learns nothing about what exists (threat
 * model, attack 3). The role is resolved from the database on every request, never cached on the
 * draft, so a member demoted mid-draft cannot answer or finish (attack 4).
 *
 * <p><b>Every write to a draft locks its row first</b>, so an answer, a discard and a completion
 * queue behind one another, and a second completion finds the row {@code COMPLETED} and writes
 * nothing (attack 5).
 *
 * <p><b>An item key resolves only among the items derived for this household's week, in this
 * request</b>, so a foreign, deleted or out-of-window id is the same {@code 404} as nonsense
 * (attack 2). Every id in a body is resolved the same way.
 *
 * <p><b>Nothing here logs a name, an amount or a figure.</b> Audit lines carry ids and kinds.
 */
@Service
public class CheckInService {

    /**
     * Answers per check-in. Answers exist only for derived items, which are bounded, but an item
     * can stop deriving (its row re-filed in the ledger) and leave its answer behind; this is the
     * backstop against a loop that grows a draft without limit.
     */
    public static final int MAXIMUM_ANSWERS_PER_CHECK_IN = 1_000;

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);

    private final MembershipService memberships;
    private final CheckInRepository checkIns;
    private final CheckInAnswerRepository answers;
    private final CheckInPictures pictures;
    private final CheckInCompletion completion;
    private final CheckInOutcomes outcomes;
    private final Clock clock;

    public CheckInService(
            MembershipService memberships,
            CheckInRepository checkIns,
            CheckInAnswerRepository answers,
            CheckInPictures pictures,
            CheckInCompletion completion,
            CheckInOutcomes outcomes,
            Clock clock) {
        this.memberships = memberships;
        this.checkIns = checkIns;
        this.answers = answers;
        this.pictures = pictures;
        this.completion = completion;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------ reads

    /** The history, newest week first. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<HistoryEntry> history(UUID actorId, int page, int size) {
        UUID householdId = memberships.requireMembership(actorId).household().id();
        org.springframework.data.domain.Page<CheckIn> found =
                checkIns.findHistory(householdId, PageRequest.of(Math.max(page, 0), clamp(size)));
        Map<UUID, CheckInAnswer> allocations = new HashMap<>();
        if (!found.isEmpty()) {
            List<UUID> ids = found.getContent().stream().map(CheckIn::id).toList();
            for (CheckInAnswer answer : answers.findAllocations(householdId, ids)) {
                allocations.put(answer.checkInId(), answer);
            }
        }
        List<HistoryEntry> entries = new ArrayList<>(found.getNumberOfElements());
        for (CheckIn checkIn : found.getContent()) {
            CheckInAnswer allocation = checkIn.isCompleted() ? allocations.get(checkIn.id()) : null;
            entries.add(
                    new HistoryEntry(
                            checkIn.week(),
                            checkIn.status().name(),
                            checkIn.completedAt().orElse(null),
                            checkIn.currency()
                                    .map(java.util.Currency::getCurrencyCode)
                                    .orElse(null),
                            checkIn.isCompleted() ? CheckInOutcomes.snapshot(checkIn) : null,
                            allocation == null ? null : allocation.choice(),
                            allocation == null
                                    ? null
                                    : allocation.debtId().or(allocation::goalId).orElse(null)));
        }
        return new Page<>(
                entries,
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * The week. A completed week returns its snapshot; a draft, its items and answers; a week
     * nobody started, its computed items — but only while it is in range.
     *
     * @throws CheckInRefusal {@code check-in-week-out-of-range} for a week with no row outside the
     *     range
     */
    @Transactional(readOnly = true)
    public WeekView get(UUID actorId, IsoWeek week) {
        Household household = memberships.requireMembership(actorId).household();
        Optional<CheckIn> found = checkIns.findForWeek(household.id(), week.monday());
        if (found.isPresent()) {
            return view(actorId, household, found.get());
        }
        requireInRange(week);
        return pictures.derive(
                        actorId,
                        household,
                        week,
                        null,
                        pictures.windowStartFor(household.id(), week),
                        List.of(),
                        today())
                .view();
    }

    /**
     * The week that contains today on the server's clock: what {@code /check-in} and the ledger's
     * prompt open. The server is the authority on which weeks are in range (decision 8), so the
     * browser's clock is never asked which week it is.
     */
    @Transactional(readOnly = true)
    public WeekView current(UUID actorId) {
        return get(actorId, IsoWeek.containing(today()));
    }

    // ----------------------------------------------------------------------------------- writes

    /**
     * Starts the draft, or returns the one already started. Idempotent: two members pressing Start
     * get one draft, because the household row is locked first.
     *
     * @throws CheckInRefusal {@code check-in-draft-open} while another week's draft exists, {@code
     *     check-in-completed}, {@code check-in-week-out-of-range}
     */
    @Transactional
    public Started start(UUID actorId, IsoWeek week) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        requireInRange(week);
        checkIns.lockHousehold(household.id());

        Optional<CheckIn> existing = checkIns.findForWeek(household.id(), week.monday());
        if (existing.isPresent()) {
            if (existing.get().isCompleted()) {
                throw CheckInRefusal.completed();
            }
            return new Started(view(actorId, household, existing.get()), false);
        }
        Optional<CheckIn> open = checkIns.findInStatus(household.id(), CheckInStatus.DRAFT);
        if (open.isPresent()) {
            throw CheckInRefusal.draftOpen(open.get().week());
        }
        CheckIn started =
                checkIns.saveAndFlush(
                        CheckIn.start(
                                household,
                                week,
                                pictures.windowStartFor(household.id(), week),
                                actorId,
                                Instant.now(clock)));
        log.info(
                "check-in started householdId={} checkInId={} week={} byUserId={}",
                household.id(),
                started.id(),
                week,
                actorId);
        return new Started(view(actorId, household, started), true);
    }

    /** What {@link #start} returns: the week, and whether this call created it. */
    public record Started(WeekView view, boolean created) {}

    /**
     * Records or replaces one answer. Omit the version to create, send the answer's own to replace.
     * Returns the whole week, so the figure settles from the server's response.
     *
     * @throws NotFoundException for a key that does not derive to an item in this week, and for an
     *     id in the body that is not a valid target for it
     * @throws ConflictException {@code stale-version}; {@code check-in-completed}
     * @throws InvalidRequestException {@code validation-failed} naming the field
     */
    @Transactional
    public WeekView answer(UUID actorId, IsoWeek week, String itemKey, AnswerCommand command) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        ItemKeys.Parsed key = ItemKeys.parse(itemKey).orElseThrow(CheckInService::noSuchItem);
        CheckIn checkIn = lockDraft(household, week);
        requireInRange(week);

        List<CheckInAnswer> stored = answers.findForCheckIn(household.id(), checkIn.id());
        Picture picture = derive(actorId, household, checkIn, stored);
        Item item = picture.items().get(itemKey);
        if (item == null || item.kind() != key.kind()) {
            throw noSuchItem();
        }

        CheckInAnswer existing = null;
        for (CheckInAnswer answer : stored) {
            if (answer.itemKey().equals(itemKey)) {
                existing = answer;
            }
        }
        if (existing == null && command.version() != null
                || existing != null
                        && (command.version() == null || existing.version() != command.version())) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the answer changed since it was read");
        }
        if (existing == null && stored.size() >= MAXIMUM_ANSWERS_PER_CHECK_IN) {
            throw new ConflictException(
                    ErrorCode.CONFLICT,
                    "a check-in is limited to " + MAXIMUM_ANSWERS_PER_CHECK_IN + " answers");
        }

        CheckInAnswer answer = existing == null ? blank(checkIn, item) : existing;
        Resolved resolved = resolve(item, command, picture);
        answer.record(command.choice(), resolved.shape(), resolved.subjectVersion(), actorId);
        answers.saveAndFlush(answer);
        checkIn.touch(Instant.now(clock));
        checkIns.saveAndFlush(checkIn);
        log.info(
                "check-in answered householdId={} checkInId={} kind={} choice={} byUserId={}",
                household.id(),
                checkIn.id(),
                item.kind(),
                command.choice(),
                actorId);
        return derive(
                        actorId,
                        household,
                        checkIn,
                        answers.findForCheckIn(household.id(), checkIn.id()))
                .view();
    }

    /**
     * Clears one answer. An answer whose item no longer derives may still be cleared, so a person
     * can tidy a stale draft.
     */
    @Transactional
    public void clearAnswer(UUID actorId, IsoWeek week, String itemKey) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        ItemKeys.parse(itemKey).orElseThrow(CheckInService::noSuchItem);
        CheckIn checkIn = lockDraft(household, week);
        CheckInAnswer found = null;
        for (CheckInAnswer answer : answers.findForCheckIn(household.id(), checkIn.id())) {
            if (answer.itemKey().equals(itemKey)) {
                found = answer;
            }
        }
        if (found == null) {
            throw noSuchItem();
        }
        answers.delete(found);
        checkIn.touch(Instant.now(clock));
        checkIns.saveAndFlush(checkIn);
        log.info(
                "check-in answer cleared householdId={} checkInId={} kind={} byUserId={}",
                household.id(),
                checkIn.id(),
                found.itemKind(),
                actorId);
    }

    /** Discards a draft and its answers, and writes nothing else. */
    @Transactional
    public void discard(UUID actorId, IsoWeek week) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        CheckIn checkIn = lockDraft(household, week);
        checkIns.delete(checkIn);
        log.info(
                "check-in discarded householdId={} checkInId={} byUserId={}",
                household.id(),
                checkIn.id(),
                actorId);
    }

    /**
     * Completes the draft: everything, atomically, or nothing.
     *
     * @param version the draft's version the person last saw; every answer write moves it, so the
     *     finisher has seen every answer
     * @param basis the digest the person last saw
     * @param today the client's own date, or null: the day a bill marked paid is recorded on
     * @throws CheckInRefusal {@code check-in-completed} on a retry, {@code check-in-stale}, {@code
     *     check-in-incomplete}, {@code check-in-week-out-of-range}
     * @throws ConflictException {@code stale-version}
     */
    @Transactional
    public WeekView complete(
            UUID actorId, IsoWeek week, long version, String basis, LocalDate today) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        CheckIn checkIn = lockDraft(household, week);
        if (checkIn.version() != version) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the check-in changed since it was read");
        }
        requireInRange(week);
        completion.complete(actorId, household, checkIn, basis, today);
        log.info(
                "check-in completed householdId={} checkInId={} week={} byUserId={}",
                household.id(),
                checkIn.id(),
                week,
                actorId);
        return view(actorId, household, checkIn);
    }

    // ------------------------------------------------------------------------------- internals

    private WeekView view(UUID actorId, Household household, CheckIn checkIn) {
        List<CheckInAnswer> stored = answers.findForCheckIn(household.id(), checkIn.id());
        if (checkIn.isCompleted()) {
            return outcomes.completed(actorId, checkIn, stored);
        }
        return derive(actorId, household, checkIn, stored).view();
    }

    private Picture derive(
            UUID actorId, Household household, CheckIn checkIn, List<CheckInAnswer> stored) {
        return pictures.derive(
                actorId,
                household,
                checkIn.week(),
                checkIn,
                checkIn.windowStart(),
                stored,
                today());
    }

    /**
     * The draft for the week, locked. A missing one is a {@code 404}; a completed one is read-only.
     */
    private CheckIn lockDraft(Household household, IsoWeek week) {
        CheckIn checkIn =
                checkIns.findForWeekForUpdate(household.id(), week.monday())
                        .orElseThrow(() -> new NotFoundException("no check-in for that week"));
        if (checkIn.isCompleted()) {
            throw CheckInRefusal.completed();
        }
        return checkIn;
    }

    /**
     * The current ISO week or the one before, by the server's clock, with a day's slack either way
     * so a household east or west of UTC is not refused at the edges of Monday (decision 8).
     */
    private void requireInRange(IsoWeek week) {
        LocalDate today = today();
        Set<IsoWeek> allowed = new HashSet<>();
        for (LocalDate day : List.of(today.minusDays(1), today, today.plusDays(1))) {
            IsoWeek containing = IsoWeek.containing(day);
            allowed.add(containing);
            allowed.add(containing.previous());
        }
        if (!allowed.contains(week)) {
            throw CheckInRefusal.weekOutOfRange();
        }
    }

    private static CheckInAnswer blank(CheckIn checkIn, Item item) {
        return switch (item.kind()) {
            case OVERDUE_BILL ->
                    CheckInAnswer.aboutOccurrence(checkIn, item.billId(), item.dueDate());
            case ALLOCATION -> CheckInAnswer.aboutAllocation(checkIn);
            default -> CheckInAnswer.aboutTransaction(checkIn, item.kind(), item.row().id());
        };
    }

    /** An answer's shape and subject version, every id resolved among what the picture offers. */
    private record Resolved(AnswerShape shape, Long subjectVersion) {}

    private Resolved resolve(Item item, AnswerCommand command, Picture picture) {
        if (command.choice() == null || !item.kind().accepts(command.choice())) {
            throw invalid("that choice does not answer this item", "choice");
        }
        AnswerChoice choice = command.choice();
        return switch (item.kind()) {
            case OVERDUE_BILL -> {
                refuseFields(
                        command,
                        "categoryId",
                        "billId",
                        "dueDate",
                        "goalId",
                        "debtId",
                        "amount",
                        "remember");
                if (command.recordsDebtPayment()
                        && (choice != AnswerChoice.MARK_PAID
                                || !picture.debtByBill().containsKey(item.billId()))) {
                    throw invalid(
                            "only a debt's bill, marked paid, records a payment",
                            "recordDebtPayment");
                }
                yield new Resolved(
                        AnswerShape.debtPrompt(command.recordsDebtPayment()),
                        item.subjectVersion());
            }
            case UNUSUAL_CHARGE -> {
                refuseFields(
                        command,
                        "categoryId",
                        "billId",
                        "dueDate",
                        "goalId",
                        "debtId",
                        "amount",
                        "remember",
                        "recordDebtPayment");
                if (choice == AnswerChoice.PREPAID) {
                    if (item.multiple() == null || item.target() == null) {
                        throw invalid("this charge cannot be treated as prepaid", "choice");
                    }
                    // The bill and k are the server's, never sent.
                    yield new Resolved(
                            AnswerShape.target(
                                    item.target().billId(), item.target().dueDate(), false, false),
                            item.subjectVersion());
                }
                yield new Resolved(AnswerShape.none(), item.subjectVersion());
            }
            case CATEGORIZE -> {
                refuseFields(
                        command,
                        "billId",
                        "dueDate",
                        "goalId",
                        "debtId",
                        "amount",
                        "recordDebtPayment");
                if (choice == AnswerChoice.LEAVE) {
                    refuseFields(command, "categoryId", "remember");
                    yield new Resolved(AnswerShape.none(), item.subjectVersion());
                }
                if (command.categoryId() == null) {
                    throw invalid("a category is required", "categoryId");
                }
                CategoryRow category = picture.categories().get(command.categoryId());
                if (category == null || category.archived()) {
                    throw new NotFoundException("no such category");
                }
                if (command.remembers() && item.row().payeeId() == null) {
                    throw invalid("a row with no payee has no rule to remember", "remember");
                }
                yield new Resolved(
                        AnswerShape.category(category.id(), command.remembers()),
                        item.subjectVersion());
            }
            case BILL_CHARGE -> {
                refuseFields(command, "categoryId", "goalId", "debtId", "amount");
                if (choice != AnswerChoice.ATTACH) {
                    refuseFields(command, "billId", "dueDate", "remember", "recordDebtPayment");
                    yield new Resolved(AnswerShape.none(), null);
                }
                if (command.billId() == null) {
                    throw invalid("a bill is required", "billId");
                }
                if (command.dueDate() == null) {
                    throw invalid("a due date is required", "dueDate");
                }
                Target target = new Target(command.billId(), command.dueDate());
                if (!picture.attachTargets().getOrDefault(item.key(), Set.of()).contains(target)) {
                    throw new NotFoundException("no such occurrence to attach to");
                }
                if (command.recordsDebtPayment()
                        && !picture.debtByBill().containsKey(target.billId())) {
                    throw invalid("only a debt's bill records a payment", "recordDebtPayment");
                }
                yield new Resolved(
                        AnswerShape.target(
                                target.billId(),
                                target.dueDate(),
                                command.remembers(),
                                command.recordsDebtPayment()),
                        picture.versionOf(target));
            }
            case ALLOCATION -> {
                refuseFields(
                        command,
                        "categoryId",
                        "billId",
                        "dueDate",
                        "remember",
                        "recordDebtPayment");
                if (choice != AnswerChoice.DEBT) {
                    refuseFields(command, "debtId");
                }
                if (choice != AnswerChoice.GOAL) {
                    refuseFields(command, "goalId");
                }
                Money surplus = picture.surplus();
                if (command.amount() == null) {
                    throw invalid("the amount is required", "amount");
                }
                BigDecimal amount = amount(command.amount(), surplus);
                if (amount.compareTo(surplus.amount()) != 0) {
                    throw CheckInRefusal.stale(
                            List.of(new Stale(ItemKeys.ALLOCATION, StaleReason.SURPLUS_CHANGED)));
                }
                if (choice == AnswerChoice.DEBT
                        && (command.debtId() == null
                                || !picture.offeredDebts().contains(command.debtId()))) {
                    throw new NotFoundException("no such debt to allocate to");
                }
                if (choice == AnswerChoice.GOAL
                        && (command.goalId() == null
                                || !picture.offeredGoals().contains(command.goalId()))) {
                    throw new NotFoundException("no such goal to allocate to");
                }
                yield new Resolved(
                        AnswerShape.allocation(
                                choice == AnswerChoice.GOAL ? command.goalId() : null,
                                choice == AnswerChoice.DEBT ? command.debtId() : null,
                                surplus.amount()),
                        null);
            }
        };
    }

    /**
     * Parses the allocation and refuses one more precise than the currency: a scale-2 amount in a
     * yen household is a {@code 400}, not a figure rounded behind somebody's back.
     */
    private static BigDecimal amount(String text, Money surplus) {
        Money parsed =
                AmountText.parse(text, surplus.currency(), AmountText.Sign.POSITIVE, "amount");
        int allowed = WeeklyFigure.minorUnit(surplus.currency());
        if (new BigDecimal(text.strip()).stripTrailingZeros().scale() > allowed) {
            throw invalid(
                    "an amount in "
                            + surplus.currency().getCurrencyCode()
                            + " has at most "
                            + allowed
                            + " decimal places",
                    "amount");
        }
        return parsed.amount();
    }

    /** A field this kind of answer does not carry is refused, not ignored. */
    private static void refuseFields(AnswerCommand command, String... fields) {
        for (String field : fields) {
            boolean present =
                    switch (field) {
                        case "categoryId" -> command.categoryId() != null;
                        case "billId" -> command.billId() != null;
                        case "dueDate" -> command.dueDate() != null;
                        case "goalId" -> command.goalId() != null;
                        case "debtId" -> command.debtId() != null;
                        case "amount" -> command.amount() != null;
                        case "remember" -> command.remembers();
                        case "recordDebtPayment" -> command.recordsDebtPayment();
                        default -> throw new IllegalArgumentException("unknown field");
                    };
            if (present) {
                throw invalid("this answer does not take that field", field);
            }
        }
    }

    private static NotFoundException noSuchItem() {
        return new NotFoundException("no such item in this check-in");
    }

    private static InvalidRequestException invalid(String detail, String field) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static int clamp(int size) {
        return Math.clamp(
                size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
    }
}
