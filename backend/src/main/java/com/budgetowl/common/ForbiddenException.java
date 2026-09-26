package com.budgetowl.common;

import java.util.Map;

/**
 * Authenticated, and refused by role or membership. Never used for "not authenticated" — that is a
 * 401.
 */
public class ForbiddenException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String developerDetail) {
        super(ErrorCode.FORBIDDEN, developerDetail);
    }

    public ForbiddenException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }

    public ForbiddenException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(errorCode, developerDetail, params);
    }
}
