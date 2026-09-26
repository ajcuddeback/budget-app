package com.budgetowl.common;

import java.util.Map;

/** Credentials were not accepted. Identical for an unknown email and a wrong password. */
public class AuthenticationFailedException extends DomainException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String developerDetail) {
        super(ErrorCode.AUTHENTICATION_FAILED, developerDetail);
    }

    public AuthenticationFailedException(ErrorCode errorCode, String developerDetail) {
        super(errorCode, developerDetail);
    }

    public AuthenticationFailedException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(errorCode, developerDetail, params);
    }
}
