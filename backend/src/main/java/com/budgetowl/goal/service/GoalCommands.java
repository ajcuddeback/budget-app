package com.budgetowl.goal.service;

import com.budgetowl.common.Change;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * What the web layer hands {@link GoalService}: plain values, amounts still as the strings the
 * client sent, so that parsing and the scale check against the goal's currency happen in one place.
 *
 * <p>Every {@code toString} is redacted. These carry names, amounts and notes, and a record prints
 * every component.
 */
public final class GoalCommands {

    private GoalCommands() {}

    /**
     * A goal to create.
     *
     * @param currency only without {@code accountId}: when an account is named, it owns the
     *     currency
     * @param monthlyContribution creates the plan's first step at the current period
     */
    public record NewGoal(
            String name,
            String targetAmount,
            YearMonth targetMonth,
            String startingAmount,
            String monthlyContribution,
            String currency,
            UUID accountId) {

        @Override
        public String toString() {
            return "NewGoal[targetMonth=" + targetMonth + "]";
        }
    }

    /**
     * A partial update. A {@code null} string is "not mentioned"; the two {@link Change} fields
     * also distinguish "clear it".
     */
    public record GoalChanges(
            String name,
            String targetAmount,
            Change<YearMonth> targetMonth,
            String startingAmount,
            Change<UUID> accountId,
            long version) {

        @Override
        public String toString() {
            return "GoalChanges[version=" + version + "]";
        }
    }

    /** A contribution to record. Signed: negative is a withdrawal. */
    public record NewContribution(String amount, LocalDate date, String note, UUID transactionId) {

        @Override
        public String toString() {
            return "NewContribution[date=" + date + ", linked=" + (transactionId != null) + "]";
        }
    }

    /** A correction to a contribution. */
    public record ContributionChanges(
            String amount,
            LocalDate date,
            Change<String> note,
            Change<UUID> transactionId,
            long version) {

        @Override
        public String toString() {
            return "ContributionChanges[version=" + version + "]";
        }
    }

    /**
     * What the goal-bound preview substitutes before projecting. Each absent field keeps the goal's
     * own value, so the edit dialog and the plan dialog share one preview.
     *
     * @param monthlyAmount the plan from the current period on, or null to keep the plan
     */
    public record GoalPreview(
            String monthlyAmount,
            String targetAmount,
            String startingAmount,
            Change<YearMonth> targetMonth) {

        @Override
        public String toString() {
            return "GoalPreview[]";
        }
    }

    /** A goal that does not exist yet, for the add form's preview. Reads no household data. */
    public record DraftGoal(
            String currency,
            String targetAmount,
            String startingAmount,
            YearMonth targetMonth,
            String monthlyAmount) {

        @Override
        public String toString() {
            return "DraftGoal[currency=" + currency + ", targetMonth=" + targetMonth + "]";
        }
    }
}
