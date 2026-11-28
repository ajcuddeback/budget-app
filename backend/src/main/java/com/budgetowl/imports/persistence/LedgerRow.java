package com.budgetowl.imports.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Just enough of a ledger row to ask whether an imported one resembles it. */
public record LedgerRow(UUID transactionId, LocalDate date, BigDecimal amount) {

    /** Carries an amount; never printed. */
    @Override
    public String toString() {
        return "LedgerRow[" + transactionId + "]";
    }
}
