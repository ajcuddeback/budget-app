package com.budgetowl.account.service;

import com.budgetowl.account.domain.AccountType;
import com.budgetowl.common.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One account as the service reports it. The entity never leaves the service layer
 * (docs/guides/api-style.md) — not as a response body and not as something a controller maps,
 * because an entity in a controller signature is an entity one step from being a response body.
 *
 * @param lastFour null when the account has no number, which is ordinary rather than unknown
 *     (ADR-0028)
 * @param balance <b>derived</b>: {@link #openingBalance} plus the account's transactions. Never
 *     read from a column, and never assumed by a client to equal {@link #openingBalance} — the two
 *     are equal only until slice 5, and a client that conflates them breaks silently then.
 * @param empty whether this account has no transactions at all. Not "its balance is zero": an
 *     account with +50 and -50 has history and may not be deleted. It is the client's only way to
 *     know whether Delete is offered or the currency is locked, which is why it is on the response
 *     rather than left to a 409 the user discovers by trying.
 * @param version what the caller must send back on a {@code PATCH} to win the optimistic lock
 */
public record AccountView(
        UUID id,
        String name,
        String lastFour,
        AccountType type,
        Money openingBalance,
        Money balance,
        boolean empty,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /**
     * Redacted. {@code lastFour} is a partial financial identifier (ADR-0028), the name is whatever
     * the user typed, and the amounts are the household's money — none of the three belongs in a
     * log line, and a record's generated {@code toString} would put all of them there the first
     * time somebody logged a view at DEBUG.
     */
    @Override
    public String toString() {
        return "AccountView[id=" + id + ", type=" + type + ", archived=" + archived + "]";
    }
}
