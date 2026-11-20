package com.budgetowl.bill.service;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One bill, with the occurrences the period asked about implies.
 *
 * @param occurrences every occurrence in the period — four or five for a weekly bill, none for an
 *     irregular one or a yearly one outside its month
 * @param tolerance null unless the amount varies
 */
public record BillView(
        UUID id,
        String name,
        UUID categoryId,
        String categoryName,
        String categoryParentName,
        Money expectedAmount,
        Recurrence recurrence,
        boolean amountVaries,
        BigDecimal tolerance,
        boolean active,
        List<OccurrenceView> occurrences,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /**
     * Redacted, as {@code AccountView} is: a generated one carries the bill's name and every
     * figure, and a view is one careless log statement from being printed.
     */
    @Override
    public String toString() {
        return "BillView[id=" + id + ", occurrences=" + occurrences.size() + "]";
    }
}
