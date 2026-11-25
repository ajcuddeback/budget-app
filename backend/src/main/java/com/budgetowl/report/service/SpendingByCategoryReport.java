package com.budgetowl.report.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Report 2: spending by category over a range, against the immediately preceding range of the same
 * length, <b>per currency</b>.
 *
 * <p>Every record here prints ids and shapes, never a name and never a figure (see {@link
 * IncomeAndSpendingReport} on why).
 *
 * @param compared the preceding range of the same length
 * @param complete false when the range reaches the current month, by the application clock
 */
public record SpendingByCategoryReport(
        ReportRange range,
        ReportRange compared,
        boolean complete,
        Currency baseCurrency,
        List<CurrencySpending> currencies) {

    public SpendingByCategoryReport {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(compared, "compared");
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        currencies = List.copyOf(currencies);
    }

    @Override
    public String toString() {
        return "SpendingByCategoryReport[range="
                + range.from()
                + ".."
                + range.to()
                + ", currencies="
                + currencies.size()
                + "]";
    }

    /**
     * One currency's tree.
     *
     * @param spending the sum of every top-level row's spending: expense categories only, net of
     *     refunds, and not including the uncategorised outflow
     * @param categories top-level rows, ordered by spending descending, then name, then id
     * @param uncategorised the range's uncategorised outflow, beside the tree rather than in it
     */
    public record CurrencySpending(
            Currency currency,
            Money spending,
            Money comparedSpending,
            List<CategorySpending> categories,
            Uncategorised uncategorised) {

        public CurrencySpending {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(spending, "spending");
            Objects.requireNonNull(comparedSpending, "comparedSpending");
            categories = List.copyOf(categories);
            Objects.requireNonNull(uncategorised, "uncategorised");
        }

        @Override
        public String toString() {
            return "CurrencySpending[currency="
                    + currency
                    + ", categories="
                    + categories.size()
                    + "]";
        }
    }

    /**
     * One category, with its children if it has any. A top-level row's figures include its
     * children's; {@code direct} is what was filed on the parent itself, so the children plus
     * {@code direct} equal the parent exactly.
     *
     * @param spending net: positive when money left, negative when refunds beat spending
     * @param change {@code (spending − comparedSpending) ÷ |comparedSpending|} at scale 4 {@code
     *     HALF_EVEN}; empty when {@code comparedSpending} is zero, because a rise from nothing has
     *     no percentage
     * @param share this row's fraction of the currency's positive spending, at scale 4; empty when
     *     this row's spending is zero or negative
     */
    public record CategorySpending(
            UUID categoryId,
            String name,
            boolean archived,
            Money spending,
            Money comparedSpending,
            Optional<BigDecimal> change,
            Optional<BigDecimal> share,
            Money direct,
            List<CategorySpending> children) {

        public CategorySpending {
            Objects.requireNonNull(categoryId, "categoryId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(spending, "spending");
            Objects.requireNonNull(comparedSpending, "comparedSpending");
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(share, "share");
            Objects.requireNonNull(direct, "direct");
            children = List.copyOf(children);
        }

        /** The id only: a category's name beside nothing is still a household's own label. */
        @Override
        public String toString() {
            return "CategorySpending[categoryId="
                    + categoryId
                    + ", children="
                    + children.size()
                    + "]";
        }
    }

    /** The range's uncategorised outflow and how many rows it is. */
    public record Uncategorised(Money spending, long count) {

        public Uncategorised {
            Objects.requireNonNull(spending, "spending");
        }

        @Override
        public String toString() {
            return "Uncategorised[count=" + count + "]";
        }
    }
}
