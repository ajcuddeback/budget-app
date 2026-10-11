package com.budgetowl.account.service;

import com.budgetowl.common.Money;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The state of the world before slice 5: there is no transactions table, so no account has a
 * transaction.
 *
 * <p><b>Not a stub, and not a mock.</b> It is the correct implementation of {@link
 * AccountTransactions} for a schema in which the only migration touching money is {@code
 * V9__accounts.sql} — every answer it gives is true. Slice 5 replaces this bean with one that
 * queries the table; nothing in {@link AccountService} changes when it does, and the two {@code
 * 409}s and the balance arithmetic that depend on it are already written and already tested.
 *
 * <p>It returns an empty map rather than a map of zeroes on purpose: "I have nothing to add" is the
 * honest answer, and it is also the answer the real implementation will give for an account with no
 * rows, so the caller's handling of a missing entry is exercised now rather than first in slice 5.
 */
@Service
public class NoTransactionsRecorded implements AccountTransactions {

    @Override
    public Map<UUID, Money> totalsByAccount(UUID householdId, Collection<UUID> accountIds) {
        return Map.of();
    }

    @Override
    public boolean existForAccount(UUID householdId, UUID accountId) {
        return false;
    }
}
