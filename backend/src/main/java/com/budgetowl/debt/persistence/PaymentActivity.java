package com.budgetowl.debt.persistence;

import java.time.LocalDate;
import java.util.UUID;

/**
 * When one debt was last paid, and how many times.
 *
 * <p>Counted over <b>every</b> payment, the ones on or before the anchor date included, because
 * both figures answer questions the anchor does not bound: the day of the month the next payment
 * falls on, and the month after which the balance may be estimated.
 */
public record PaymentActivity(UUID debtId, LocalDate latestPaidDate, long paymentCount) {

    @Override
    public String toString() {
        return "PaymentActivity[debtId=" + debtId + ", paymentCount=" + paymentCount + "]";
    }
}
