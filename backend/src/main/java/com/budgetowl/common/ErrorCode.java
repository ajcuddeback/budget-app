package com.budgetowl.common;

/**
 * The registry of error codes. <b>A code is part of the API contract</b>
 * (docs/guides/api-style.md): the client renders the sentence from the code and its params, so
 * renaming one is a breaking change and shipping a translation must not need a backend release.
 *
 * <p>{@code title} is an English, developer-facing fallback for logs — never the string a user
 * sees.
 *
 * <p>Several codes are deliberately vague. {@link #AUTHENTICATION_FAILED} covers an unknown email
 * and a wrong password because telling them apart is a user-enumeration oracle, and {@link
 * #INVITATION_UNUSABLE} covers expired, revoked, already-used and never-existed because otherwise a
 * link's history is disclosed to whoever holds it.
 */
public enum ErrorCode {
    VALIDATION_FAILED("validation-failed", 400, "Validation failed"),
    MALFORMED_REQUEST("malformed-request", 400, "Malformed request"),
    PASSWORD_UNACCEPTABLE("password-unacceptable", 400, "Password unacceptable"),

    /** Unknown email and wrong password are the same answer, on purpose. */
    AUTHENTICATION_FAILED("authentication-failed", 401, "Authentication failed"),
    NOT_AUTHENTICATED("not-authenticated", 401, "Not authenticated"),

    FORBIDDEN("forbidden", 403, "Forbidden"),
    NOT_A_MEMBER("not-a-member", 403, "Not a member of this household"),
    OWNER_ONLY("owner-only", 403, "Only an owner may do this"),
    READ_ONLY_ROLE("read-only-role", 403, "This role may not write"),
    OWN_ROLE_UNCHANGEABLE("own-role-unchangeable", 403, "Nobody may change their own role"),
    CSRF_TOKEN_REQUIRED("csrf-token-required", 403, "CSRF token missing or invalid"),
    CURRENT_PASSWORD_INCORRECT("current-password-incorrect", 403, "Current password incorrect"),

    NOT_FOUND("not-found", 404, "Not found"),
    /** Expired, revoked, already used, or never existed — one answer for all four. */
    INVITATION_UNUSABLE("invitation-unusable", 404, "This invitation cannot be used"),

    SETUP_ALREADY_COMPLETE("setup-already-complete", 409, "Setup has already been completed"),
    INVITATION_REFUSED("invitation-refused", 409, "This invitation cannot be created"),
    INVITATION_REQUIRES_SIGN_OUT(
            "invitation-requires-sign-out", 409, "Sign out before accepting an invitation"),
    LAST_OWNER("last-owner", 409, "A household must keep at least one owner"),
    CONFLICT("conflict", 409, "Conflict"),

    RATE_LIMITED("rate-limited", 429, "Too many attempts"),
    INTERNAL_ERROR("internal-error", 500, "Internal error");

    private final String code;
    private final int status;
    private final String title;

    ErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
