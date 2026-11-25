package com.budgetowl.transfer.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One transfer, as a surface sees it: the pair, read as the single thing it is.
 *
 * @param id the {@code transferGroupId}. The pair is the object, so neither leg's id is its name
 * @param sent what left, as a positive figure in the from-account's currency
 * @param received what arrived, in the to-account's currency. Equal in magnitude to {@code sent}
 *     when the currencies match
 * @param crossCurrency whether the two accounts are denominated differently. A client needs this to
 *     decide whether one figure or two is the honest thing to print
 * @param impliedRate {@code received / sent}, to six places, or null when the currencies match.
 *     <b>Derived for display and stored nowhere</b> (ADR-0022): nothing reads it back, nothing
 *     multiplies by it, and it is not what the bank charged — it is what the two figures imply
 * @param fromDate the outgoing leg's date, and {@code toDate} the incoming leg's. The same for a
 *     transfer made with "Move money"; possibly two days for a pair made from two statements, where
 *     each bank's date is the true one (file-import.md rule 12)
 * @param fromNote the outgoing leg's note, and {@code toNote} the incoming leg's, likewise
 * @param version the pair's. Both legs are written together and bumped together, so one number
 *     describes both; the legs' own versions are an implementation detail of that
 */
public record TransferView(
        UUID id,
        UUID fromAccountId,
        String fromAccountName,
        UUID toAccountId,
        String toAccountName,
        LocalDate fromDate,
        LocalDate toDate,
        Money sent,
        Money received,
        boolean crossCurrency,
        BigDecimal impliedRate,
        String fromNote,
        String toNote,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Redacted, as {@code AccountView} is: a generated one carries both figures. */
    @Override
    public String toString() {
        return "TransferView[id=" + id + ", crossCurrency=" + crossCurrency + "]";
    }
}
