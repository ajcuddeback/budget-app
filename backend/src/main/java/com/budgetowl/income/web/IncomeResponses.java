package com.budgetowl.income.web;

import com.budgetowl.common.schedule.web.ScheduleResponse;
import com.budgetowl.income.domain.SourceOfTruth;
import com.budgetowl.income.service.IncomeForPeriod;
import com.budgetowl.income.service.IncomeView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies for the income endpoints. The entity never appears in one. */
public final class IncomeResponses {

    private IncomeResponses() {}

    /**
     * One income source, with what it is worth in the month asked about.
     *
     * @param dates when it arrives that month — <b>three for a fortnightly wage in some months</b>,
     *     which is the month the household actually has and not a figure to be averaged flat
     * @param expectedForPeriod {@code amount} times the number of arrivals
     */
    public record IncomeResponse(
            UUID id,
            String name,
            BigDecimal amount,
            String currency,
            ScheduleResponse schedule,
            UUID accountId,
            String accountName,
            SourceOfTruth sourceOfTruth,
            boolean active,
            List<LocalDate> dates,
            BigDecimal expectedForPeriod,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted: a generated one would put somebody's salary into any log line. */
        @Override
        public String toString() {
            return "IncomeResponse[id=" + id + ", active=" + active + "]";
        }

        static IncomeResponse of(IncomeView view) {
            return new IncomeResponse(
                    view.id(),
                    view.name(),
                    view.amount().amount(),
                    view.amount().currency().getCurrencyCode(),
                    ScheduleResponse.of(view.recurrence()),
                    view.accountId(),
                    view.accountName(),
                    view.sourceOfTruth(),
                    view.active(),
                    view.dates(),
                    view.expectedForPeriod().amount(),
                    view.createdAt(),
                    view.updatedAt(),
                    view.version());
        }
    }

    /**
     * One month's income sources and what they come to.
     *
     * @param total an <b>expectation</b>, not a record of money that arrived (ADR-0025)
     */
    public record IncomeListResponse(
            List<IncomeResponse> content, BigDecimal total, String currency) {

        /** Redacted, for the reason {@code LedgerResponse} is. */
        @Override
        public String toString() {
            return "IncomeListResponse[sources=" + content.size() + "]";
        }

        static IncomeListResponse of(IncomeForPeriod found) {
            return new IncomeListResponse(
                    found.sources().stream().map(IncomeResponse::of).toList(),
                    found.total().amount(),
                    found.total().currency().getCurrencyCode());
        }
    }
}
