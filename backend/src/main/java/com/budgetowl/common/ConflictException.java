package com.budgetowl.common;

import java.util.Map;

/** The request is valid but the current state refuses it. */
public class ConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String developerDetail) {
        super(ErrorCode.CONFLICT, developerDetail);
    }

    public ConflictException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }

    public ConflictException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(errorCode, developerDetail, params);
    }
}
