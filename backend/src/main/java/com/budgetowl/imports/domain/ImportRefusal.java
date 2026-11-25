package com.budgetowl.imports.domain;

import com.budgetowl.common.DomainException;
import com.budgetowl.common.ErrorCode;
import java.util.Map;
import java.util.Objects;

/**
 * The whole file is refused.
 *
 * <p><b>The message is fixed text and the params are a fixed vocabulary.</b> Nothing from the file
 * — no cell, no tag, no position, no parser exception's message — is ever carried here, because
 * this exception's {@code toString} reaches the log through {@code ApiExceptionHandler} and its
 * params reach the response. A caught parser exception is <em>replaced</em> by one of these, never
 * wrapped: a cause would carry its message along with it.
 */
public final class ImportRefusal extends DomainException {

    private static final long serialVersionUID = 1L;

    private ImportRefusal(ErrorCode code, Map<String, Object> params) {
        super(code, "import refused: " + code.code(), params);
    }

    /** {@code 400 import-file-malformed}, with the reason from the fixed list. */
    public static ImportRefusal malformed(MalformedReason reason) {
        return new ImportRefusal(
                ErrorCode.IMPORT_FILE_MALFORMED,
                Map.of("reason", Objects.requireNonNull(reason).code()));
    }

    /** Any other file-level refusal, which needs no reason beyond its code. */
    public static ImportRefusal of(ErrorCode code) {
        return new ImportRefusal(Objects.requireNonNull(code), Map.of());
    }

    /** A file-level refusal naming the request field the person can change to fix it. */
    public static ImportRefusal of(ErrorCode code, String field) {
        return new ImportRefusal(Objects.requireNonNull(code), Map.of("field", field));
    }

    /** No stack: a refusal is an answer, not a bug, and the frames say nothing useful. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
