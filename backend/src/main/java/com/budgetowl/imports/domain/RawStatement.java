package com.budgetowl.imports.domain;

import java.util.List;
import java.util.Objects;

/**
 * What a reader took out of a file, before any of it is interpreted.
 *
 * @param rows in file order
 * @param currency OFX {@code CURDEF}, or {@code null} for formats that do not carry one
 * @param ledgerBalance OFX {@code LEDGERBAL/BALAMT} as text, shown to the person who uploaded the
 *     file as "your bank says" and <b>never stored</b>
 * @param accountIdentifier OFX {@code ACCTID}: a full account number. Held only long enough to be
 *     compared with the account's {@code lastFour}, and <b>never stored, logged or returned</b>
 *     (ADR-0028, ADR-0038). {@link #toString} does not print it, and nothing else may
 */
public record RawStatement(
        List<RawRow> rows, String currency, String ledgerBalance, String accountIdentifier) {

    public RawStatement {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /** Counts only. This record holds a bank statement and a full account number. */
    @Override
    public String toString() {
        return "RawStatement[rows=" + rows.size() + "]";
    }
}
