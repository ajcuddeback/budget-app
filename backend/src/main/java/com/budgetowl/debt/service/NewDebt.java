package com.budgetowl.debt.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * A debt to create.
 *
 * <p>The required fields are a name, a kind, a minimum, a balance and the date it was true — and a
 * rate, <b>unless</b> a payment and a horizon are both given, which is the installment-loan case
 * where a statement names everything except the rate. Those are the three fields onboarding step 2
 * captures, so building that step later needs no new API.
 *
 * <p>Amounts and the rate are decimal strings (ADR-0006), parsed in the service. The horizon
 * arrives in whichever of its two faces the household typed; both is a {@code 400}, because they
 * are one unknown and two stored answers to it could disagree.
 *
 * @param accountId optional. The debt's currency <b>is</b> that account's where it is set
 * @param billId optional, and the only thing that keeps a minimum from being counted twice. Named
 *     by the household, never inferred
 */
public record NewDebt(
        String name,
        String kind,
        String servicer,
        String currency,
        String balance,
        LocalDate balanceDate,
        String apr,
        String minimumPayment,
        String originalBalance,
        String payment,
        Integer monthsLeft,
        YearMonth payoffMonth,
        UUID accountId,
        UUID billId) {

    /** Redacted: every amount in here is somebody's debt. */
    @Override
    public String toString() {
        return "NewDebt[kind=" + kind + "]";
    }
}
