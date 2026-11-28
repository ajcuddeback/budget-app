package com.budgetowl.checkin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The references one answer carries, already resolved through the household by the service.
 *
 * <p>{@link #requireFits} is the Java statement of the per-kind {@code CHECK}s in V20. Both exist
 * on purpose: this gives a clean refusal before anything is flushed, and the constraint holds
 * whatever path a future change writes by.
 */
public record AnswerShape(
        UUID categoryId,
        UUID billId,
        LocalDate dueDate,
        UUID goalId,
        UUID debtId,
        BigDecimal amount,
        boolean remember,
        boolean recordDebtPayment) {

    public static AnswerShape none() {
        return new AnswerShape(null, null, null, null, null, null, false, false);
    }

    public static AnswerShape category(UUID categoryId, boolean remember) {
        return new AnswerShape(categoryId, null, null, null, null, null, remember, false);
    }

    public static AnswerShape target(
            UUID billId, LocalDate dueDate, boolean remember, boolean recordDebtPayment) {
        return new AnswerShape(
                null, billId, dueDate, null, null, null, remember, recordDebtPayment);
    }

    public static AnswerShape debtPrompt(boolean recordDebtPayment) {
        return new AnswerShape(null, null, null, null, null, null, false, recordDebtPayment);
    }

    public static AnswerShape allocation(UUID goalId, UUID debtId, BigDecimal amount) {
        return new AnswerShape(null, null, null, goalId, debtId, amount, false, false);
    }

    /**
     * @throws IllegalArgumentException when the shape is not one this kind and choice may carry
     */
    void requireFits(ItemKind kind, AnswerChoice choice) {
        boolean fits =
                switch (kind) {
                    case OVERDUE_BILL ->
                            noCategory()
                                    && noTarget()
                                    && noAllocation()
                                    && !remember
                                    && (choice == AnswerChoice.MARK_PAID || !recordDebtPayment);
                    case UNUSUAL_CHARGE ->
                            noCategory()
                                    && noAllocation()
                                    && !remember
                                    && !recordDebtPayment
                                    && (choice == AnswerChoice.PREPAID ? hasTarget() : noTarget());
                    case CATEGORIZE ->
                            noTarget()
                                    && noAllocation()
                                    && !recordDebtPayment
                                    && (choice == AnswerChoice.CATEGORY
                                            ? categoryId != null
                                            : categoryId == null && !remember);
                    case BILL_CHARGE ->
                            noCategory()
                                    && noAllocation()
                                    && (choice == AnswerChoice.ATTACH
                                            ? hasTarget()
                                            : noTarget() && !remember && !recordDebtPayment);
                    case ALLOCATION ->
                            noCategory()
                                    && noTarget()
                                    && !remember
                                    && !recordDebtPayment
                                    && amount != null
                                    && amount.signum() > 0
                                    && (choice == AnswerChoice.DEBT) == (debtId != null)
                                    && (choice == AnswerChoice.GOAL) == (goalId != null);
                };
        if (!fits) {
            throw new IllegalArgumentException("that answer does not fit this kind of item");
        }
    }

    private boolean noCategory() {
        return categoryId == null;
    }

    private boolean noTarget() {
        return billId == null && dueDate == null;
    }

    private boolean hasTarget() {
        return billId != null && dueDate != null;
    }

    private boolean noAllocation() {
        return goalId == null && debtId == null && amount == null;
    }

    /** Redacted: an amount and the ids it moves between. */
    @Override
    public String toString() {
        return "AnswerShape[]";
    }
}
