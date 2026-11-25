package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.ReadRow;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a file, set against the ledger as it is now.
 *
 * @param externalId the row's tier-1 identity, for a row that could be imported
 * @param resembles for a suspected duplicate, the ledger row it looks like; for an exact duplicate,
 *     the row that already carries its identity
 * @param resemblesDate that row's date
 */
public record Classified(
        ReadRow row,
        Classification classification,
        String externalId,
        UUID resembles,
        LocalDate resemblesDate) {

    /** What a row is to this import. */
    public enum Classification {
        /** Not in the ledger: imported. */
        NEW,
        /** Looks like a row already in the account: excluded unless the person includes it. */
        SUSPECTED,
        /** Already imported (tier 1): counted and listed, never written, never overridable. */
        DUPLICATE,
        /** Could not be read. */
        UNREADABLE,
        /** Moves no money: counted, never imported. */
        ZERO
    }

    /** A statement row: printed as its number and classification only. */
    @Override
    public String toString() {
        return "Classified[record=" + row.record() + ", " + classification + "]";
    }
}
