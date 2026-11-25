package com.budgetowl.common.web;

import java.io.IOException;

/**
 * A request body read past its cap. Thrown by the counting stream file import wraps its bodies in,
 * from inside whatever is reading — Jackson — so it arrives wrapped in a {@code
 * HttpMessageNotReadableException}, and {@link ApiExceptionHandler} answers {@code 413} rather than
 * {@code 400 malformed-request}.
 *
 * <p>Carries no message beyond a fixed sentence and no cause: it is raised in the middle of an
 * upload, and nothing about the upload belongs in a log line.
 */
public final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public BodyTooLargeException() {
        super("request body over its cap", null);
    }

    /** Whether this was the reason a body could not be read, anywhere in the cause chain. */
    static boolean isIn(Throwable thrown) {
        for (Throwable link = thrown; link != null; link = link.getCause()) {
            if (link instanceof BodyTooLargeException) {
                return true;
            }
            if (link.getCause() == link) {
                break;
            }
        }
        return false;
    }
}
