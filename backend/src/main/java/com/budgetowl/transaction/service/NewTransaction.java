package com.budgetowl.transaction.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A transaction to record.
 *
 * @param amount a signed decimal STRING (ADR-0006), in the account's own currency — there is no
 *     currency field, because the account denominates it and a second copy could disagree
 * @param payeeName as the user typed it; null or blank for no payee. Matched case-insensitively
 *     against the household's existing payees and created if it is new — a person recording a
 *     purchase is not asked to manage a payee list first
 * @param categoryId null for "Needs a category", which is a normal state and not an error
 * @param status null means {@code CLEARED}: somebody typing a transaction watched it happen
 */
public record NewTransaction(
        UUID accountId,
        LocalDate date,
        String amount,
        String payeeName,
        UUID categoryId,
        String note,
        String status) {}
