package com.budgetowl.transfer.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.domain.TransactionStatus;
import com.budgetowl.transaction.persistence.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers: money moving between two of the household's own accounts.
 *
 * <p>Scoping and roles work as everywhere else — membership resolved first, every repository call
 * given that household's id, a {@code VIEWER} never writes, another household's row a {@code 404}.
 * <b>Both</b> account ids are resolved the same way a plain transaction's single one is: a transfer
 * must not become a side door into an account the caller could not otherwise write to.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>A transfer is a pair, written in one database transaction</b> (ADR-0033). There is no
 *       moment at which money has left one account and arrived nowhere, and {@code
 *       trg_transactions_transfer_pair} checks the shape at {@code COMMIT} rather than trusting
 *       this class to have got it right.
 *   <li><b>Across currencies the caller supplies both figures.</b> No rate is fetched, inferred or
 *       stored. What the bank actually did is on a statement the household is holding, and anything
 *       we computed would disagree with it (ADR-0022).
 *   <li><b>A transfer has no category and no payee.</b> It is not spending and the other side is
 *       not a third party.
 *   <li><b>The two accounts must differ.</b> A transfer to itself is two rows that cancel.
 * </ul>
 *
 * <p><b>Nothing here logs an amount.</b> Audit lines carry ids.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /**
     * Places kept on the implied rate shown beside a cross-currency transfer.
     *
     * <p>Six, which is more than a bank quotes and enough that the division does not look rounded
     * to the figure it came from. It is a derived label, not an input — see {@code
     * TransferView.impliedRate}.
     *
     * <p><b>{@code HALF_UP}, where ADR-0006 mandates {@code HALF_EVEN} for money.</b> This is not
     * money: it is a ratio printed beside two figures that are themselves exact, nothing adds it
     * up, and no balance depends on it. {@code HALF_EVEN} exists to stop rounding bias accumulating
     * across many sums, and there is no sum here to accumulate across.
     */
    private static final int RATE_SCALE = 6;

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final MembershipService memberships;

    public TransferService(
            TransactionRepository transactions,
            AccountRepository accounts,
            MembershipService memberships) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public TransferView get(UUID actorId, UUID transferGroupId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        return view(household, requireLegs(transferGroupId, household.id()));
    }

    /**
     * Creates both legs.
     *
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws NotFoundException if either account is not this household's
     * @throws ConflictException {@code account-archived}
     * @throws InvalidRequestException {@code validation-failed} for the same account twice, or for
     *     a second amount that is missing when it is needed or given when it is not
     */
    @Transactional
    public TransferView create(UUID actorId, NewTransfer requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();

        Account from = requireAccount(requested.fromAccountId(), household.id(), "fromAccountId");
        Account to = requireAccount(requested.toAccountId(), household.id(), "toAccountId");
        requireDifferentAccounts(from, to);
        requireOpen(from);
        requireOpen(to);

        Money sent = amount(requested.amount(), from.currency(), "amount");
        Money received = received(requested.amountReceived(), sent, from, to);

        UUID groupId = UUID.randomUUID();
        Transaction out = leg(from, requested.date(), sent.negated(), groupId, requested.note());
        Transaction in = leg(to, requested.date(), received, groupId, requested.note());

        // Flushed, and safe to flush one at a time: the pair rule is DEFERRABLE INITIALLY
        // DEFERRED and is judged at COMMIT, so a half-written pair mid-transaction is expected
        // rather than a violation. Flushing is what makes the response's version the one the
        // database holds — see `update`, where returning the pre-flush number made every
        // response stale the moment it was sent.
        transactions.saveAndFlush(out);
        transactions.saveAndFlush(in);

        log.info(
                "transfer created householdId={} transferGroupId={} fromAccountId={}"
                        + " toAccountId={} byUserId={} crossCurrency={}",
                household.id(),
                groupId,
                from.id(),
                to.id(),
                actorId,
                !from.currency().equals(to.currency()));
        return view(household, List.of(out, in));
    }

    /**
     * Rewrites both legs: the date, the figures, the note.
     *
     * <p><b>Not the accounts</b> — see {@code TransferChanges}. That keeps this method a
     * field-for-field edit of two existing rows, so both versions bump together and the pair's
     * version only ever moves forward.
     */
    @Transactional
    public TransferView update(UUID actorId, UUID transferGroupId, TransferChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        List<Transaction> legs = requireLegs(transferGroupId, household.id());
        Transaction out = legs.get(0);
        Transaction in = legs.get(1);

        requireCurrentVersion(pairVersion(legs), changes.version());

        Account from = account(out.accountId(), household.id());
        Account to = account(in.accountId(), household.id());
        // An account archived AFTER the transfer happened does not invalidate it: the money
        // already moved. Only a new transfer through an archived account is refused.

        LocalDate date = changes.date() == null ? out.date() : changes.date();
        Money sent =
                changes.amount() == null
                        ? Money.of(out.amount().abs(), from.currency())
                        : amount(changes.amount(), from.currency(), "amount");
        Money received = receivedAfter(changes.amountReceived(), sent, in, from, to);
        String note = noteAfter(changes.note(), out);

        out.on(date);
        out.correctAmount(sent.negated(), from.currency());
        out.annotate(note);
        in.on(date);
        in.correctAmount(received, to.currency());
        in.annotate(note);

        log.info(
                "transfer updated householdId={} transferGroupId={} byUserId={}",
                household.id(),
                transferGroupId,
                actorId);
        // saveAndFlush, not save: @Version is bumped at flush, and flush would otherwise happen
        // at commit — AFTER the view is built. The response would then carry the version the
        // caller already had, and its next edit would be refused as stale. A test that edited
        // twice in a row is what caught it; one edit always looked fine.
        return view(
                household, List.of(transactions.saveAndFlush(out), transactions.saveAndFlush(in)));
    }

    /** Removes both legs. Neither can be removed alone — {@code TransactionService} refuses it. */
    @Transactional
    public void delete(UUID actorId, UUID transferGroupId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();

        for (Transaction leg : requireLegs(transferGroupId, householdId)) {
            transactions.delete(leg);
        }
        log.info(
                "transfer deleted householdId={} transferGroupId={} byUserId={}",
                householdId,
                transferGroupId,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    private static Transaction leg(
            Account account, LocalDate date, Money amount, UUID groupId, String note) {
        // CLEARED, as a typed transaction is: somebody moving their own money watched it happen.
        Transaction leg = Transaction.of(account, date, amount, TransactionStatus.CLEARED);
        leg.linkToTransfer(groupId);
        leg.annotate(note);
        return leg;
    }

    /**
     * What arrives after an edit.
     *
     * <p><b>Absent means unchanged</b>, as every other field in every other {@code PATCH} in this
     * codebase does — so a note-only edit of a cross-currency transfer is a note-only edit.
     * Requiring the figure on every edit, as an earlier version did, made {@code PATCH
     * {"note":"holiday money","version":4}} a {@code 400} naming a field the caller had no reason
     * to send.
     *
     * <p>Within one currency the two legs must agree, so a changed {@code amount} moves both and
     * {@code amountReceived} is still refused: there it would be a second name for the same figure
     * and a chance for the two to disagree with no tie-break.
     */
    private static Money receivedAfter(
            String text, Money sent, Transaction in, Account from, Account to) {
        if (from.currency().equals(to.currency())) {
            if (text != null) {
                throw refuseSecondFigure();
            }
            return Money.of(sent.amount(), to.currency());
        }
        // Across currencies the legs are independent facts, so leaving one alone leaves it alone.
        // Recomputing it from the other would silently rewrite a balance nobody asked to change.
        return text == null
                ? Money.of(in.amount(), to.currency())
                : amount(text, to.currency(), "amountReceived");
    }

    private static InvalidRequestException refuseSecondFigure() {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED,
                "both accounts are in the same currency, so the amount received is the amount"
                        + " sent",
                Map.of("field", "amountReceived"));
    }

    /**
     * What arrives, and whether the caller was allowed to say.
     *
     * <p>Required across currencies and refused within one. Refused rather than ignored: a second
     * figure that silently did nothing would be a client believing it had recorded something it had
     * not, and the two could then disagree with no way to tell which was meant.
     */
    private static Money received(String text, Money sent, Account from, Account to) {
        boolean crossCurrency = !from.currency().equals(to.currency());
        if (!crossCurrency) {
            if (text != null) {
                throw refuseSecondFigure();
            }
            return Money.of(sent.amount(), to.currency());
        }
        if (text == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "these accounts are in different currencies, so say what arrived",
                    Map.of("field", "amountReceived"));
        }
        return amount(text, to.currency(), "amountReceived");
    }

    /** Builds the view, naming both accounts and deriving the rate that nothing reads back. */
    private TransferView view(Household household, List<Transaction> legs) {
        Transaction out = legs.get(0);
        Transaction in = legs.get(1);
        Account from = account(out.accountId(), household.id());
        Account to = account(in.accountId(), household.id());

        Money sent = Money.of(out.amount().abs(), from.currency());
        Money received = Money.of(in.amount(), to.currency());
        boolean crossCurrency = !from.currency().equals(to.currency());

        return new TransferView(
                out.transferGroupId().orElseThrow(),
                from.id(),
                from.name(),
                to.id(),
                to.name(),
                out.date(),
                sent,
                received,
                crossCurrency,
                crossCurrency ? impliedRate(sent, received) : null,
                out.note().orElse(null),
                out.createdAt(),
                laterOf(out.updatedAt(), in.updatedAt()),
                pairVersion(legs));
    }

    /**
     * What the two figures imply, for a label.
     *
     * <p>Null-safe against a zero send, which {@code AmountText} already refuses but which a row
     * written before this feature existed could in principle hold.
     */
    private static BigDecimal impliedRate(Money sent, Money received) {
        if (sent.isZero()) {
            return null;
        }
        return received.amount().divide(sent.amount(), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The pair's version: the sum of the legs'.
     *
     * <p>Both legs are written together and bumped together, so the sum moves by two on every edit
     * and is stale exactly when either leg is. One number for the caller to echo, and it only ever
     * moves forward — which holds because a leg is never replaced, only corrected. An earlier draft
     * moved a leg between accounts by deleting the row and writing a new one at version zero, and
     * the sum could then go DOWN; a stale version could coincide with a later one and an edit would
     * land on a transfer the client had not seen.
     */
    private static long pairVersion(List<Transaction> legs) {
        return legs.stream().mapToLong(Transaction::version).sum();
    }

    private static java.time.Instant laterOf(java.time.Instant left, java.time.Instant right) {
        if (left == null) {
            return right;
        }
        return right == null || left.isAfter(right) ? left : right;
    }

    private static String noteAfter(Change<String> change, Transaction leg) {
        return switch (change) {
            case Change.Unchanged<String> ignored -> leg.note().orElse(null);
            case Change.Cleared<String> ignored -> null;
            case Change.SetTo<String> written -> written.value();
        };
    }

    /**
     * Both legs, or a {@code 404}.
     *
     * <p>A group with anything but two rows is what {@code trg_transactions_transfer_pair} exists
     * to prevent, so finding one here means the database has been changed by something that is not
     * this application. It is reported as a server error rather than rendered, because showing half
     * a transfer as though it were whole is how a wrong balance becomes a believed balance.
     */
    private List<Transaction> requireLegs(UUID transferGroupId, UUID householdId) {
        List<Transaction> legs = transactions.findTransferLegs(householdId, transferGroupId);
        if (legs.isEmpty()) {
            throw new NotFoundException("no such transfer");
        }
        if (legs.size() != 2) {
            throw new IllegalStateException(
                    "transfer " + transferGroupId + " has " + legs.size() + " legs");
        }
        return legs;
    }

    private Account account(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new IllegalStateException("a transfer leg has no account"));
    }

    private Account requireAccount(UUID accountId, UUID householdId, String field) {
        if (accountId == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "an account is required", Map.of("field", field));
        }
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    private static void requireDifferentAccounts(Account from, Account to) {
        if (from.id().equals(to.id())) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a transfer moves money between two different accounts",
                    Map.of("field", "toAccountId"));
        }
    }

    private static void requireOpen(Account account) {
        if (account.isArchived()) {
            throw new ConflictException(
                    ErrorCode.ACCOUNT_ARCHIVED,
                    "this account is archived; restore it to move money through it");
        }
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the transfer changed since it was read");
        }
    }

    private static Money amount(String text, java.util.Currency currency, String field) {
        return AmountText.parse(text, currency, AmountText.Sign.POSITIVE, field);
    }
}
