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
import com.budgetowl.envelope.persistence.EnvelopeEntryRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.domain.TransactionStatus;
import com.budgetowl.transaction.persistence.TransactionRepository;
import com.budgetowl.transaction.service.TransactionService;
import com.budgetowl.transaction.service.TransactionView;
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
    private final TransactionService ledger;
    private final EnvelopeEntryRepository envelopeEntries;

    public TransferService(
            TransactionRepository transactions,
            AccountRepository accounts,
            MembershipService memberships,
            TransactionService ledger,
            EnvelopeEntryRepository envelopeEntries) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.memberships = memberships;
        this.ledger = ledger;
        this.envelopeEntries = envelopeEntries;
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

        // Only what was sent is written (file-import.md rule 12, change 2). Each leg keeps its own
        // date and note — a pair made from two statements clears on two days — so an edit of the
        // amount must not quietly move the incoming row to the outgoing row's date. A date or a
        // note that IS sent goes on both legs, as "Move money" has always done.
        if (changes.date() != null) {
            out.on(changes.date());
            in.on(changes.date());
        }
        if (changes.amount() != null) {
            Money sent = amount(changes.amount(), from.currency(), "amount");
            out.correctAmount(sent.negated(), from.currency());
            in.correctAmount(
                    receivedAfter(changes.amountReceived(), sent, in, from, to), to.currency());
        } else if (changes.amountReceived() != null) {
            Money sent = Money.of(out.amount().abs(), from.currency());
            in.correctAmount(
                    receivedAfter(changes.amountReceived(), sent, in, from, to), to.currency());
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned: each leg keeps its own.
            }
            case Change.Cleared<String> ignored -> {
                out.annotate(null);
                in.annotate(null);
            }
            case Change.SetTo<String> written -> {
                out.annotate(written.value());
                in.annotate(written.value());
            }
        }

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

    /**
     * "These two rows are one transfer" (docs/features/file-import.md rule 12, decision 7).
     *
     * <p>Turns two existing transactions — imported or typed — into a linked pair. <b>Matching is
     * manual</b>: nothing here suggests, ranks or looks for a partner; the person named both rows.
     * Every rule is checked before anything is written, and a refusal writes nothing. The rules are
     * the pair invariants of ADR-0033 and this class; pairing adds none of its own.
     *
     * <p>What changes: both rows get one new {@code transfer_group_id}; category and payee are
     * cleared on both, because a transfer has neither ({@code ck_transactions_transfer_has_no_*});
     * both versions move forward. <b>Nothing else</b>: account, amount, date, note, status, {@code
     * external_id} and {@code import_batch_id} stay as they were — so a later overlapping statement
     * still sees each row as an exact duplicate, and each leg keeps the date its own bank gave it.
     *
     * @throws ForbiddenException {@code read-only-role}, checked before either row is looked up
     * @throws NotFoundException for either row not in this household
     * @throws ConflictException {@code transfer-leg-not-separate}, {@code stale-version}, {@code
     *     account-archived}
     * @throws InvalidRequestException {@code transfer-pair-mismatch} with {@code params.reason}
     */
    @Transactional
    public TransferView pair(UUID actorId, UUID transactionId, PairRequest request) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        UUID householdId = household.id();

        // Both rows locked FOR UPDATE, in id order so two pairings of the same rows the other way
        // round cannot deadlock. EnvelopeService takes the same lock before filing a row, so the
        // envelope check below cannot be raced (slice-12 security re-audit).
        requirePresent(request.withTransactionId());
        boolean otherFirst = request.withTransactionId().compareTo(transactionId) < 0;
        Transaction other =
                otherFirst ? requireRow(request.withTransactionId(), householdId) : null;
        Transaction one = requireRow(transactionId, householdId);
        if (!otherFirst) {
            other = requireRow(request.withTransactionId(), householdId);
        }

        if (one.isTransfer() || other.isTransfer()) {
            throw new ConflictException(
                    ErrorCode.TRANSFER_LEG_NOT_SEPARATE,
                    "one of these rows is already half of a transfer");
        }
        if (one.accountId().equals(other.accountId())) {
            // Also the answer for a row paired with itself.
            throw mismatch("same-account");
        }
        if (one.amount().signum() == other.amount().signum()) {
            throw mismatch("same-sign");
        }
        Account first = account(one.accountId(), householdId);
        Account second = account(other.accountId(), householdId);
        boolean sameCurrency = first.currency().equals(second.currency());
        if (sameCurrency && one.amount().abs().compareTo(other.amount().abs()) != 0) {
            // Within one currency a transfer is equal and opposite. A fee taken on the way is its
            // own transaction, and the person edits one row first.
            throw mismatch("amounts-differ");
        }
        // A TRANSFER IS NOT SPENDING (EnvelopeService refuses to file a transfer leg). Pairing a
        // row already filed in an envelope would turn it into a leg after the fact, and the
        // envelope would go on counting money that never left the household — then outlive the
        // transfer as a phantom entry if the pair were deleted. Slice-12 security audit, medium 3.
        for (Transaction leg : List.of(one, other)) {
            if (envelopeEntries.findByTransaction(householdId, leg.id()).isPresent()) {
                throw new ConflictException(
                        ErrorCode.TRANSFER_LEG_IN_ENVELOPE,
                        "one of these rows is logged against an envelope");
            }
        }
        if (one.version() != request.version() || other.version() != request.withVersion()) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "one of these rows changed since it was read");
        }
        requireOpen(first);
        requireOpen(second);

        UUID groupId = UUID.randomUUID();
        for (Transaction leg : List.of(one, other)) {
            leg.linkToTransfer(groupId);
            leg.fileUnder(null);
            leg.payTo(null);
        }
        // Flushed so the response carries the versions the database now holds, and so a second
        // pairing racing for the same row fails here, at its UPDATE ... WHERE version = ?, rather
        // than after this method has answered (docs/memory/gotchas.md). The pair trigger is
        // deferred to COMMIT and is the backstop if anything above were wrong.
        transactions.saveAndFlush(one);
        transactions.saveAndFlush(other);

        log.info(
                "transfer paired householdId={} transferGroupId={} transactionId={}"
                        + " withTransactionId={} byUserId={}",
                householdId,
                groupId,
                one.id(),
                other.id(),
                actorId);
        Transaction out = one.amount().signum() < 0 ? one : other;
        Transaction in = out == one ? other : one;
        return view(household, List.of(out, in));
    }

    /**
     * Undoes a pairing — or any transfer, since nothing records how a pair was made: turns it back
     * into two ordinary rows (rule 12, "Undo is a split").
     *
     * <p>{@code transfer_group_id} is cleared on both and their versions move forward; nothing else
     * changes. The rows come back with <b>no category and no payee</b>, because pairing cleared
     * them and nothing kept the old values, so they land in "Needs a category". Allowed when an
     * account has since been archived, as editing or deleting a transfer is.
     *
     * @param version the pair's, as {@code GET /api/transfers/{id}} reports it
     * @return both rows, in the ledger's own shape, the leaving one first
     */
    @Transactional
    public List<TransactionView> split(UUID actorId, UUID transferGroupId, long version) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        List<Transaction> legs = requireLegs(transferGroupId, householdId);
        requireCurrentVersion(pairVersion(legs), version);

        for (Transaction leg : legs) {
            leg.linkToTransfer(null);
        }
        List<Transaction> saved =
                List.of(
                        transactions.saveAndFlush(legs.get(0)),
                        transactions.saveAndFlush(legs.get(1)));
        log.info(
                "transfer split householdId={} transferGroupId={} byUserId={}",
                householdId,
                transferGroupId,
                actorId);
        return ledger.rowsOf(householdId, saved);
    }

    /** The two rows to pair, each at the version the caller read. */
    public record PairRequest(UUID withTransactionId, long version, long withVersion) {}

    // ------------------------------------------------------------------------------- internals

    private static void requirePresent(UUID transactionId) {
        if (transactionId == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a transaction is required",
                    Map.of("field", "withTransactionId"));
        }
    }

    /** Locked {@code FOR UPDATE}; see {@code TransactionRepository}. */
    private Transaction requireRow(UUID transactionId, UUID householdId) {
        requirePresent(transactionId);
        return transactions
                .findByIdAndHouseholdIdForUpdate(transactionId, householdId)
                .orElseThrow(() -> new NotFoundException("no such transaction"));
    }

    private static InvalidRequestException mismatch(String reason) {
        return new InvalidRequestException(
                ErrorCode.TRANSFER_PAIR_MISMATCH,
                "these two rows cannot be one transfer",
                Map.of("reason", reason));
    }

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
                in.date(),
                sent,
                received,
                crossCurrency,
                crossCurrency ? impliedRate(sent, received) : null,
                out.note().orElse(null),
                in.note().orElse(null),
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
