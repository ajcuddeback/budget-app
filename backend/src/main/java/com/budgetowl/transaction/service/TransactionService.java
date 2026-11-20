package com.budgetowl.transaction.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.category.domain.Category;
import com.budgetowl.category.persistence.CategoryRepository;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.payee.domain.Payee;
import com.budgetowl.payee.persistence.PayeeRepository;
import com.budgetowl.payee.service.PayeeService;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.domain.TransactionStatus;
import com.budgetowl.transaction.persistence.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactions: the record of what actually happened to the household's money.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes, another household's row a {@code 404}
 * and never a {@code 403}. The database carries the same rule structurally: {@code
 * fk_transactions_account} is composite on {@code (account_id, household_id)}, so a transaction
 * cannot physically reference another household's account however wrong this class gets.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>An amount is in its account's currency.</b> There is no currency on a transaction and
 *       none accepted from the caller: it is read from the account. This is what makes {@code
 *       account-currency-immutable} meaningful rather than decorative.
 *   <li><b>An archived account takes no new transactions.</b> Un-archiving is one click and is a
 *       deliberate act; an archived account quietly accepting money and then not appearing in the
 *       list it was recorded against is the bug that behaviour would cause.
 *   <li><b>A category is optional and a payee is created on the way past.</b> Recording what
 *       happened must never be gated on tidying up first — "Needs a category" is a work queue, not
 *       an error, and that is the single biggest difference between a budget somebody keeps using
 *       and one they abandon.
 *   <li><b>The account cannot be edited.</b> See {@link TransactionChanges}.
 * </ul>
 *
 * <p><b>Nothing here logs an amount, a payee or a note.</b> Audit lines carry ids only. An amount
 * is somebody's money, a payee says where they shop and a note is whatever they wrote.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final PayeeService payeeService;
    private final MembershipService memberships;

    public TransactionService(
            TransactionRepository transactions,
            AccountRepository accounts,
            CategoryRepository categories,
            PayeeRepository payees,
            PayeeService payeeService,
            MembershipService memberships) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.payees = payees;
        this.payeeService = payeeService;
        this.memberships = memberships;
    }

    /** The ledger, newest first, narrowed by whichever filters were supplied. */
    @Transactional(readOnly = true)
    public TransactionPage list(UUID actorId, LedgerFilter filter, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();

        Pageable pageable = pageable(page, size);
        // One query with every filter bound, including the work queue. An earlier version routed
        // "uncategorised" to a method of its own, which silently discarded the account, date and
        // search the caller had also set — a data bug on a financial screen, found in review.
        Page<Transaction> found =
                transactions.findLedger(
                        householdId,
                        filter.accountId(),
                        filter.categoryId(),
                        filter.uncategorisedOnly(),
                        filter.fromOrEarliest(),
                        filter.toOrLatest(),
                        likePattern(filter.search()),
                        pageable);

        List<TransactionView> views = views(householdId, found.getContent());
        return new TransactionPage(
                views,
                pageTotal(views),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TransactionView get(UUID actorId, UUID transactionId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        Transaction transaction = require(transactionId, householdId);
        return views(householdId, List.of(transaction)).getFirst();
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws NotFoundException for an account or category that is not this household's
     * @throws ConflictException {@code account-archived}
     * @throws InvalidRequestException for an unparseable amount, a zero amount, or an amount whose
     *     scale exceeds four decimal places
     */
    @Transactional
    public TransactionView create(UUID actorId, NewTransaction requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        UUID householdId = household.id();

        Account account = requireAccount(requested.accountId(), householdId);
        if (account.isArchived()) {
            throw new ConflictException(
                    ErrorCode.ACCOUNT_ARCHIVED,
                    "the account is archived; un-archive it to record against it");
        }

        Money amount = money(requested.amount(), account.currency());
        Transaction transaction =
                Transaction.of(account, requested.date(), amount, status(requested.status()));

        transaction.payTo(resolvePayee(household, requested.payeeName()));
        transaction.fileUnder(requireCategoryOrNull(requested.categoryId(), householdId));
        transaction.annotate(requested.note());

        Transaction saved = transactions.saveAndFlush(transaction);
        log.info(
                "transaction created householdId={} transactionId={} accountId={} byUserId={}"
                        + " categorised={}",
                householdId,
                saved.id(),
                account.id(),
                actorId,
                saved.categoryId().isPresent());
        return views(householdId, List.of(saved)).getFirst();
    }

    /**
     * @throws ConflictException {@code stale-version}
     */
    @Transactional
    public TransactionView update(UUID actorId, UUID transactionId, TransactionChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        UUID householdId = household.id();

        Transaction transaction = require(transactionId, householdId);
        requireCurrentVersion(transaction, changes.version());

        // The account cannot change, so its currency is fixed for the life of the row — which is
        // what lets an amount be re-stated without the caller naming a currency.
        Account account = requireAccount(transaction.accountId(), householdId);

        if (changes.date() != null) {
            transaction.on(changes.date());
        }
        if (changes.amount() != null) {
            transaction.correctAmount(
                    money(changes.amount(), account.currency()), account.currency());
        }
        switch (changes.payeeName()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> transaction.payTo(null);
            case Change.SetTo<String> named ->
                    transaction.payTo(resolvePayee(household, named.value()));
        }
        switch (changes.categoryId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            // Back into "Needs a category" — a state the user can choose, not only arrive at.
            case Change.Cleared<UUID> ignored -> transaction.fileUnder(null);
            case Change.SetTo<UUID> filed ->
                    transaction.fileUnder(requireCategory(filed.value(), householdId).id());
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> transaction.annotate(null);
            case Change.SetTo<String> written -> transaction.annotate(written.value());
        }
        if (changes.status() != null) {
            transaction.markAs(status(changes.status()));
        }

        Transaction saved = transactions.saveAndFlush(transaction);
        log.info(
                "transaction updated householdId={} transactionId={} byUserId={}",
                householdId,
                transactionId,
                actorId);
        return views(householdId, List.of(saved)).getFirst();
    }

    /**
     * Deletes a transaction outright.
     *
     * <p>A real delete, not a tombstone: a household that typed the wrong amount needs the wrong
     * figure gone, and every later sum would otherwise have to remember to exclude it. Corrections
     * keep their trail through {@code updated_at} and the version counter, which is what
     * "append-mostly" means in {@code docs/domain/model.md}.
     *
     * <p>Half of a transfer cannot be deleted on its own — that would leave money that left one
     * account and arrived nowhere. Slice 8 deletes the pair; until transfers exist, no row has a
     * {@code transferGroupId} and the guard is unreachable by construction, so it is written now
     * rather than discovered later.
     */
    @Transactional
    public void delete(UUID actorId, UUID transactionId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Transaction transaction = require(transactionId, householdId);

        if (transaction.isTransfer()) {
            throw new ConflictException(
                    ErrorCode.CONFLICT,
                    "this is one half of a transfer; delete the transfer instead");
        }

        transactions.delete(transaction);
        log.info(
                "transaction deleted householdId={} transactionId={} byUserId={}",
                householdId,
                transactionId,
                actorId);
    }

    /** Payee suggestions for the entry form, scoped to the caller's household. */
    @Transactional(readOnly = true)
    public List<String> suggestPayees(UUID actorId, String typed) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        return payeeService.suggest(membership.household().id(), typed).stream()
                .map(Payee::name)
                .toList();
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Resolves a typed payee name to an id, creating the payee if the household has not used it
     * before. Blank or null is no payee at all, which is ordinary — cash out of a machine has none.
     */
    private UUID resolvePayee(Household household, String typedName) {
        if (typedName == null || typedName.isBlank()) {
            return null;
        }
        return payeeService.getOrCreate(household, typedName).id();
    }

    private Transaction require(UUID transactionId, UUID householdId) {
        return transactions
                .findByIdAndHouseholdId(transactionId, householdId)
                .orElseThrow(() -> new NotFoundException("no such transaction"));
    }

    private Account requireAccount(UUID accountId, UUID householdId) {
        if (accountId == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "an account is required",
                    Map.of("field", "accountId"));
        }
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
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
    private static void requireCurrentVersion(Transaction transaction, long expected) {
        if (transaction.version() != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the transaction changed since it was read");
        }
    }

    /**
     * Builds the views for a page, resolving account, category and payee names in <b>three queries
     * for the whole page</b> rather than three per row.
     *
     * <p>A ledger is the most-looked-at screen in the product; per-row lookups here would be the
     * N+1 that makes it feel slow with a year of history, which is exactly when somebody starts
     * relying on it.
     */
    private List<TransactionView> views(UUID householdId, List<Transaction> found) {
        if (found.isEmpty()) {
            return List.of();
        }

        Set<UUID> accountIds = new HashSet<>();
        Set<UUID> categoryIds = new HashSet<>();
        Set<UUID> payeeIds = new HashSet<>();
        for (Transaction transaction : found) {
            accountIds.add(transaction.accountId());
            transaction.categoryId().ifPresent(categoryIds::add);
            transaction.payeeId().ifPresent(payeeIds::add);
        }

        Map<UUID, Account> accountsById = new HashMap<>();
        for (Account account : accounts.findAllByHouseholdIdAndIdIn(householdId, accountIds)) {
            accountsById.put(account.id(), account);
        }

        Map<UUID, Category> categoriesById = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category category :
                    categories.findAllByHouseholdIdAndIdIn(householdId, categoryIds)) {
                categoriesById.put(category.id(), category);
            }
            // The parents, for rendering "Bills → Utilities". One more query, not one per row.
            Set<UUID> parentIds = new HashSet<>();
            for (Category category : categoriesById.values()) {
                category.parentId()
                        .filter(id -> !categoriesById.containsKey(id))
                        .ifPresent(parentIds::add);
            }
            if (!parentIds.isEmpty()) {
                for (Category parent :
                        categories.findAllByHouseholdIdAndIdIn(householdId, parentIds)) {
                    categoriesById.put(parent.id(), parent);
                }
            }
        }

        Map<UUID, String> payeeNames = new HashMap<>();
        if (!payeeIds.isEmpty()) {
            for (Payee payee : payees.findAllByHouseholdIdAndIdIn(householdId, payeeIds)) {
                payeeNames.put(payee.id(), payee.name());
            }
        }

        List<TransactionView> views = new ArrayList<>(found.size());
        for (Transaction transaction : found) {
            views.add(view(transaction, accountsById, categoriesById, payeeNames));
        }
        return views;
    }

    private static TransactionView view(
            Transaction transaction,
            Map<UUID, Account> accountsById,
            Map<UUID, Category> categoriesById,
            Map<UUID, String> payeeNames) {

        Account account = accountsById.get(transaction.accountId());
        // Unreachable through the API — fk_transactions_account guarantees the row exists and
        // belongs to this household — but this is load-bearing rather than defensive padding.
        // Deliberately breaking findLedger's household predicate makes this throw instead of
        // leaking: a transaction whose account this household cannot read is not rendered at all.
        // MoneyCrossHouseholdIT demonstrates exactly that, so do not downgrade it to a null check
        // that returns a blank row.
        if (account == null) {
            throw new IllegalStateException(
                    "transaction " + transaction.id() + " references an unreadable account");
        }

        Category category = transaction.categoryId().map(categoriesById::get).orElse(null);
        String parentName =
                category == null
                        ? null
                        : category.parentId()
                                .map(categoriesById::get)
                                .map(Category::name)
                                .orElse(null);

        return new TransactionView(
                transaction.id(),
                account.id(),
                account.name(),
                transaction.date(),
                Money.of(transaction.amount(), account.currency()),
                transaction.payeeId().orElse(null),
                transaction.payeeId().map(payeeNames::get).orElse(null),
                category == null ? null : category.id(),
                category == null ? null : category.name(),
                parentName,
                transaction.note().orElse(null),
                transaction.status(),
                transaction.isTransfer(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.version());
    }

    /**
     * The sum of this page, or null when the page mixes currencies.
     *
     * <p>Null rather than a converted figure: conversion is display-only (ADR-0022) and a
     * server-side cross-currency total would be a number the user could not check against anything.
     * A single-account ledger — the case the figure is actually for — always has one currency.
     */
    private static Money pageTotal(List<TransactionView> views) {
        if (views.isEmpty()) {
            return null;
        }
        Currency currency = views.getFirst().amount().currency();
        Money total = Money.zero(currency);
        for (TransactionView view : views) {
            if (!view.amount().currency().equals(currency)) {
                return null;
            }
            total = total.plus(view.amount());
        }
        return total;
    }

    /**
     * Turns a search term into a {@code like} pattern: escaped, lowercased, wrapped for a {@code
     * contains} match. <b>Never returns null</b> — no search at all is {@code "%"}, which matches
     * every row.
     *
     * <p>Both halves of that are forced by the database rather than chosen. The lowercasing is here
     * because {@code lower(:search)} in JPQL made PostgreSQL resolve {@code lower(bytea)} on a null
     * bind; the match-all is here because a null parameter appearing only inside a {@code like} has
     * no type at all and the server refuses the statement outright. {@code TransactionRepository}
     * carries the full account. The query lowercases the column, this lowercases the pattern, and
     * the two have to stay in step.
     */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped =
                search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return ("%" + escaped + "%").toLowerCase(Locale.ROOT);
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    private static TransactionStatus status(String name) {
        if (name == null) {
            // Somebody typing a transaction watched it happen; they are the authority on that.
            return TransactionStatus.CLEARED;
        }
        try {
            return TransactionStatus.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "unknown transaction status",
                    Map.of("field", "status"));
        }
    }

    /**
     * Parses a decimal string into the account's currency.
     *
     * <p>The edge's {@code @Pattern} produces the good message for a malformed amount; this is the
     * backstop for anything reaching the service without passing it, and the rejected text is never
     * echoed back — it is an amount. Zero and an over-precise amount are rejected here rather than
     * silently rounded: rounding somebody's money without telling them is the defect ADR-0006
     * exists to prevent.
     */
    private static Money money(String text, Currency currency) {
        // Checked rather than caught: a missing amount and an unparseable one are the same answer
        // to the caller, but catching NullPointerException to discover it is the kind of control
        // flow that hides a real NPE from somewhere else in the method.
        if (text == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "an amount is required",
                    Map.of("field", "amount"));
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text.strip());
        } catch (NumberFormatException malformed) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unparseable amount", Map.of("field", "amount"));
        }
        if (parsed.signum() == 0) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a transaction of zero moves no money",
                    Map.of("field", "amount"));
        }
        if (parsed.stripTrailingZeros().scale() > Money.SCALE) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "an amount may have at most " + Money.SCALE + " decimal places",
                    Map.of("field", "amount"));
        }
        return Money.of(parsed, currency);
    }
}
