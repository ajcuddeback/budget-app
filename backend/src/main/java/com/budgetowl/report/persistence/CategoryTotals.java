package com.budgetowl.report.persistence;

import com.budgetowl.category.domain.CategoryKind;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One category's sums for one currency across a range and the range before it, straight out of the
 * grouped query. Raw and signed.
 *
 * <p>The category columns are all {@code null} for the one group per currency that holds the
 * uncategorised rows. The parent columns are {@code null} for a top-level category. Both come from
 * <b>today's</b> tree (docs/features/reporting.md, "The tree is today's tree").
 *
 * @param amount {@code Σ amount} over the requested range
 * @param comparedAmount {@code Σ amount} over the preceding range of the same length
 * @param outflow {@code Σ amount} over the requested range's rows with {@code amount < 0}: what the
 *     uncategorised bucket reports, because an uncategorised inflow is not spending
 * @param outflowCount how many of the requested range's rows have {@code amount < 0}
 */
public record CategoryTotals(
        String currency,
        UUID categoryId,
        String name,
        Boolean archived,
        CategoryKind kind,
        UUID parentId,
        String parentName,
        Boolean parentArchived,
        BigDecimal amount,
        BigDecimal comparedAmount,
        BigDecimal outflow,
        Long outflowCount) {

    /**
     * Ids and the currency only. Never a name and never a figure: a household's own category names
     * ("Therapy", "IVF") beside a figure are the most disclosing thing this slice handles.
     */
    @Override
    public String toString() {
        return "CategoryTotals[currency=" + currency + ", categoryId=" + categoryId + "]";
    }
}
