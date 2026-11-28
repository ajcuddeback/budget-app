package com.budgetowl.checkin.web;

import com.budgetowl.checkin.domain.AnswerChoice;
import com.budgetowl.checkin.service.CheckInViews.HistoryEntry;
import com.budgetowl.checkin.service.CheckInViews.OutcomeView;
import com.budgetowl.checkin.service.CheckInViews.Person;
import com.budgetowl.checkin.service.CheckInViews.RuleCounts;
import com.budgetowl.checkin.service.CheckInViews.SnapshotView;
import com.budgetowl.checkin.service.CheckInViews.StepView;
import com.budgetowl.checkin.service.CheckInViews.WeekView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies for the check-in. The nested step, item and figure records are the service's
 * views as they are, every amount an {@code {"amount", "currency"}} pair of strings (ADR-0006,
 * {@code MoneyJsonModule}); only the week is re-shaped, to the {@code 2026-W34} the API names it
 * by. <b>No sentence is pre-composed</b>: the client builds every one from i18n messages
 * (ADR-0023).
 */
public final class CheckInResponses {

    private CheckInResponses() {}

    public record WeekResponse(
            String week,
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDate windowStart,
            String status,
            Long version,
            String basis,
            Person startedBy,
            Instant completedAt,
            Person completedBy,
            String currency,
            long otherCurrencyRows,
            RuleCounts byRule,
            List<StepView> steps,
            SnapshotView snapshot,
            List<OutcomeView> outcome) {

        static WeekResponse of(WeekView view) {
            return new WeekResponse(
                    view.week().toString(),
                    view.week().monday(),
                    view.week().sunday(),
                    view.windowStart(),
                    view.status(),
                    view.version(),
                    view.basis(),
                    view.startedBy(),
                    view.completedAt(),
                    view.completedBy(),
                    view.currency(),
                    view.otherCurrencyRows(),
                    view.byRule(),
                    view.steps(),
                    view.snapshot(),
                    view.outcome());
        }

        /** Redacted: a week is a household's whole weekly position. */
        @Override
        public String toString() {
            return "WeekResponse[week=" + week + ", status=" + status + "]";
        }
    }

    public record HistoryResponse(
            String week,
            String status,
            Instant completedAt,
            String currency,
            SnapshotView snapshot,
            AnswerChoice allocation,
            UUID allocationTargetId) {

        static HistoryResponse of(HistoryEntry entry) {
            return new HistoryResponse(
                    entry.week().toString(),
                    entry.status(),
                    entry.completedAt(),
                    entry.currency(),
                    entry.snapshot(),
                    entry.allocation(),
                    entry.allocationTargetId());
        }

        @Override
        public String toString() {
            return "HistoryResponse[week=" + week + "]";
        }
    }
}
