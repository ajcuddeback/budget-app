package com.budgetowl.debt.service;

import java.util.List;
import java.util.UUID;

/**
 * A change to the household's plan.
 *
 * <p><b>Setting a strategy is a write</b>, even though it stores two values and computes nothing: a
 * {@code VIEWER} is refused here as everywhere, because the figures the household acts on would
 * change.
 *
 * @param strategy the order to attack in, or null to leave it alone
 * @param monthlyAmount a decimal string (ADR-0006), or null to leave it alone. <b>Accepted below
 *     the sum of the minimums</b>, where it produces no projection rather than a {@code 422}
 * @param customOrder the household's own order, as debt ids from rung 1 down, or null to leave
 *     every debt's position alone. A debt the list leaves out has its position <b>cleared</b>, so
 *     the list is the whole order rather than a patch to it — a half-applied order is how two
 *     members' drags end up interleaved
 * @param version the plan's version, required: optimistic locking only works if the caller says
 *     which version it read
 */
public record PlanChanges(
        String strategy, String monthlyAmount, List<UUID> customOrder, long version) {

    public PlanChanges {
        customOrder = customOrder == null ? null : List.copyOf(customOrder);
    }

    /** Redacted: the monthly amount is a statement of disposable income. */
    @Override
    public String toString() {
        return "PlanChanges[strategy=" + strategy + ", version=" + version + "]";
    }
}
