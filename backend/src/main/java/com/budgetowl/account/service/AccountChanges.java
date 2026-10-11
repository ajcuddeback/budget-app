package com.budgetowl.account.service;

/**
 * A partial update to an account. {@code null} means <b>unchanged</b> for every field here except
 * the digits, which carry their own three-way answer (see {@link LastFourChange}).
 *
 * <p>Why null can mean unchanged for the other four: a valid account always has a name, a type, a
 * currency and an opening balance, so "set this to nothing" is not a request any of them can
 * express and {@code null} is free to mean something else. The digits are the one field whose
 * absence and whose removal are both legitimate.
 *
 * @param version the version the caller read. Required, and the whole point of optimistic locking:
 *     without it the second of two concurrent editors silently overwrites the first instead of
 *     being told (docs/features/accounts.md, {@code 409 stale-version}).
 */
public record AccountChanges(
        String name,
        LastFourChange lastFour,
        String type,
        String currency,
        String openingBalance,
        long version) {

    /** Redacted, for the reasons on {@link NewAccount#toString()}. */
    @Override
    public String toString() {
        return "AccountChanges[version=" + version + "]";
    }
}
