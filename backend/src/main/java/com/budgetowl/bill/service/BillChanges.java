package com.budgetowl.bill.service;

import com.budgetowl.common.Change;
import com.budgetowl.common.schedule.Recurrence;
import java.util.UUID;

/**
 * A partial update to a bill.
 *
 * <p>{@code recurrence} is replaced wholesale or not at all: the schedule's parameters only mean
 * anything together, and a patch that could set a weekday without clearing a day of month would
 * produce a bill that projects nothing and looks like one nobody set up.
 *
 * <p>{@code amountVaries} and {@code tolerance} move together for the same reason.
 *
 * @param active retiring a bill is an ordinary field change rather than its own endpoint: unlike
 *     archiving an account it closes nothing off, it only stops future projection
 */
public record BillChanges(
        String name,
        String expectedAmount,
        Recurrence recurrence,
        Change<UUID> categoryId,
        Boolean amountVaries,
        String tolerance,
        Boolean active,
        long version) {}
