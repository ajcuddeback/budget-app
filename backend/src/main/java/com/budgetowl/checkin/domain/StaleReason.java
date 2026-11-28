package com.budgetowl.checkin.domain;

/**
 * Why one item of a draft can no longer be completed as answered (decision 9). The codes are part
 * of the API contract: the client renders a sentence from each.
 */
public enum StaleReason {
    SUBJECT_CHANGED("subject-changed"),
    SUBJECT_GONE("subject-gone"),
    TARGET_ARCHIVED("target-archived"),
    ALREADY_RECORDED("already-recorded"),
    BILL_INACTIVE("bill-inactive"),
    TRANSACTION_ATTACHED_ELSEWHERE("transaction-attached-elsewhere"),
    GOAL_ARCHIVED("goal-archived"),
    DEBT_PAID_OFF("debt-paid-off"),
    LIMIT_REACHED("limit-reached"),
    SURPLUS_CHANGED("surplus-changed"),
    BASIS_CHANGED("basis-changed");

    private final String code;

    StaleReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
