package com.budgetowl.checkin.service;

import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.SpendingGroup;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <b>The one definition</b> of a week's unbudgeted spending (docs/features/weekly-check-in.md,
 * decision 2): money that left for things the month's plan had not already set aside.
 *
 * <pre>
 * unbudgeted(from, to) = −Σ t.amount over base-currency, non-transfer, unattached rows where
 *     t's category is EXPENSE (net of refunds), or t is uncategorised and t.amount &lt; 0,
 *     and neither t's category nor its parent is budgeted in t's month
 *     (an unarchived envelope's category, or an active bill's with an unskipped occurrence)
 * </pre>
 *
 * <p>The query that feeds this ({@code CheckInLedgerRepository.spendingGroups}) applies only the
 * filters that cannot be argued with — household, currency, transfer, attachment, date — and
 * groups. Every judgement about a category is {@link #of}, here, in Java, once; a draft's answers
 * are applied through the same method ({@link #adjusted}), so "with the answers applied" cannot
 * disagree with "as the ledger stands".
 *
 * <p>No database, no clock: {@code UnbudgetedSpendingTest} exercises each exclusion on its own,
 * because one test that "spending returns a number" passes while an exclusion is missing.
 */
final class UnbudgetedSpending {

    private final Currency currency;
    private final Map<UUID, CategoryRow> categories;
    private final Map<YearMonth, Set<UUID>> budgeted;

    /**
     * @param categories every category of the household, by id
     * @param budgeted per month, the categories an envelope or a bill of that month already budgets
     */
    UnbudgetedSpending(
            Currency currency,
            Map<UUID, CategoryRow> categories,
            Map<YearMonth, Set<UUID>> budgeted) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.categories = Map.copyOf(categories);
        this.budgeted = Map.copyOf(budgeted);
    }

    /**
     * What one amount filed under one category on one day adds to unbudgeted spending: positive for
     * money out, negative for a refund, zero for anything the rule leaves out.
     *
     * @param categoryId null for uncategorised
     * @param amount signed, as the ledger stores it
     */
    BigDecimal of(LocalDate date, UUID categoryId, BigDecimal amount) {
        if (categoryId == null) {
            // An uncategorised outflow is spending nobody has explained yet; an uncategorised
            // deposit is not netted, because it may well be the salary (accepted risk).
            return amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO;
        }
        CategoryRow category = categories.get(categoryId);
        if (category == null || category.kind() != CategoryKind.EXPENSE) {
            return BigDecimal.ZERO;
        }
        Set<UUID> inPlan = budgeted.getOrDefault(YearMonth.from(date), Set.of());
        if (inPlan.contains(categoryId)
                || (category.parentId() != null && inPlan.contains(category.parentId()))) {
            // Rent is in `left` as a bill and groceries as an envelope. Counting them against the
            // week too would count them twice (decision 2).
            return BigDecimal.ZERO;
        }
        return amount.negate();
    }

    /** The groups' total over {@code [from, to]}, before any answer is applied. */
    Money between(Collection<SpendingGroup> groups, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        if (!to.isBefore(from)) {
            for (SpendingGroup group : groups) {
                if (!group.date().isBefore(from) && !group.date().isAfter(to)) {
                    total =
                            total.add(of(group.date(), group.categoryId(), group.outflow()))
                                    .add(of(group.date(), group.categoryId(), group.inflow()));
                }
            }
        }
        return Money.of(total, currency);
    }

    /**
     * {@code total} with one row's answer applied: its contribution as it stands taken out, and its
     * contribution as answered put back. A row the answer attaches to a bill counts nothing.
     *
     * @param wasCounted whether the row is among the groups at all (unattached, base currency)
     */
    Money adjusted(
            Money total,
            LocalDate from,
            LocalDate to,
            LocalDate date,
            BigDecimal amount,
            UUID categoryBefore,
            UUID categoryAfter,
            boolean attachedAfter,
            boolean wasCounted) {
        if (!wasCounted || date.isBefore(from) || date.isAfter(to)) {
            return total;
        }
        BigDecimal before = of(date, categoryBefore, amount);
        BigDecimal after = attachedAfter ? BigDecimal.ZERO : of(date, categoryAfter, amount);
        return total.plus(Money.of(after.subtract(before), currency));
    }

    @Override
    public String toString() {
        return "UnbudgetedSpending[categories=" + categories.size() + "]";
    }
}
