package com.budgetowl.debt.persistence;

import com.budgetowl.debt.domain.PaymentProvenance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One stored payment, reduced to what deciding a month's interest, and what was already paid in it,
 * needs: which debt, which day, how much, how much interest it carried and how it got here
 * (docs/features/debt-plan.md, The split; ADR-0043).
 *
 * <p>A read model for {@code MonthInterest}, never written. Amounts are never logged.
 */
public record MonthInterestRow(
        UUID paymentId,
        UUID debtId,
        LocalDate paidDate,
        BigDecimal amount,
        BigDecimal interest,
        PaymentProvenance provenance) {

    @Override
    public String toString() {
        return "MonthInterestRow[paymentId=" + paymentId + ", debtId=" + debtId + "]";
    }
}
