package com.budgetowl.common;

import java.util.Map;

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

    public NotFoundException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(errorCode, developerDetail, params);
    }
}
