package com.budgetowl.checkin.domain;

/** Every answer a check-in item can be given. Which kind takes which is {@link ItemKind}'s. */
public enum AnswerChoice {
    MARK_PAID,
    SKIP,
    PREPAID,
    CATEGORY,
    ATTACH,
    NOT_A_BILL,
    /** An answer, not an absence of one: the item is left exactly as it is. */
    LEAVE,
    DEBT,
    GOAL,
    ROLL_OVER
}
