package com.budgetowl.account.service;

import com.budgetowl.common.Money;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the transactions recorded against an account add up to, and whether there are any.
 *
 * <p><b>This was the seam slice 5 filled in, and it worked: slice 5 added a term rather than
 * rewriting a call site.</b> {@code PostedTransactions} now answers from the {@code transactions}
 * table and nothing in {@link AccountService} changed. Three rules in docs/features/accounts.md
 * depend on it:
 *
 * <ul>
 *   <li>balance is {@code openingBalance} plus the sum of the account's transactions,
 *   <li>currency is immutable once the account has any transaction ({@code 409}),
 *   <li>deletion is permitted only while the account has none ({@code 409}).
 * </ul>
 *
 * <p>Implementing those against a hard-coded zero would have meant three call sites to find and
 * change later, and the feature doc is explicit that a client must never assume {@code balance}
 * equals {@code openingBalance} — an assumption that breaks silently the moment transactions land.
 * So the service asks this interface. Until slice 5 the implementation honestly answered "none" for
 * a schema with no transactions table; the arithmetic and the two refusals were written, reachable
 * and tested before there was anything to sum.
 *
 * <p><b>Totals are fetched in a batch</b>, not one account at a time. A list endpoint that asked
 * per row would be an N+1 the day slice 5 makes these real queries, and the shape of the call is
 * the part that is expensive to change later.
 *
 * <p>Every method takes the {@code householdId} the caller's membership resolved to, for the same
 * reason every repository method does (ADR-0017): an account id is a lookup key and never evidence
 * of ownership, and that has to stay true through the seam as well as in front of it.
 */
public interface AccountTransactions {

    /**
     * The posted total per account, in each account's own currency.
     *
     * <p>An account with no transactions may be absent from the map or present with a zero — a
     * caller must treat the two the same, which is why {@link AccountService} defaults a missing
     * entry to zero in the account's currency rather than asking for a complete map.
     *
     * @param accountIds the accounts to total; all of them must belong to {@code householdId}
     */
    Map<UUID, Money> totalsByAccount(UUID householdId, Collection<UUID> accountIds);

    /**
     * Whether this account has any transaction at all.
     *
     * <p>Deliberately not derived from {@link #totalsByAccount}: a total of zero is not the same
     * fact as no transactions, and a credit of 50 against a debit of 50 is an account with history
     * whose currency must not be reinterpreted and whose rows must not be deleted.
     */
    boolean existForAccount(UUID householdId, UUID accountId);

    /**
     * Which of these accounts have any transaction at all, batched.
     *
     * <p>Separate from {@link #totalsByAccount} because a total of zero is not an absence: an
     * account with +50 and -50 has history and must not be deletable. Separate from {@link
     * #existForAccount} because the list endpoint asks about every row at once, and asking one at a
     * time is an N+1 the day this stops returning an empty set.
     *
     * @return the subset with history; never null, and never containing an id that was not asked
     *     about
     */
    Set<UUID> withHistory(UUID householdId, Collection<UUID> accountIds);
}
