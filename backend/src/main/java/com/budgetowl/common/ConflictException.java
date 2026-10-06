package com.budgetowl.common;

/** The request is valid but the current state refuses it. */
public class ConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ConflictException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }
}
