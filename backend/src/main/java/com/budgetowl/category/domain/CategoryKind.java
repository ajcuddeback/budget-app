package com.budgetowl.category.domain;

/**
 * Whether a category classifies money arriving or money leaving.
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}) and constrained to this set by
 * {@code ck_categories_kind}. A persisted ordinal would renumber every row the day a third kind is
 * inserted anywhere but the end of the list.
 *
 * <p><b>This is not the same fact as the sign of a transaction's amount.</b> A category's kind says
 * what the category is for; the sign says which way the money actually went. They usually agree,
 * and when they do not it is a refund — a positive amount against {@code Groceries} — which is
 * ordinary and must not be rejected. See {@code docs/features/transactions.md}.
 */
public enum CategoryKind {
    INCOME,
    EXPENSE
}
