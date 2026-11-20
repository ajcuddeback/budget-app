package com.budgetowl.transaction.service;

import com.budgetowl.account.service.AccountTransactions;
import com.budgetowl.common.Money;
import com.budgetowl.transaction.persistence.AccountTotal;
import com.budgetowl.transaction.persistence.TransactionRepository;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the recorded transactions add up to — the real implementation of the seam slice 3 left.
 *
 * <p><b>This bean replaces {@code NoTransactionsRecorded}, and nothing in {@code AccountService}
 * changed when it did.</b> That was the point of building the seam: the balance arithmetic, the
 * {@code 409 account-currency-immutable} and the {@code 409 account-not-empty} were written,
 * reachable and tested against an implementation that honestly answered "none", and they start
 * producing real answers here without a call site being edited.
 *
 * <p>An account with no transactions is <b>absent</b> from the map rather than present with a zero,
 * which is what {@code AccountService} already handles by defaulting to {@code Money.zero} in the
 * account's currency. Returning zeroes instead would mean inventing a currency for an account this
 * class was not asked about.
 */
@Service
public class PostedTransactions implements AccountTransactions {

    private final TransactionRepository transactions;

    public PostedTransactions(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Money> totalsByAccount(UUID householdId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            // An `in ()` is a syntax error in some dialects and an empty scan in others. Asking
            // about nothing has an obvious answer, so it is given here rather than in SQL.
            return Map.of();
        }

        Map<UUID, Money> totals = new HashMap<>();
        for (AccountTotal row : transactions.totalsByAccount(householdId, accountIds)) {
            totals.put(
                    row.accountId(), Money.of(row.total(), Currency.getInstance(row.currency())));
        }
        return totals;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existForAccount(UUID householdId, UUID accountId) {
        return transactions.existsForAccount(householdId, accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> withHistory(UUID householdId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(transactions.accountIdsWithHistory(householdId, accountIds));
    }
}
