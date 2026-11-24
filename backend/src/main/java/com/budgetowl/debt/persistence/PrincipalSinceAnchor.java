package com.budgetowl.debt.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much principal one debt has had paid off it <b>since its anchor date</b>.
 *
 * <p>The balance is the anchor minus this, which is why the anchor date is part of the query rather
 * than something a caller filters on afterwards: the comparison belongs next to the rows it
 * excludes. The anchor is the figure as at the start of its date — see the query for which rows
 * that includes and the two that it does not.
 *
 * <p>Summed rather than read off the latest payment's {@code balanceAfter}. The chain of closing
 * balances agrees with the sum only while the payments were recorded in date order, and a household
 * logging a payment it forgot last month breaks that — the sum stays right.
 *
 * @param principalPaid never null: a debt with no qualifying payments is absent from the result
 *     rather than present with a zero
 */
public record PrincipalSinceAnchor(UUID debtId, BigDecimal principalPaid) {

    /** Amounts are never logged (docs/architecture/security-model.md). */
    @Override
    public String toString() {
        return "PrincipalSinceAnchor[debtId=" + debtId + "]";
    }
}
