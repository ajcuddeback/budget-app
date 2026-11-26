package com.budgetowl.checkin.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** An earlier outflow from a payee, for the unusual-charge and recurring-charge checks. */
public record HistoryRow(UUID id, UUID payeeId, UUID accountId, LocalDate date, BigDecimal amount) {

    @Override
    public String toString() {
        return "HistoryRow[id=" + id + "]";
    }
}
