package com.budgetowl.account.web;

import com.budgetowl.account.domain.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response bodies for the account endpoints. The entity never appears in one. */
public final class AccountResponses {

    private AccountResponses() {}

    /**
     * One account.
     *
     * <p><b>{@code name} and {@code lastFour} are returned separately and never joined.</b> The
     * client composes {@code name •1234}; the server does not, because a pre-joined label cannot be
     * re-ordered for a right-to-left locale (ADR-0023, ADR-0028).
     *
     * <p><b>{@code balance} is derived and is not the same field as {@code openingBalance}.</b> It
     * is {@code openingBalance} plus the sum of the account's transactions, computed on the server.
     * The two are equal only because there are no transactions yet: a client must never compute a
     * balance itself and must never assume the two fields are equal, because that assumption breaks
     * silently the moment slice 5 lands.
     *
     * @param openingBalance and {@code balance}: strings on the wire, never JSON numbers — a double
     *     cannot hold every {@code NUMERIC(19,4)} and the one that gets rounded will be somebody's
     *     money (ADR-0006). {@code MoneyJsonModule} is what makes that structural rather than
     *     remembered.
     * @param currency the account's own, never a converted one. Conversion is display-only and
     *     never leaves the client's read path (ADR-0022), and balances are never summed across
     *     currencies.
     * @param version what to send back on a {@code PATCH}
     */
    public record AccountResponse(
            UUID id,
            String name,
            String lastFour,
            AccountType type,
            String currency,
            BigDecimal openingBalance,
            BigDecimal balance,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /**
         * Redacted. A record's generated {@code toString} would put a partial financial identifier
         * (ADR-0028), the household's account names and its balances into any log line that touched
         * a response — and Spring MVC logs the returned value at {@code DEBUG}.
         */
        @Override
        public String toString() {
            return "AccountResponse[id=" + id + ", type=" + type + ", archived=" + archived + "]";
        }
    }
}
