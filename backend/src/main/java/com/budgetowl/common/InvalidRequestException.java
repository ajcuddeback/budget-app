package com.budgetowl.common;

import java.util.Map;

/** Input a caller could fix, rejected past the point Bean Validation can see. */
public class InvalidRequestException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(errorCode, developerDetail, params);
    }
}
