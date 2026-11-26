package com.budgetowl.checkin.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The five kinds of question a check-in can ask, each with the choices that may answer it.
 *
 * <p>{@code ck_check_in_answers_*} in V20 says the same thing in the database, one {@code CHECK}
 * per kind, so a mis-shaped answer is unwritable as well as refused here.
 */
public enum ItemKind {
    /** Step 1: a bill occurrence still {@code DUE} after its date. Key {@code bill.<id>.<date>}. */
    OVERDUE_BILL(EnumSet.of(AnswerChoice.MARK_PAID, AnswerChoice.SKIP, AnswerChoice.LEAVE)),

    /** Step 1: a charge twice its normal size. Key {@code odd.<transactionId>}. */
    UNUSUAL_CHARGE(EnumSet.of(AnswerChoice.PREPAID, AnswerChoice.LEAVE)),

    /** Step 2: an uncategorised row. Key {@code cat.<transactionId>}. */
    CATEGORIZE(EnumSet.of(AnswerChoice.CATEGORY, AnswerChoice.LEAVE)),

    /** Step 3: a charge that may pay a bill. Key {@code chg.<transactionId>}. */
    BILL_CHARGE(EnumSet.of(AnswerChoice.ATTACH, AnswerChoice.NOT_A_BILL, AnswerChoice.LEAVE)),

    /** Step 4: where the surplus goes. Key {@code alloc}. */
    ALLOCATION(EnumSet.of(AnswerChoice.DEBT, AnswerChoice.GOAL, AnswerChoice.ROLL_OVER));

    private final Set<AnswerChoice> choices;

    ItemKind(Set<AnswerChoice> choices) {
        this.choices = Set.copyOf(choices);
    }

    public boolean accepts(AnswerChoice choice) {
        return choices.contains(choice);
    }
}
