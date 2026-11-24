package com.budgetowl.debt.domain;

/**
 * What shape of debt this is (docs/features/debt-plan.md).
 *
 * <p>Two, because the designs show two and because the pair changes what the words on screen mean
 * rather than what the arithmetic does: the recurrence is identical for both, and {@code
 * originalBalance} is a progress baseline on an installment loan and a phrase that means nothing on
 * a revolving card.
 *
 * <p>A mortgage is deliberately not here. It would be an {@code INSTALLMENT} with a term nothing in
 * slice 10 models — and {@code ck_debts_kind} refuses it, so adding the word needs a migration
 * rather than a line in this file.
 */
public enum DebtKind {

    /** Revolving: a balance that can go up again. */
    CREDIT_CARD,

    /** A fixed advance being paid down — a car loan, a student loan. */
    INSTALLMENT
}
