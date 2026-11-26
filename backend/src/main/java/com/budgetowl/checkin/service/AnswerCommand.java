package com.budgetowl.checkin.service;

import com.budgetowl.checkin.domain.AnswerChoice;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One answer as a member gave it. Every id is a lookup key, resolved by the service among what it
 * derived for this household's week; none is evidence of ownership.
 *
 * @param version absent to create, the answer's own version to replace
 */
public record AnswerCommand(
        AnswerChoice choice,
        UUID categoryId,
        UUID billId,
        LocalDate dueDate,
        UUID goalId,
        UUID debtId,
        String amount,
        Boolean remember,
        Boolean recordDebtPayment,
        Long version) {

    boolean remembers() {
        return Boolean.TRUE.equals(remember);
    }

    boolean recordsDebtPayment() {
        return Boolean.TRUE.equals(recordDebtPayment);
    }

    /** Redacted: an amount and the ids it moves between. */
    @Override
    public String toString() {
        return "AnswerCommand[choice=" + choice + ", version=" + version + "]";
    }
}
