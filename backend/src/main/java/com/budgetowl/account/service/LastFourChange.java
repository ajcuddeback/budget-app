package com.budgetowl.account.service;

/**
 * What a {@code PATCH} asks to happen to an account's last four digits.
 *
 * <p>Three outcomes, and they have to be three rather than two. {@code null} cannot carry the
 * difference between "the caller did not mention the digits" and "the caller asked for the digits
 * to be removed", and JSON offers no fourth state: an absent property and an explicit {@code null}
 * both arrive as {@code null} in a record component, and Jackson's {@code Optional} support maps
 * both to {@code Optional.empty()} (verified against Jackson 3, not assumed). A sealed type makes
 * the distinction explicit here, so the web layer has to decide which of the three it means and the
 * {@code switch} in {@link AccountService} cannot quietly grow a fourth unhandled case
 * (docs/guides/java-style.md).
 *
 * <p>Getting this wrong is not cosmetic: collapsing absent into cleared means a client that patches
 * only the name silently erases the digits, and the digits are what tell two cards at one bank
 * apart (ADR-0028).
 */
public sealed interface LastFourChange {

    /** The caller did not mention the digits. Whatever the account has, it keeps. */
    static LastFourChange unchanged() {
        return new Unchanged();
    }

    /** The caller asked for the account to have no digits — a mistyped number being undone. */
    static LastFourChange cleared() {
        return new Cleared();
    }

    /**
     * @param digits exactly four ASCII digits, shape-checked at the edge and re-checked by the
     *     entity
     */
    static LastFourChange setTo(String digits) {
        return new SetTo(digits);
    }

    record Unchanged() implements LastFourChange {}

    record Cleared() implements LastFourChange {}

    record SetTo(String digits) implements LastFourChange {

        /** Not in a log, not in an error message (ADR-0028). */
        @Override
        public String toString() {
            return "SetTo[redacted]";
        }
    }
}
