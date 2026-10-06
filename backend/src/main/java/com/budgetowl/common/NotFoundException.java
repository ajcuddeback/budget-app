package com.budgetowl.common;

/**
 * Missing, or belonging to somebody else — the caller cannot tell the difference, and that is the
 * point (ADR-0008).
 */
public class NotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String developerDetail) {
        super(ErrorCode.NOT_FOUND, developerDetail);
    }

    public NotFoundException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }
}
