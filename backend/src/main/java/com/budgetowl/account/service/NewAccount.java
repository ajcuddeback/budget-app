package com.budgetowl.account.service;

/**
 * An account the caller wants created.
 *
 * <p><b>No household id.</b> Not an omission: the household is resolved from the caller's verified
 * membership inside the service, and a field for it here would be a field an attacker could set
 * (ADR-0017, non-negotiable #2). The same goes for the acting user, which comes from the {@code
 * SecurityContext}.
 *
 * <p>{@code type}, {@code currency} and {@code openingBalance} arrive as the text the caller sent.
 * They are parsed in the service rather than at the edge so that an unknown currency or an
 * unrecognised type is a {@code validation-failed} naming the field, the same shape {@code
 * HouseholdService} uses for the household's base currency — a deserializer failure can only be a
 * {@code malformed-request} naming nothing.
 *
 * @param lastFour null for an account with no number
 */
public record NewAccount(
        String name, String lastFour, String type, String currency, String openingBalance) {

    /**
     * Redacted: {@code lastFour} is a partial financial identifier (ADR-0028), the name is whatever
     * the user typed, and the opening balance is somebody's money.
     */
    @Override
    public String toString() {
        return "NewAccount[redacted]";
    }
}
