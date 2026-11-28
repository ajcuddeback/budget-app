package com.budgetowl.bill.service;

import com.budgetowl.common.schedule.Recurrence;
import java.util.UUID;

/**
 * A bill to create.
 *
 * @param expectedAmount a decimal string (ADR-0006), parsed in the service
 * @param tolerance required when {@code amountVaries}, refused otherwise
 */
public record NewBill(
        String name,
        String expectedAmount,
        Recurrence recurrence,
        UUID categoryId,
        boolean amountVaries,
        String tolerance) {}
