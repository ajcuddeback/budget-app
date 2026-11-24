package com.budgetowl.debt.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.bill.domain.BillOccurrence;
import com.budgetowl.bill.persistence.BillOccurrenceRepository;
import com.budgetowl.bill.persistence.BillRepository;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.transaction.persistence.TransactionRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The four ids a debt or a payment may name, each resolved through the household that owns it.
 *
 * <p>One class, because they are one control. The threat model's first two live attacks are a
 * foreign {@code accountId} on {@code POST /api/debts} and a foreign {@code transactionId} on
 * {@code POST /api/debts/{id}/payments} — both of which leak a row's existence, and one its
 * currency, through the caller's own screen. The answer in every case is the same: <b>an id in a
 * request body is a lookup key and never evidence of ownership</b>, so it is resolved by a
 * membership-scoped query before anything is written, and another household's is a {@code 404}.
 *
 * <p>The composite foreign keys on {@code (id, household_id)} make the row physically unwritable
 * otherwise (ADR-0017). That is the layer a future caller cannot forget; this is the layer that
 * produces an answer a person can read instead of a constraint violation.
 *
 * <p>Nothing here is {@code @Transactional}: every method is called from inside a service method
 * that already owns a transaction, exactly as {@code MembershipService} is.
 */
@Service
public class DebtLinks {

    private final AccountRepository accounts;
    private final BillRepository bills;
    private final TransactionRepository transactions;
    private final BillOccurrenceRepository occurrences;

    public DebtLinks(
            AccountRepository accounts,
            BillRepository bills,
            TransactionRepository transactions,
            BillOccurrenceRepository occurrences) {
        this.accounts = accounts;
        this.bills = bills;
        this.transactions = transactions;
        this.occurrences = occurrences;
    }

    /**
     * The account a debt is held at.
     *
     * <p>Returned rather than merely checked, because the debt's currency <b>is</b> the account's
     * and is not independently settable — the account owns that fact, exactly as it does for a
     * transaction. {@code account_currency_immutable} (slice 3) is what keeps it true afterwards.
     *
     * <p>An <b>archived</b> account is accepted: a retired container is not a settled debt, and the
     * debt is deliberately untouched when one is archived underneath it. Nothing is written to the
     * account either way — a debt payment is not a ledger row (ADR-0035).
     */
    public Account requireAccount(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    /**
     * The bill whose occurrence pays a debt's minimum.
     *
     * <p>Named by the household and <b>never inferred</b> from a name, an amount or a category:
     * matching them would be guessing, which is precisely what bills refuse to do with envelopes.
     * It is also what keeps a minimum from being counted in both places.
     */
    public UUID requireBill(UUID billId, UUID householdId) {
        return bills.findByIdAndHouseholdId(billId, householdId)
                .orElseThrow(() -> new NotFoundException("no such bill"))
                .id();
    }

    /**
     * The real movement a payment points at.
     *
     * <p>Resolved by the same path a plain transaction read uses, and <b>never taken on trust
     * because the debt happens to reference the account</b>: that is the shape of the second live
     * attack in the feature doc's threat model.
     */
    public UUID requireTransaction(UUID transactionId, UUID householdId) {
        return transactions
                .findByIdAndHouseholdId(transactionId, householdId)
                .orElseThrow(() -> new NotFoundException("no such transaction"))
                .id();
    }

    /**
     * Which of this household's bills are live.
     *
     * <p>Not a resolution like the four above, but the same question asked of the same table: the
     * period summary leaves a debt's minimum out of its subtraction when a bill already carries it,
     * and a <b>retired</b> bill carries nothing. Asking here keeps that answer coming from the
     * bills table rather than from an assumption held in the debt code.
     */
    public Set<UUID> activeBillIds(UUID householdId) {
        return bills.findActiveIdsForHousehold(householdId);
    }

    /** The bill occurrence a payment was recorded from. Same resolution, same reason. */
    public BillOccurrence requireOccurrence(UUID occurrenceId, UUID householdId) {
        return occurrences
                .findByIdAndHouseholdId(occurrenceId, householdId)
                .orElseThrow(() -> new NotFoundException("no such bill occurrence"));
    }
}
