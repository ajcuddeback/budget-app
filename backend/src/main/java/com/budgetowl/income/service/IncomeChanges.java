package com.budgetowl.income.service;

import com.budgetowl.common.Change;
import com.budgetowl.common.schedule.Recurrence;
import java.util.UUID;

/**
 * A partial update to an income source.
 *
 * <p>{@code recurrence} is replaced wholesale, for the reason {@code BillChanges} gives. There is
 * no {@code sourceOfTruth}: it says where the figure came from, and a caller asserting that its own
 * typed number was derived from history would make the field a lie.
 */
public record IncomeChanges(
        String name,
        String amount,
        Recurrence recurrence,
        Change<UUID> accountId,
        Boolean active,
        long version) {}
