package com.budgetowl.account.web;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.service.LastFourChange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the account endpoints. Entities are never request bodies
 * (docs/guides/api-style.md) — binding one is how mass assignment happens, and an {@code Account}
 * has a {@code householdId}.
 *
 * <p><b>No household id and no user id anywhere below.</b> Both come from the authenticated
 * caller's verified membership (ADR-0017, non-negotiable #2). A field for either would be a field
 * an attacker could set, and the check one level up would then be checking the attacker's own
 * answer.
 *
 * <p><b>Amounts are strings</b> (ADR-0006) and are shape-checked here rather than parsed by
 * Jackson, because Bean Validation is what produces a {@code validation-failed} naming the field
 * while a deserializer failure can only be a {@code malformed-request} naming nothing. {@code
 * MoneyJsonModule} refuses a JSON number for any amount that is not declared this way.
 *
 * <p>Every {@code toString} below is redacted. Spring MVC logs the deserialized request body at
 * {@code DEBUG} ("Read ... to [CreateAccountRequest...]"), and a record's generated {@code
 * toString} prints every component — which here means a partial financial identifier (ADR-0028),
 * whatever the user typed as a name, and somebody's money. That is exactly the shape of the bug
 * slice 2's credential-disclosure test caught.
 */
public final class AccountRequests {

    /**
     * Exactly four ASCII digits (ADR-0028). No anchors: Bean Validation matches the <em>whole</em>
     * value, and a written-out {@code $} in Java also matches before a trailing newline — so {@code
     * "1234\n"} would read as valid to a human checking the pattern.
     */
    private static final String LAST_FOUR = "[0-9]{4}";

    /** ISO-4217 shape. Which codes exist is checked in the service against the JVM's table. */
    private static final String CURRENCY = "[A-Za-z]{3}";

    /**
     * A signed decimal that fits {@code NUMERIC(19,4)}: at most 15 integer digits and 4 decimal
     * places. {@code [0-9]} rather than {@code \d} so no locale can widen it to Arabic-Indic
     * digits, which {@code BigDecimal} would then refuse several frames later.
     */
    private static final String AMOUNT = "-?[0-9]{1,15}(\\.[0-9]{1,4})?";

    private static final String AMOUNT_MESSAGE =
            "must be a signed decimal amount with at most 15 integer digits and 4 decimal places";

    /** At least one non-whitespace character. {@code DOTALL} so a newline cannot slip past. */
    private static final String NOT_BLANK = ".*\\S.*";

    private AccountRequests() {}

    /**
     * A new account.
     *
     * @param lastFour optional — an account with no number is entirely ordinary, because cash in a
     *     tin has no number
     * @param openingBalance signed. Negative is normal for a credit card and must not be warned
     *     about; zero is valid and common and is never "unset"
     */
    public record CreateAccountRequest(
            @NotBlank @Size(max = Account.NAME_MAX_LENGTH) String name,
            @Pattern(regexp = LAST_FOUR) String lastFour,
            @NotBlank String type,
            @NotBlank @Pattern(regexp = CURRENCY) String currency,
            @NotBlank @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String openingBalance) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateAccountRequest[redacted]";
        }
    }

    /**
     * A partial update. <b>An absent field is unchanged</b>, which is what {@code PATCH} means.
     *
     * <p><b>Why this one is a mutable class and not a record.</b> docs/guides/java-style.md asks
     * for records for DTOs, and every other request body in this codebase is one. This body has to
     * tell the service something a record cannot carry: whether {@code lastFour} was
     * <em>mentioned</em>. An absent property and an explicit {@code null} both arrive as {@code
     * null} in a record component — and as {@code Optional.empty()} with Jackson's {@code Optional}
     * support, which was verified rather than assumed — so a record forces absent and cleared to be
     * the same request. They are not: collapsing them means a client that patches only the name
     * silently erases the digits that tell two cards at one bank apart (ADR-0028). A setter is
     * called only for a property that is present, which is the one hook that distinguishes the two,
     * and it keeps the {@code @Pattern} on the field where a container wrapper would have needed a
     * custom Bean Validation value extractor to validate anything at all.
     *
     * @see LastFourChange
     */
    public static final class UpdateAccountRequest {

        @Size(max = Account.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        @Pattern(regexp = LAST_FOUR)
        private String lastFour;

        private boolean lastFourMentioned;

        private String type;

        @Pattern(regexp = CURRENCY)
        private String currency;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String openingBalance;

        /**
         * Required, and not an inconvenience: optimistic locking only works if the caller says
         * which version it read. Without it the second of two people editing one account silently
         * overwrites the first instead of being told ({@code 409 stale-version}).
         */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setLastFour(String lastFour) {
            this.lastFour = lastFour;
            this.lastFourMentioned = true;
        }

        public void setType(String type) {
            this.type = type;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public void setOpeningBalance(String openingBalance) {
            this.openingBalance = openingBalance;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public String currency() {
            return currency;
        }

        public String openingBalance() {
            return openingBalance;
        }

        public Long version() {
            return version;
        }

        /**
         * The three-way answer, resolved here because this is the only object that holds the
         * presence bit. Exposing the bit instead would invite a call site that forgets it.
         */
        public LastFourChange lastFourChange() {
            if (!lastFourMentioned) {
                return LastFourChange.unchanged();
            }
            return lastFour == null ? LastFourChange.cleared() : LastFourChange.setTo(lastFour);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateAccountRequest[version=" + version + "]";
        }
    }
}
