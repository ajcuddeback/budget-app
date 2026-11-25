package com.budgetowl.goal.service;

import com.budgetowl.common.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * What {@link GoalService} returns. Read models, never entities.
 *
 * <p>Every {@code toString} is redacted: a goal's name is the most disclosing field this feature
 * has, and a contribution history is a month-by-month record of disposable income.
 */
public final class GoalViews {

    private GoalViews() {}

    /**
     * One goal with every derived figure.
     *
     * @param targetMonth null for a goal with no month
     * @param accountId null for a goal kept nowhere in particular
     */
    public record GoalView(
            UUID id,
            String name,
            Currency currency,
            Money targetAmount,
            YearMonth targetMonth,
            Money startingAmount,
            UUID accountId,
            boolean archived,
            GoalFigures figures,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        @Override
        public String toString() {
            return "GoalView[id=" + id + ", version=" + version + "]";
        }
    }

    /** A goal before it exists: the add form's preview. */
    public record DraftView(Currency currency, GoalFigures figures) {

        @Override
        public String toString() {
            return "DraftView[currency=" + currency + "]";
        }
    }

    /**
     * One recorded contribution.
     *
     * @param amount signed: negative is a withdrawal
     * @param period the month it counts towards
     */
    public record ContributionView(
            UUID id,
            UUID goalId,
            LocalDate date,
            YearMonth period,
            Money amount,
            String note,
            UUID transactionId,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        @Override
        public String toString() {
            return "ContributionView[id=" + id + ", goalId=" + goalId + "]";
        }
    }

    /** One page of anything, in the envelope {@code docs/guides/api-style.md} describes. */
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        @Override
        public String toString() {
            return "Page[page=" + page + ", totalElements=" + totalElements + "]";
        }
    }

    /**
     * What a month's goals take out of the left-over figure: {@code Σ monthContribution(g, P)} over
     * the unarchived goals in the household's base currency.
     *
     * <p>The one object the period summary both reports and subtracts, so the term printed on the
     * page and the term taken off {@code left} cannot be two different readings.
     *
     * @param goalsCounted how many goals the figure was summed over
     */
    public record SetAside(Money total, int goalsCounted) {

        @Override
        public String toString() {
            return "SetAside[goalsCounted=" + goalsCounted + "]";
        }
    }
}
