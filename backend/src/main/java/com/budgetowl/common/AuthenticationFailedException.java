package com.budgetowl.common;

/** Credentials were not accepted. Identical for an unknown email and a wrong password. */
public class AuthenticationFailedException extends DomainException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String developerDetail) {
        super(ErrorCode.AUTHENTICATION_FAILED, developerDetail);
    }
}
