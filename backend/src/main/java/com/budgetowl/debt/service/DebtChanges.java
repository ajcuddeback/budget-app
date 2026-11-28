package com.budgetowl.debt.service;

import com.budgetowl.common.Change;
import java.time.YearMonth;
import java.util.UUID;

/**
 * A change to a debt's terms.
 *
 * <p><b>The entered set is explicit, and absent never means release.</b> Each solver field carries
 * one of three instructions: leave it alone ({@link Change.Unchanged}), release it to the solver
 * ({@link Change.Cleared}), or enter this value ({@link Change.SetTo}). A field dropped by accident
 * must not silently change what the household believes about its own payoff date, which is why
 * {@code transactions.md}'s three-way {@code PATCH} lesson applies here with more force.
 *
 * <p>The <b>client</b> is what releases a field — the designs make that the interaction
 * (<em>"change a solved field and it becomes yours — I'll re-solve around it"</em>) — and the
 * server's job is to refuse an over-determined set naming the fields rather than to pick one to
 * drop.
 *
 * @param monthsLeft and {@code payoffMonth}: the two faces of one unknown. Entering either clears
 *     the other; releasing either releases the horizon. Entering both at once is a {@code 400}
 * @param version the debt's version, required
 */
public record DebtChanges(
        String name,
        String kind,
        Change<String> servicer,
        String minimumPayment,
        Change<String> originalBalance,
        Change<String> apr,
        Change<String> payment,
        Change<Integer> monthsLeft,
        Change<YearMonth> payoffMonth,
        Change<UUID> accountId,
        Change<UUID> billId,
        Change<Integer> customOrder,
        long version) {

    /** Redacted — see {@code NewDebt}. */
    @Override
    public String toString() {
        return "DebtChanges[version=" + version + "]";
    }
}
