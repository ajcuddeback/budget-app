package com.budgetowl.common;

import java.util.Map;
import java.util.Objects;

/**
 * The base of every failure this application raises deliberately.
 *
 * <p>Carries an {@link ErrorCode} and its params so the {@code @RestControllerAdvice} can render an
 * RFC 7807 response without any controller building one (docs/guides/api-style.md).
 *
 * <p><b>The message is for developers.</b> It never contains a secret, an amount or a value a
 * caller submitted — an exception message ends up in a log line, and a log line outlives the
 * request and leaves the box in a bug report.
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final transient Map<String, Object> params;

    protected DomainException(ErrorCode errorCode, String developerDetail) {
        this(errorCode, developerDetail, Map.of());
    }

    protected DomainException(
            ErrorCode errorCode, String developerDetail, Map<String, Object> params) {
        super(developerDetail);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.params = Map.copyOf(Objects.requireNonNull(params, "params"));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> params() {
        return params;
    }
}
