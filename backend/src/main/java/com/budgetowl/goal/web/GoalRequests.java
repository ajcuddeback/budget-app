package com.budgetowl.goal.web;

import com.budgetowl.common.Change;
import com.budgetowl.goal.domain.Goal;
import com.budgetowl.goal.domain.GoalContribution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Request bodies for the goal endpoints. <b>Entities are never request bodies.</b>
 *
 * <p><b>Amounts are strings</b> (ADR-0006), shape-checked here so a malformed one is a {@code
 * validation-failed} naming the field. A JSON <em>number</em> is refused outright by {@code
 * CoreConfig}'s coercion config. The scale is checked again against the goal's currency in the
 * service: a yen goal takes no decimals at all.
 *
 * <p><b>No household id, no user, and no contribution currency</b> anywhere. The first two come
 * from the caller's verified membership (ADR-0017); a contribution is in its goal's currency.
 * Unknown properties are refused, so sending any of them is a {@code 400} rather than a field
 * quietly ignored.
 *
 * <p>Every {@code toString} is redacted. Spring MVC logs the deserialized body at {@code DEBUG},
 * and these carry a goal's name — the most disclosing field this feature has — amounts and notes.
 */
public final class GoalRequests {

    /** Unsigned, at most four decimal places — the scale {@code NUMERIC(19,4)} holds. */
    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE = "must be a decimal with at most 4 decimal places";

    /** Signed: a negative contribution is a withdrawal (decision 6). Zero is refused in service. */
    private static final String SIGNED_AMOUNT = "^-?\\d{1,15}(\\.\\d{1,4})?$";

    private static final String SIGNED_AMOUNT_MESSAGE =
            "must be a signed decimal with at most 4 decimal places";

    private static final String CURRENCY = "^[A-Za-z]{3}$";

    /**
     * At least one non-whitespace character, Unicode-aware. See {@code DebtRequests.NOT_BLANK}:
     * without it {@code " "}, or an ideographic space {@code @NotBlank} does not recognise, reaches
     * the entity, strips to empty and is a {@code 500}. Slices 3 and 10 both shipped that once.
     */
    private static final String NOT_BLANK = "(?U).*\\S.*";

    private GoalRequests() {}

    /**
     * A goal to create. {@code name} and {@code targetAmount} are required.
     *
     * @param currency refused alongside {@code accountId}: the account owns that fact
     * @param monthlyContribution creates the plan's first step at the current period
     */
    public record CreateGoalRequest(
            @NotBlank
                    @Size(max = Goal.NAME_MAX_LENGTH)
                    @Pattern(
                            regexp = NOT_BLANK,
                            flags = Pattern.Flag.DOTALL,
                            message = "must not be blank")
                    String name,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String targetAmount,
            YearMonth targetMonth,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String startingAmount,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String monthlyContribution,
            @Pattern(regexp = CURRENCY, message = "must be a three-letter currency code")
                    String currency,
            UUID accountId) {

        @Override
        public String toString() {
            return "CreateGoalRequest[targetMonth=" + targetMonth + "]";
        }
    }

    /**
     * A partial update.
     *
     * <p>A class with setters rather than a record, because <b>an absent field and an explicit null
     * are different instructions</b> for {@code targetMonth} and {@code accountId}: absent leaves
     * it alone, null clears it. Jackson calls a setter only when the property is present, and that
     * is the whole mechanism. There is no {@code currency}.
     */
    public static final class UpdateGoalRequest {

