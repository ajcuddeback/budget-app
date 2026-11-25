package com.budgetowl.goal.web;

import com.budgetowl.common.Money;
import com.budgetowl.goal.service.GoalFigures;
import com.budgetowl.goal.service.GoalProjection;
import com.budgetowl.goal.service.GoalViews.ContributionView;
import com.budgetowl.goal.service.GoalViews.DraftView;
import com.budgetowl.goal.service.GoalViews.GoalView;
import com.budgetowl.goal.service.PlanStep;
import com.budgetowl.goal.service.ProjectionReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;

/**
 * Response bodies for the goal endpoints. Entities are never response bodies.
 *
 * <p>Amounts are strings at storage scale (ADR-0006) and every one travels beside its currency —
 * except {@code requiredMonthly}, which is at the currency's minor-unit scale because it is an
 * amount somebody will type. Months are {@code "2027-11"}. <b>The name and the month are separate
 * fields</b>: the canvas's "Japan, Nov 2027" is composed by the client from an i18n message, so a
 * right-to-left locale can reorder it (ADR-0023).
 *
 * <p>Every {@code toString} is redacted.
 */
public final class GoalResponses {

    private GoalResponses() {}

    /** One goal, the same shape on every route that returns one. */
    public record GoalResponse(
            UUID id,
            String name,
            String currency,
            BigDecimal targetAmount,
            String targetMonth,
            BigDecimal startingAmount,
            BigDecimal saved,
            BigDecimal remaining,
            boolean reached,
            boolean overdue,
            UUID accountId,
            ThisPeriodResponse thisPeriod,
            BigDecimal monthlyContribution,
            ProjectionResponse projection,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        static GoalResponse of(GoalView goal) {
            GoalFigures figures = goal.figures();
            return new GoalResponse(
                    goal.id(),
                    goal.name(),
                    goal.currency().getCurrencyCode(),
                    goal.targetAmount().amount(),
                    month(goal.targetMonth()),
                    goal.startingAmount().amount(),
                    figures.saved().amount(),
                    figures.remaining().amount(),
                    figures.reached(),
                    figures.overdue(),
                    goal.accountId(),
                    ThisPeriodResponse.of(figures.thisPeriod()),
                    figures.monthlyContribution().amount(),
                    ProjectionResponse.of(figures.projection(), goal.currency()),
                    goal.archived(),
                    goal.createdAt(),
                    goal.updatedAt(),
                    goal.version());
        }

        @Override
        public String toString() {
            return "GoalResponse[id=" + id + ", version=" + version + "]";
        }
    }

    /**
     * This month's line.
     *
     * @param planned what the plan asks of this month, zero for a month that began reached
     * @param stillToCome {@code max(planned − recorded, 0)}: what "Set money aside" prefills
     */
    public record ThisPeriodResponse(
            String period, BigDecimal planned, BigDecimal recorded, BigDecimal stillToCome) {

        static ThisPeriodResponse of(GoalFigures.ThisPeriod thisPeriod) {
            return new ThisPeriodResponse(
                    thisPeriod.period().toString(),
                    thisPeriod.planned().amount(),
                    thisPeriod.recorded().amount(),
                    thisPeriod.stillToCome().amount());
        }

        @Override
        public String toString() {
            return "ThisPeriodResponse[period=" + period + "]";
        }
    }

    /**
     * When it gets there, and what it would take. A {@code null} figure always travels with a
     * reason code, and the reason is never an error.
     */
    public record ProjectionResponse(
            String projectedMonth,
            String projectedReason,
            BigDecimal requiredMonthly,
            String requiredReason) {

        static ProjectionResponse of(GoalFigures.Projection projection, Currency currency) {
            return new ProjectionResponse(
                    month(projection.projectedMonth()),
                    reason(projection.projectedReason()),
                    minorUnits(projection.requiredMonthly(), currency),
                    reason(projection.requiredReason()));
        }

        @Override
        public String toString() {
            return "ProjectionResponse[projectedMonth="
                    + projectedMonth
                    + ", projectedReason="
                    + projectedReason
                    + ", requiredReason="
                    + requiredReason
                    + "]";
        }
    }

    /** The add form's preview: the figures a goal would have, with nothing stored. */
    public record DraftResponse(
            String currency,
            BigDecimal saved,
            BigDecimal remaining,
            boolean reached,
            boolean overdue,
            ThisPeriodResponse thisPeriod,
            BigDecimal monthlyContribution,
            ProjectionResponse projection) {

        static DraftResponse of(DraftView draft) {
            GoalFigures figures = draft.figures();
            return new DraftResponse(
                    draft.currency().getCurrencyCode(),
                    figures.saved().amount(),
                    figures.remaining().amount(),
                    figures.reached(),
                    figures.overdue(),
                    ThisPeriodResponse.of(figures.thisPeriod()),
                    figures.monthlyContribution().amount(),
                    ProjectionResponse.of(figures.projection(), draft.currency()));
        }

        @Override
        public String toString() {
            return "DraftResponse[currency=" + currency + "]";
        }
    }

    /** One step of the plan. */
    public record PlanStepResponse(String fromPeriod, BigDecimal monthlyAmount, String currency) {

        static PlanStepResponse of(PlanStep step) {
            return new PlanStepResponse(
                    step.fromPeriod().toString(),
                    step.monthlyAmount().amount(),
                    step.monthlyAmount().currency().getCurrencyCode());
        }

        @Override
        public String toString() {
            return "PlanStepResponse[fromPeriod=" + fromPeriod + "]";
        }
    }

    /**
     * One contribution.
     *
     * @param amount signed: negative is a withdrawal, and the client says so in words
     */
    public record ContributionResponse(
            UUID id,
            UUID goalId,
            LocalDate date,
            String period,
            BigDecimal amount,
            String currency,
            String note,
            UUID transactionId,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        static ContributionResponse of(ContributionView contribution) {
            return new ContributionResponse(
                    contribution.id(),
                    contribution.goalId(),
                    contribution.date(),
                    contribution.period().toString(),
                    contribution.amount().amount(),
                    contribution.amount().currency().getCurrencyCode(),
                    contribution.note(),
                    contribution.transactionId(),
                    contribution.createdAt(),
                    contribution.updatedAt(),
                    contribution.version());
        }

        @Override
        public String toString() {
            return "ContributionResponse[id=" + id + ", goalId=" + goalId + "]";
        }
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }

    private static String reason(ProjectionReason reason) {
        return reason == null ? null : reason.code();
    }

    /**
     * The required figure at the currency's own scale: {@code "163.13"}, {@code "164"} for yen.
     * {@code UNNECESSARY} because the projection already rounded it up there; anything else would
     * be a second rounding step hiding a bug in the first.
     */
    private static BigDecimal minorUnits(Money amount, Currency currency) {
        return amount == null
                ? null
                : amount.amount()
                        .setScale(
                                GoalProjection.minorUnitScale(currency), RoundingMode.UNNECESSARY);
    }
}
