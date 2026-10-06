package com.budgetowl.common;

/**
 * Authenticated, and refused by role or membership. Never used for "not authenticated" — that is a
 * 401.
 */
public class ForbiddenException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }
}