        @Size(max = Goal.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String targetAmount;

        private YearMonth targetMonth;

        private boolean targetMonthMentioned;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String startingAmount;

        private UUID accountId;

        private boolean accountMentioned;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        public void setTargetAmount(String targetAmount) {
            this.targetAmount = targetAmount;
        }

        public void setTargetMonth(YearMonth targetMonth) {
            this.targetMonth = targetMonth;
            this.targetMonthMentioned = true;
        }

        public void setStartingAmount(String startingAmount) {
            this.startingAmount = startingAmount;
        }

        public void setAccountId(UUID accountId) {
            this.accountId = accountId;
            this.accountMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String targetAmount() {
            return targetAmount;
        }

        public String startingAmount() {
            return startingAmount;
        }

        public Long version() {
            return version;
        }

        public Change<YearMonth> targetMonthChange() {
            return Change.of(targetMonth, targetMonthMentioned);
        }

        public Change<UUID> accountIdChange() {
            return Change.of(accountId, accountMentioned);
        }

        @Override
        public String toString() {
            return "UpdateGoalRequest[version=" + version + "]";
        }
    }

    /** From the month in the path on, the plan is this amount. Guarded by the goal's version. */
    public record SetPlanRequest(
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String monthlyAmount,
            @NotNull Long version) {

        @Override
        public String toString() {
            return "SetPlanRequest[version=" + version + "]";
        }
    }

    /**
     * Money set aside, or — negative — taken out.
     *
     * @param transactionId the real movement, usually one leg of a transfer into savings. Evidence
     *     only: nothing is written to the ledger either way
     */
    public record CreateContributionRequest(
            @NotNull @Pattern(regexp = SIGNED_AMOUNT, message = SIGNED_AMOUNT_MESSAGE)
                    String amount,
            @NotNull LocalDate date,
            @Size(max = GoalContribution.NOTE_MAX_LENGTH) String note,
            UUID transactionId) {

        @Override
        public String toString() {
            return "CreateContributionRequest[date="
                    + date
                    + ", linked="
                    + (transactionId != null)
                    + "]";
        }
    }

    /** A correction. Three-way on {@code note} and {@code transactionId}. */
    public static final class UpdateContributionRequest {

        @Pattern(regexp = SIGNED_AMOUNT, message = SIGNED_AMOUNT_MESSAGE)
        private String amount;

        private LocalDate date;

        @Size(max = GoalContribution.NOTE_MAX_LENGTH)
        private String note;

        private boolean noteMentioned;

        private UUID transactionId;

        private boolean transactionMentioned;

        @NotNull private Long version;

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public void setNote(String note) {
            this.note = note;
            this.noteMentioned = true;
        }

        public void setTransactionId(UUID transactionId) {
            this.transactionId = transactionId;
            this.transactionMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String amount() {
            return amount;
        }

        public LocalDate date() {
            return date;
        }

        public Long version() {
            return version;
        }

        /** A blank note is cleared rather than stored: the CHECK refuses an empty one. */
        public Change<String> noteChange() {
            if (!noteMentioned) {
                return Change.unchanged();
            }
            return note == null || note.isBlank() ? Change.cleared() : Change.setTo(note);
        }

        public Change<UUID> transactionIdChange() {
            return Change.of(transactionId, transactionMentioned);
        }

        @Override
        public String toString() {
            return "UpdateContributionRequest[version=" + version + "]";
        }
    }

    /**
     * What the goal-bound preview substitutes. Every field is optional and an absent one keeps the
     * goal's own value; {@code targetMonth} is three-way, as on {@code PATCH}.
     */
    public static final class GoalPreviewRequest {

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String monthlyAmount;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String targetAmount;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String startingAmount;

        private YearMonth targetMonth;

        private boolean targetMonthMentioned;

        public void setMonthlyAmount(String monthlyAmount) {
            this.monthlyAmount = monthlyAmount;
        }

        public void setTargetAmount(String targetAmount) {
            this.targetAmount = targetAmount;
        }

        public void setStartingAmount(String startingAmount) {
            this.startingAmount = startingAmount;
        }

        public void setTargetMonth(YearMonth targetMonth) {
            this.targetMonth = targetMonth;
            this.targetMonthMentioned = true;
        }

        public String monthlyAmount() {
            return monthlyAmount;
        }

        public String targetAmount() {
            return targetAmount;
        }

        public String startingAmount() {
            return startingAmount;
        }

        public Change<YearMonth> targetMonthChange() {
            return Change.of(targetMonth, targetMonthMentioned);
        }

        @Override
        public String toString() {
            return "GoalPreviewRequest[]";
        }
    }

    /** The add form's preview, before a goal exists. Pure arithmetic over this body. */
    public record DraftPreviewRequest(
            @Pattern(regexp = CURRENCY, message = "must be a three-letter currency code")
                    String currency,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String targetAmount,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String startingAmount,
            YearMonth targetMonth,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String monthlyAmount) {

        @Override
        public String toString() {
            return "DraftPreviewRequest[currency=" + currency + "]";
        }
    }
}
