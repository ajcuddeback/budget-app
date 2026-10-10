package com.budgetowl.account.domain;

/**
 * What kind of place the money sits in. A closed set: an unknown value is a {@code 400} at the edge
 * and is refused by {@code ck_accounts_type} in the database, so it cannot reach the table through
 * an import path or a CLI either.
 *
 * <p>Stored as text rather than a PostgreSQL enum (docs/features/accounts.md), so adding a sixth
 * type later is an ordinary migration instead of a type rewrite.
 *
 * <p>Nothing about stored money depends on which of these an account is — the type is always
 * mutable, and the sign of an amount is never inferred from it.
 */
public enum AccountType {
    /** A current account. Everyday money in and out. */
    CHECKING,
    /** A savings pot. */
    SAVINGS,
    /** Normally held at a negative balance: what is owed, not what is held. */
    CREDIT_CARD,
    /** Notes in a tin. No number, so no {@code lastFour}. */
    CASH,
    /** Treated as an ordinary account for now — see the feature doc's open question. */
    INVESTMENT
}
