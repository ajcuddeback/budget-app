package com.budgetowl.transaction.service;

import com.budgetowl.common.Money;
import java.util.List;

/**
 * One page of the ledger, plus the counts the envelope needs.
 *
 * @param pageTotal the sum of the amounts ON THIS PAGE, or null when the page mixes currencies.
 *     Null rather than a converted figure: ADR-0022 keeps conversion in the display layer, and a
 *     server-side total across currencies would be a number nobody could check. A single-account
 *     ledger always has one currency, which is the case the total is for
 */
public record TransactionPage(
        List<TransactionView> transactions,
        Money pageTotal,
        int page,
        int size,
        long totalElements,
        int totalPages) {}
