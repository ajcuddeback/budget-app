package com.budgetowl.envelope.service;

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
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.envelope.domain.Envelope;
import com.budgetowl.envelope.domain.EnvelopeEntry;
import com.budgetowl.envelope.persistence.EnvelopeEntryRepository;
import com.budgetowl.envelope.persistence.EnvelopeRepository;
import com.budgetowl.envelope.persistence.EnvelopeSpent;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.payee.domain.Payee;
import com.budgetowl.payee.persistence.PayeeRepository;
import com.budgetowl.payee.service.PayeeService;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.persistence.TransactionRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Envelopes: the amounts a household decided on, and what it has spent of them.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes, another household's row a {@code 404}.
 * The database carries the same rule structurally: the envelope, category, payee and transaction
 * foreign keys are all composite on {@code (id, household_id)}.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>{@code remaining} is computed here and stored nowhere.</b> It is the number a person
 *       acts on while still standing in the shop, and a stored copy is wrong the instant an entry
 *       lands. It goes negative when an envelope is overspent, and nothing clamps it.
 *   <li><b>An amount alone is a valid entry.</b> Payee and note are optional and the date defaults
 *       to today. This is the action somebody takes eighty times a month.
 *   <li><b>A transaction never files itself.</b> Attaching one is something a person does, and the
 *       resulting entry takes its figure from the transaction so the two cannot disagree.
 *   <li><b>One transaction, at most one entry, ever</b> (ADR-0031). Enforced by a unique index;
 *       this service turns the violation into a message naming the envelope that has it.
 * </ul>
 *
 * <p><b>Nothing here logs an amount, a payee or a note.</b> Audit lines carry ids and counts.
 */
