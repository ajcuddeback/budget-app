package com.budgetowl.income.service;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.income.domain.SourceOfTruth;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One income source, with what it is worth in the period asked about.
 *
 * @param dates when it arrives in the period. <b>Two for a fortnightly wage in most months and
 *     three in some</b> — that is not a rounding error to be smoothed away, it is the month the
 *     household actually has
 * @param expectedForPeriod {@code amount} times {@code dates.size()}
 * @param accountName null when the source is not attributed to an account, which is allowed
 */
public record IncomeView(
        UUID id,
        String name,
        Money amount,
        Recurrence recurrence,
        UUID accountId,
        String accountName,
        SourceOfTruth sourceOfTruth,
        boolean active,
        List<LocalDate> dates,
        Money expectedForPeriod,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /**
     * Redacted, as {@code AccountView} is: a generated one carries somebody's salary, and a view is
     * one careless log statement from being printed.
     */
    @Override
    public String toString() {
        return "IncomeView[id=" + id + ", active=" + active + "]";
    }
}
