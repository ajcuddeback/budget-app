package com.budgetowl.debt.service;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One debt as the payoff ladder sees it: a balance, a rate, a floor, and enough identity to break a
 * tie the same way twice.
 *
 * <p>Deliberately not the {@code Debt} entity. The ladder is arithmetic over four numbers and must
 * stay unit-testable with no database, no household and no Spring context — and an entity that can
 * reach the ladder is an entity one step from reaching a response body.
 *
 * @param id the household's debt id. The last tie-breaker, and the only one guaranteed to be unique
 * @param name the debt's name, the tie-breaker before the id, because two cards with the same rate
 *     and the same balance should at least rank alphabetically rather than arbitrarily
 * @param balance what is owed now — positive, because a debt at zero has left the ladder
 * @param apr the annual rate as a percentage. <b>Never null here:</b> a solved rate is solved
 *     before the plan runs, and a ladder that quietly treated an unknown rate as zero would rank a
 *     payday loan last
 * @param minimumPayment the floor the plan allocates to this debt in every month until the rung
 *     above it clears. Always entered, never solved, and it never participates in this debt's own
 *     projection
 * @param customOrder the household's position for this debt under {@link PayoffStrategy#CUSTOM}, or
 *     null for a debt it has not placed. Ignored by the two named strategies, which derive the
 *     order from the numbers
 * @param firstMonth what is already true of the plan's month 1, which is the current month
 *     (ADR-0043): its interest booked by a recorded payment, so the ladder charges this debt none
 *     in month 1, and what was already paid towards this debt this month, which counts as paid
 */
public record LadderDebt(
        UUID id,
        String name,
        Money balance,
        BigDecimal apr,
        Money minimumPayment,
        Integer customOrder,
        FirstMonth firstMonth) {

    /** A debt whose first month nothing has happened in yet: the ordinary case. */
    public LadderDebt(
            UUID id,
            String name,
            Money balance,
            BigDecimal apr,
            Money minimumPayment,
            Integer customOrder) {
        this(
                id,
                name,
                balance,
                apr,
                minimumPayment,
                customOrder,
                FirstMonth.untouched(balance.currency()));
    }

    public LadderDebt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(apr, "apr");
        Objects.requireNonNull(minimumPayment, "minimumPayment");
        Objects.requireNonNull(firstMonth, "firstMonth");
        if (!balance.isPositive()) {
            throw new IllegalArgumentException("A debt in the ladder has a positive balance");
        }
        if (apr.signum() < 0) {
            throw new IllegalArgumentException("A rate cannot be negative");
        }
        if (minimumPayment.isNegative()) {
            throw new IllegalArgumentException("A minimum payment cannot be negative");
        }
        if (!minimumPayment.currency().equals(balance.currency())) {
            throw new CurrencyMismatchException(balance.currency(), minimumPayment.currency());
        }
    }

    /**
     * Ids only. A servicer name is the sharpest field this app holds — <em>Nelnet</em> says this
     * household has student debt and a medical financing company says something about somebody's
     * health — and a balance is a balance (docs/architecture/security-model.md).
     */
    @Override
    public String toString() {
        return "LadderDebt[id=" + id + "]";
    }
}
