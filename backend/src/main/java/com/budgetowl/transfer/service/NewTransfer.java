package com.budgetowl.transfer.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A transfer to create.
 *
 * @param amount what LEAVES the from-account, as a positive decimal string (ADR-0006). The sign is
 *     the direction and the direction is already in the field names, so a caller never writes a
 *     minus
 * @param amountReceived what ARRIVES in the to-account. Required when the two accounts are in
 *     different currencies, and refused when they are not — there it would be a second name for
 *     {@code amount} and a chance for the two to disagree (ADR-0033)
 */
public record NewTransfer(
        UUID fromAccountId,
        UUID toAccountId,
        LocalDate date,
        String amount,
        String amountReceived,
        String note) {}
