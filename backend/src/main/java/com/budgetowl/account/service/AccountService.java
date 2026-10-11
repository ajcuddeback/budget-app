package com.budgetowl.account.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.domain.AccountType;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts: the household's places money sits, and the first service over genuinely financial data.
 *
 * <p>Three rules are enforced here and nowhere else, because here is the only place that can see
 * all three at once:
 *
 * <ul>
 *   <li><b>Which household.</b> Every call starts by resolving the caller's membership ({@link
 *       MembershipService}) and every repository call is given that household's id. An account id
 *       from the path is a lookup key and never evidence of ownership, so another household's
 *       account — or one that never existed — is an empty {@code Optional} and then a {@code 404}.
 *       Never a {@code 403}: that would confirm the row exists (ADR-0008, amended by ADR-0017).
 *   <li><b>What the role permits.</b> A {@code VIEWER} reads and never writes. Every write below
 *       begins with {@link MembershipService#requireWriteAccess}, which is one line and is the
 *       single highest-value authorization control in the codebase since ADR-0026 removed the
 *       second household (docs/guides/testing-style.md).
 *   <li><b>What the stored money means.</b> Balance is derived, the opening balance is denominated
 *       in the account's own currency, and the currency cannot be reinterpreted once there is
 *       anything to reinterpret.
 * </ul>
 *
 * <p><b>Nothing here logs a value.</b> Audit lines name the account by id and the actor by user id.
 * A name is whatever the user typed, {@code lastFour} is a partial financial identifier (ADR-0028)
 * and an opening balance is somebody's money — all three belong in the database and none of them
 * belongs in the log stream.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final MembershipService memberships;
    private final AccountTransactions transactions;

    public AccountService(
            AccountRepository accounts,
            MembershipService memberships,
            AccountTransactions transactions) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.transactions = transactions;
    }

    /**
     * The household's accounts, ordered by name case-insensitively with a stable tiebreak on id.
     *
     * @param includeArchived false — the default — hides retired accounts, which is what every
     *     picker and the default list want
     */
    @Transactional(readOnly = true)
    public AccountPage list(UUID actorId, boolean includeArchived, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();

        Pageable pageable = pageable(page, size);
        Page<Account> found =
                includeArchived
                        ? accounts.findAllByHouseholdId(householdId, pageable)
                        : accounts.findUnarchivedByHouseholdId(householdId, pageable);

        return new AccountPage(
                views(householdId, found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AccountView get(UUID actorId, UUID accountId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        return view(householdId, require(accountId, householdId));
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws InvalidRequestException for an unknown currency or an unrecognised type
     */
    @Transactional
    public AccountView create(UUID actorId, NewAccount requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();

        Currency currency = currency(requested.currency());
        Account account =
                Account.of(
                        membership.household(),
                        requested.name(),
                        type(requested.type()),
                        Money.of(amount(requested.openingBalance()), currency));
        account.identifyBy(requested.lastFour());

        Account saved = accounts.saveAndFlush(account);
        log.info(
                "account created householdId={} accountId={} byUserId={} type={}",
                householdId,
                saved.id(),
                actorId,
                saved.type());
        return view(householdId, saved);
    }

    /**
     * Applies whatever the caller named and leaves the rest alone.
     *
     * <p>Order matters in one place: the currency is re-denominated before the opening balance is
     * corrected, so a {@code PATCH} that changes both reads the new amount as being in the new
     * currency rather than being rejected for disagreeing with the old one.
     *
     * @throws ConflictException {@code stale-version} when somebody else saved first, and {@code
     *     account-currency-immutable} when the account already has a transaction
     */
    @Transactional
    public AccountView update(UUID actorId, UUID accountId, AccountChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Account account = require(accountId, householdId);

        requireCurrentVersion(account, changes.version());

        if (changes.name() != null) {
            account.rename(changes.name());
        }
        switch (changes.lastFour()) {
            case LastFourChange.Unchanged ignored -> {
                // The caller did not mention the digits, so the account keeps whatever it has.
            }
            case LastFourChange.Cleared ignored -> account.identifyBy(null);
            case LastFourChange.SetTo digits -> account.identifyBy(digits.digits());
        }
        if (changes.type() != null) {
            account.changeTypeTo(type(changes.type()));
        }
        if (changes.currency() != null) {
            redenominate(householdId, account, currency(changes.currency()));
        }
        if (changes.openingBalance() != null) {
            account.correctOpeningBalance(
                    Money.of(amount(changes.openingBalance()), account.currency()));
        }

        // Flushed before the view is built: @Version is incremented at flush, so a response
        // assembled before it would hand the caller back the version they already had and their
        // next PATCH would be refused as stale.
        Account saved = accounts.saveAndFlush(account);
        log.info(
                "account updated householdId={} accountId={} byUserId={}",
                householdId,
                accountId,
                actorId);
        return view(householdId, saved);
    }

    /** Idempotent: archiving an archived account is the state the caller asked for. */
    @Transactional
    public AccountView archive(UUID actorId, UUID accountId) {
        return setArchived(actorId, accountId, true);
    }

    @Transactional
    public AccountView unarchive(UUID actorId, UUID accountId) {
        return setArchived(actorId, accountId, false);
    }

    /**
     * A hard delete, and only while the account has no transactions.
     *
     * <p>With transactions it is a {@code 409} naming archiving as the alternative: deleting would
     * orphan or silently destroy financial history, and a budgeting app that loses your records is
     * not one anybody keeps using. Empty, there is nothing to preserve and a graveyard of
     * tombstoned empties is worse than the row being gone.
     *
     * @throws ConflictException {@code account-not-empty}
     */
    @Transactional
    public void delete(UUID actorId, UUID accountId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Account account = require(accountId, householdId);

        if (transactions.existForAccount(householdId, accountId)) {
            throw new ConflictException(
                    ErrorCode.ACCOUNT_NOT_EMPTY,
                    "the account has transactions; archive it instead");
        }

        accounts.delete(account);
        log.info(
                "account deleted householdId={} accountId={} byUserId={}",
                householdId,
                accountId,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    private AccountView setArchived(UUID actorId, UUID accountId, boolean archived) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Account account = require(accountId, householdId);

        if (archived) {
            account.archive();
        } else {
            account.unarchive();
        }

        Account saved = accounts.saveAndFlush(account);
        log.info(
                "account {} householdId={} accountId={} byUserId={}",
                archived ? "archived" : "unarchived",
                householdId,
                accountId,
                actorId);
        return view(householdId, saved);
    }

    /**
     * Re-denominates, or refuses.
     *
     * <p>Changing to the currency the account already has is not a change and is never refused: a
     * client that {@code PATCH}es the whole form back, currency included, must not be told the
     * account is immutable because it sent the value it just read.
     */
    private void redenominate(UUID householdId, Account account, Currency wanted) {
        if (wanted.equals(account.currency())) {
            return;
        }
        if (transactions.existForAccount(householdId, account.id())) {
            // The stored figures do not move, so £100 would become €100 with nothing in the UI
            // admitting it happened. Refused, never applied and never a silent no-op.
            throw new ConflictException(
                    ErrorCode.ACCOUNT_CURRENCY_IMMUTABLE,
                    "the account has transactions; its currency can no longer be changed");
        }
        account.changeCurrencyTo(wanted);
    }

    private Account require(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    /**
     * The optimistic lock, checked before anything is applied.
     *
     * <p>This is the clear message, not the control. Hibernate's {@code @Version} is the control:
     * it re-checks at flush, which is the only place a conflict opened after this read can be seen,
     * and the handler answers {@code 409 stale-version} for it as well. A guard that could be the
     * whole story would be one that has to hold a lock for the length of a user's edit.
     */
    private static void requireCurrentVersion(Account account, long expected) {
        if (account.version() != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the account changed since it was read");
        }
    }

    private List<AccountView> views(UUID householdId, List<Account> found) {
        Map<UUID, Money> totals =
                transactions.totalsByAccount(householdId, found.stream().map(Account::id).toList());
        List<AccountView> views = new ArrayList<>(found.size());
        for (Account account : found) {
            views.add(view(account, totals));
        }
        return views;
    }

    private AccountView view(UUID householdId, Account account) {
        return view(account, transactions.totalsByAccount(householdId, List.of(account.id())));
    }

    /**
     * <b>Balance is computed here, from two terms, and is never read from a column.</b>
     *
     * <p>Today the second term is zero for every account, because there is no transactions table
     * yet — so {@code balance} equals {@code openingBalance}, and that is a fact about the data
     * rather than about the code. Slice 5 supplies a non-empty {@link AccountTransactions} and this
     * line starts producing different numbers without being edited, which is the point: a {@code
     * balance()} that returned the opening balance would keep compiling and start lying
     * (docs/features/accounts.md, and the Javadoc on {@code Account} explaining why the entity
     * deliberately has no such method).
     */
    private static AccountView view(Account account, Map<UUID, Money> totals) {
        Money opening = account.openingBalance();
        Money posted = totals.getOrDefault(account.id(), Money.zero(account.currency()));
        return new AccountView(
                account.id(),
                account.name(),
                account.lastFour().orElse(null),
                account.type(),
                opening,
                opening.plus(posted),
                account.isArchived(),
                account.createdAt(),
                account.updatedAt(),
                account.version());
    }

    private static Pageable pageable(int page, int size) {
        // Clamped, not rejected (docs/guides/api-style.md). The bounds come from the envelope
        // every collection in this API is returned in, so the two cannot drift apart.
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    /**
     * ISO-4217, checked against the codes this JVM knows rather than only for shape. {@code "ZZZ"}
     * is three uppercase letters and is not a currency.
     */
    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown currency", Map.of("field", "currency"));
        }
    }

    /**
     * A closed set, and an unknown value is a {@code 400} naming the field rather than a {@code
     * malformed-request} naming nothing — which is what accepting the enum at the edge would give.
     */
    private static AccountType type(String name) {
        try {
            return AccountType.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown account type", Map.of("field", "type"));
        }
    }

    /**
     * The edge's {@code @Pattern} is what produces the good message for a malformed amount; this is
     * the backstop for anything that reaches the service without passing it. Never a silent zero
     * (docs/features/accounts.md), and the rejected text is not echoed — it is an amount.
     */
    private static BigDecimal amount(String text) {
        try {
            return new BigDecimal(text.strip());
        } catch (NumberFormatException malformed) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "unparseable amount",
                    Map.of("field", "openingBalance"));
        }
    }
}