@Service
public class EnvelopeService {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeService.class);

    private final EnvelopeRepository envelopes;
    private final EnvelopeEntryRepository entries;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final PayeeService payeeService;
    private final TransactionRepository transactions;
    private final MembershipService memberships;
    private final Clock clock;

    public EnvelopeService(
            EnvelopeRepository envelopes,
            EnvelopeEntryRepository entries,
            CategoryRepository categories,
            PayeeRepository payees,
            PayeeService payeeService,
            TransactionRepository transactions,
            MembershipService memberships,
            Clock clock) {
        this.envelopes = envelopes;
        this.entries = entries;
        this.categories = categories;
        this.payees = payees;
        this.payeeService = payeeService;
        this.transactions = transactions;
        this.memberships = memberships;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------- envelopes

    /** One month's envelopes, each with its remaining figure, plus the period's own totals. */
    @Transactional(readOnly = true)
    public EnvelopePage list(
            UUID actorId, YearMonth period, boolean includeArchived, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();

        Page<Envelope> found =
                envelopes.findForPeriod(
                        household.id(), period.atDay(1), includeArchived, pageable(page, size));

        List<EnvelopeView> views = views(household, found.getContent());
        Currency currency = household.baseCurrency();
        Money allocated = Money.zero(currency);
        Money spent = Money.zero(currency);
        for (EnvelopeView view : views) {
            // ARCHIVED ENVELOPES ARE NOT PART OF THE PLAN, even when the caller asked for them so
            // it could show them separately. Counting a retired envelope in "set aside this month"
            // would overstate the plan and understate what is left — and the figure would look
            // perfectly reasonable, which is why a UI test is what caught it.
            if (view.archived()) {
                continue;
            }
            allocated = allocated.plus(view.amount());
            spent = spent.plus(view.spent());
        }

        return new EnvelopePage(
                views,
                allocated,
                spent,
                // Not summed from the views' own remaining figures, but derived from the two
                // totals: one subtraction rather than a chain of them, so a rounding difference
                // cannot appear between the headline and the rows it summarises.
                allocated.minus(spent),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /** The months this household has envelopes for, newest first. */
    @Transactional(readOnly = true)
    public List<YearMonth> periods(UUID actorId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        return envelopes.periodsWithEnvelopes(membership.household().id()).stream()
                .map(YearMonth::from)
                .toList();
    }

    /**
     * What one month's envelopes set aside in all — one figure, one query.
     *
     * <p>For the Bills &amp; Income page, whose headline is income minus bills minus this. It is
     * the same definition as {@code list}'s {@code totalAllocated}, archived envelopes excluded,
     * and the repository's Javadoc says why it is a sum rather than a sum of views.
     */
    @Transactional(readOnly = true)
    public Money allocatedFor(UUID actorId, YearMonth period) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        BigDecimal allocated = envelopes.allocatedForPeriod(household.id(), period.atDay(1));
        return allocated == null
                ? Money.zero(household.baseCurrency())
                : Money.of(allocated, household.baseCurrency());
    }

    @Transactional(readOnly = true)
    public EnvelopeView get(UUID actorId, UUID envelopeId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        return views(household, List.of(require(envelopeId, household.id()))).getFirst();
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code envelope-name-taken}
     */
    @Transactional
    public EnvelopeView create(UUID actorId, NewEnvelope requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        Envelope envelope =
                Envelope.of(
                        household,
                        requested.name(),
                        requested.period(),
                        money(requested.amount(), household.baseCurrency(), false));
        envelope.fileUnder(requireCategoryOrNull(requested.categoryId(), household.id()));

        Envelope saved = saveDistinctly(envelope);
        log.info(
                "envelope created householdId={} envelopeId={} byUserId={} period={}",
                household.id(),
                saved.id(),
                actorId,
                saved.period());
        return views(household, List.of(saved)).getFirst();
    }

    @Transactional
    public EnvelopeView update(UUID actorId, UUID envelopeId, EnvelopeChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Envelope envelope = require(envelopeId, household.id());

        requireCurrentVersion(envelope.version(), changes.version(), "envelope");

        if (changes.name() != null) {
            envelope.rename(changes.name());
        }
        if (changes.amount() != null) {
            envelope.setAmount(
                    money(changes.amount(), household.baseCurrency(), false),
                    household.baseCurrency());
        }
        switch (changes.categoryId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> envelope.fileUnder(null);
            case Change.SetTo<UUID> filed ->
                    envelope.fileUnder(requireCategory(filed.value(), household.id()).id());
        }

        Envelope saved = saveDistinctly(envelope);
        log.info(
                "envelope updated householdId={} envelopeId={} byUserId={}",
                household.id(),
                envelopeId,
                actorId);
        return views(household, List.of(saved)).getFirst();
    }

    @Transactional
    public EnvelopeView archive(UUID actorId, UUID envelopeId) {
        return setArchived(actorId, envelopeId, true);
    }

    @Transactional
    public EnvelopeView unarchive(UUID actorId, UUID envelopeId) {
        return setArchived(actorId, envelopeId, false);
    }

    /**
     * Deletes an envelope and its log.
     *
     * <p><b>Its entries go with it, and no transaction does.</b> An entry has no meaning without
     * the envelope it was logged against, while a transaction is a record of something that
     * happened in the world and outlives any plan it was filed under — so an attached transaction
     * is simply detached, and the ledger is untouched.
     */
    @Transactional
    public void delete(UUID actorId, UUID envelopeId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Envelope envelope = require(envelopeId, householdId);

        envelopes.delete(envelope);
        log.info(
                "envelope deleted householdId={} envelopeId={} byUserId={}",
                householdId,
                envelopeId,
                actorId);
    }

    // --------------------------------------------------------------------------------- entries

    @Transactional(readOnly = true)
    public EnvelopeEntryPage log(UUID actorId, UUID envelopeId, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        // Resolved first so that an envelope belonging to nobody the caller is a member of is a
        // 404 for the envelope rather than an empty log, which would read as "nothing spent".
        require(envelopeId, household.id());

        Page<EnvelopeEntry> found =
                entries.findLog(household.id(), envelopeId, pageable(page, size));

        return new EnvelopeEntryPage(
                entryViews(household, found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * Logs spend against an envelope — typed, or by attaching a transaction.
     *
     * @throws ConflictException {@code envelope-archived}, or {@code
     *     transaction-already-in-envelope} naming the envelope that already has it
     */
    @Transactional
    public EnvelopeEntryView logEntry(UUID actorId, UUID envelopeId, NewEntry requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Envelope envelope = require(envelopeId, household.id());

        if (envelope.isArchived()) {
            throw new ConflictException(
                    ErrorCode.ENVELOPE_ARCHIVED,
                    "the envelope is archived; restore it to log against it");
        }

        boolean attaching = requested.transactionId() != null;
        if (attaching == (requested.amount() != null)) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "give either an amount or a transaction to attach, not both and not neither",
                    Map.of("field", "amount"));
        }

        EnvelopeEntry entry =
                attaching
                        ? attach(household, envelope, requested)
                        : EnvelopeEntry.typedInto(
                                envelope,
                                requested.date() == null ? today() : requested.date(),
                                money(requested.amount(), household.baseCurrency(), true),
                                household.baseCurrency());

        entry.payTo(resolvePayee(household, requested.payeeName()));
        entry.annotate(requested.note());

        EnvelopeEntry saved = saveEntryDistinctly(entry);
        log.info(
                "envelope entry logged householdId={} envelopeId={} entryId={} byUserId={}"
                        + " source={}",
                household.id(),
                envelopeId,
                saved.id(),
                actorId,
                saved.source());
        return entryViews(household, List.of(saved)).getFirst();
    }

    @Transactional
    public EnvelopeEntryView updateEntry(
            UUID actorId, UUID envelopeId, UUID entryId, EntryChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        EnvelopeEntry entry = requireEntry(entryId, envelopeId, household.id());

        requireCurrentVersion(entry.version(), changes.version(), "entry");

        if (changes.date() != null) {
            entry.on(changes.date());
        }
        if (changes.amount() != null) {
            entry.correctAmount(
                    money(changes.amount(), household.baseCurrency(), true),
                    household.baseCurrency());
        }
        switch (changes.payeeName()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> entry.payTo(null);
            case Change.SetTo<String> named -> entry.payTo(resolvePayee(household, named.value()));
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> entry.annotate(null);
            case Change.SetTo<String> written -> entry.annotate(written.value());
        }

        EnvelopeEntry saved = entries.saveAndFlush(entry);
        log.info(
                "envelope entry corrected householdId={} envelopeId={} entryId={} byUserId={}",
                household.id(),
                envelopeId,
                entryId,
                actorId);
        return entryViews(household, List.of(saved)).getFirst();
    }

    /** Removes one entry. The transaction it was attached to, if any, is untouched. */
    @Transactional
    public void deleteEntry(UUID actorId, UUID envelopeId, UUID entryId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        EnvelopeEntry entry = requireEntry(entryId, envelopeId, householdId);

        entries.delete(entry);
        log.info(
                "envelope entry deleted householdId={} envelopeId={} entryId={} byUserId={}",
                householdId,
                envelopeId,
                entryId,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Builds the entry for an attached transaction.
     *
     * <p>The figure comes from the transaction and the caller cannot override it. That is what
     * makes the two agree, and what makes marking the entry {@code SYNCED} mean anything: a
     * "synced" entry whose amount somebody could type would be a manual entry wearing a badge.
     */
    private EnvelopeEntry attach(Household household, Envelope envelope, NewEntry requested) {
        Transaction transaction =
                transactions
                        .findByIdAndHouseholdId(requested.transactionId(), household.id())
                        .orElseThrow(() -> new NotFoundException("no such transaction"));

        // One message, not two. An earlier version said "already in this envelope" or "already in
        // another one" depending on where it was — but the problem body renders from the error
        // CODE and its params (docs/guides/api-style.md), so neither sentence ever reached a
        // client. A branch whose output nothing can observe reads as a feature and is not one.
        if (entries.findByTransaction(household.id(), transaction.id()).isPresent()) {
            throw new ConflictException(
                    ErrorCode.TRANSACTION_ALREADY_IN_ENVELOPE,
                    "this transaction is already in an envelope");
        }

        // Money OUT of an account is a negative transaction; an entry records how much of a
        // decided amount was used, which only goes one way. Income filed against an envelope is
        // refused rather than silently flipped: it is not spending, and counting it as such would
        // make the envelope look emptier than it is.
        if (transaction.amount().signum() > 0) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "only money leaving an account can be logged against an envelope",
                    Map.of("field", "transactionId"));
        }

        return EnvelopeEntry.attachedTo(
                envelope,
                transaction.id(),
                requested.date() == null ? transaction.date() : requested.date(),
                Money.of(transaction.amount().negate(), household.baseCurrency()),
                household.baseCurrency());
    }

    private EnvelopeView setArchived(UUID actorId, UUID envelopeId, boolean archived) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Envelope envelope = require(envelopeId, household.id());

        if (archived) {
            envelope.archive();
        } else {
            envelope.unarchive();
        }

        Envelope saved = envelopes.saveAndFlush(envelope);
        log.info(
                "envelope {} householdId={} envelopeId={} byUserId={}",
                archived ? "archived" : "unarchived",
                household.id(),
                envelopeId,
                actorId);
        return views(household, List.of(saved)).getFirst();
    }

    private UUID resolvePayee(Household household, String typedName) {
        if (typedName == null || typedName.isBlank()) {
            return null;
        }
        return payeeService.getOrCreate(household, typedName).id();
    }

    private Envelope require(UUID envelopeId, UUID householdId) {
        return envelopes
                .findByIdAndHouseholdId(envelopeId, householdId)
                .orElseThrow(() -> new NotFoundException("no such envelope"));
    }

    private EnvelopeEntry requireEntry(UUID entryId, UUID envelopeId, UUID householdId) {
        return entries.findInEnvelope(entryId, envelopeId, householdId)
                .orElseThrow(() -> new NotFoundException("no such entry"));
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

    /** Saves, turning the name index's violation into {@code 409 envelope-name-taken}. */
    private Envelope saveDistinctly(Envelope envelope) {
        try {
            return envelopes.saveAndFlush(envelope);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_envelopes_household_period_name")) {
                throw new ConflictException(
                        ErrorCode.ENVELOPE_NAME_TAKEN,
                        "an envelope with this name already exists for that month");
            }
            throw violation;
        }
    }

    /**
     * Saves an entry, turning the transaction index's violation into a {@code 409}.
     *
     * <p>The look-before-you-leap check in {@link #attach} is the good message; this is the
     * control. Two members attaching the same transaction at the same moment both pass that check
     * and one of them still has to be told.
     */
    private EnvelopeEntry saveEntryDistinctly(EnvelopeEntry entry) {
        try {
            return entries.saveAndFlush(entry);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_envelope_entries_transaction_id")) {
                throw new ConflictException(
                        ErrorCode.TRANSACTION_ALREADY_IN_ENVELOPE,
                        "this transaction is already in an envelope");
            }
            throw violation;
        }
    }

    /**
     * Whether this violation names a particular constraint.
     *
     * <p>Matched on the constraint name, which is ours and is stable, rather than on the driver's
     * message text. Anything else is re-thrown: reporting an unrelated constraint as a name
     * collision would send somebody round a loop renaming a thing that was never the problem.
     */
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
     * Builds the views, resolving each envelope's spend in <b>one batched query</b> and its
     * category name in one more.
     *
     * <p>{@code remaining} is the subtraction below and exists nowhere else. An envelope absent
     * from the spend map has no entries, which is why the map is defaulted rather than required to
     * be complete.
     */
    private List<EnvelopeView> views(Household household, List<Envelope> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Currency currency = household.baseCurrency();
        List<UUID> ids = found.stream().map(Envelope::id).toList();

        Map<UUID, EnvelopeSpent> spentById = new HashMap<>();
        for (EnvelopeSpent row : envelopes.spentByEnvelope(household.id(), ids)) {
            spentById.put(row.envelopeId(), row);
        }

        Map<UUID, Category> categoriesById = categoriesFor(household.id(), found);

        List<EnvelopeView> views = new ArrayList<>(found.size());
        for (Envelope envelope : found) {
            EnvelopeSpent row = spentById.get(envelope.id());
            Money amount = Money.of(envelope.amount(), currency);
            Money spent = row == null ? Money.zero(currency) : Money.of(row.spent(), currency);
            Category category = envelope.categoryId().map(categoriesById::get).orElse(null);
            String parentName =
                    category == null
                            ? null
                            : category.parentId()
                                    .map(categoriesById::get)
                                    .map(Category::name)
                                    .orElse(null);

            views.add(
                    new EnvelopeView(
                            envelope.id(),
                            envelope.name(),
                            envelope.period(),
                            category == null ? null : category.id(),
                            category == null ? null : category.name(),
                            parentName,
                            amount,
                            spent,
                            amount.minus(spent),
                            row == null ? 0L : row.entryCount(),
                            row == null,
                            envelope.isArchived(),
                            envelope.createdAt(),
                            envelope.updatedAt(),
                            envelope.version()));
        }
        return views;
    }

    /** The categories these envelopes name, and their parents, in at most two queries. */
    private Map<UUID, Category> categoriesFor(UUID householdId, List<Envelope> found) {
        Set<UUID> categoryIds = new HashSet<>();
        for (Envelope envelope : found) {
            envelope.categoryId().ifPresent(categoryIds::add);
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

    private List<EnvelopeEntryView> entryViews(Household household, List<EnvelopeEntry> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Currency currency = household.baseCurrency();

        Set<UUID> payeeIds = new HashSet<>();
        for (EnvelopeEntry entry : found) {
            entry.payeeId().ifPresent(payeeIds::add);
        }
        Map<UUID, String> payeeNames = new HashMap<>();
        if (!payeeIds.isEmpty()) {
            for (Payee payee : payees.findAllByHouseholdIdAndIdIn(household.id(), payeeIds)) {
                payeeNames.put(payee.id(), payee.name());
            }
        }

        List<EnvelopeEntryView> views = new ArrayList<>(found.size());
        for (EnvelopeEntry entry : found) {
            views.add(
                    new EnvelopeEntryView(
                            entry.id(),
                            entry.envelopeId(),
                            entry.date(),
                            Money.of(entry.amount(), currency),
                            entry.payeeId().orElse(null),
                            entry.payeeId().map(payeeNames::get).orElse(null),
                            entry.note().orElse(null),
                            entry.source(),
                            entry.transactionId().orElse(null),
                            entry.createdAt(),
                            entry.updatedAt(),
                            entry.version()));
        }
        return views;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    /**
     * See {@link AmountText}: the shape and precision rules live there, once.
     *
     * @param mustBePositive true for an entry, where zero used nothing; false for an envelope,
     *     where deciding to spend nothing is a real decision
     */
    private static Money money(String text, Currency currency, boolean mustBePositive) {
        return AmountText.parse(
                text,
                currency,
                mustBePositive ? AmountText.Sign.POSITIVE : AmountText.Sign.NOT_NEGATIVE,
                "amount");
    }
}
