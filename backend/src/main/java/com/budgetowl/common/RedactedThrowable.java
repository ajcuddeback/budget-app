package com.budgetowl.common;

import java.sql.SQLException;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A stand-in for a database failure that is safe to write to a log.
 *
 * <p><b>Why this class exists.</b> A PostgreSQL {@code CHECK} violation puts the <em>entire failing
 * row</em> in the error {@code DETAIL}. When {@code ck_users_password_hash_encoded} fires, that row
 * contains the rejected plaintext password; pgjdbc puts it in the exception message and Spring
 * carries it into {@code DataIntegrityViolationException.getMessage()}. So {@code log.error("...",
 * exception)} on a failure from {@code users} writes a user's password into a file that outlives
 * the request and leaves the box in a bug report (docs/memory/gotchas.md).
 *
 * <p>Documenting that is not enough — the safe and the unsafe call look identical. So every log
 * call in the error handler passes its throwable through {@link #of(Throwable)} first, and anything
 * with a SQL exception anywhere in its chain comes back as an instance of this class: class names
 * and the violated constraint's name, no messages, no cause, nothing to walk back to.
 */
public final class RedactedThrowable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private RedactedThrowable(String summary) {
        super(summary, null, false, false);
    }

    /**
     * @return the original throwable when it cannot be carrying database content, and a redacted
     *     summary when it can
     */
    public static Throwable of(Throwable original) {
        if (original == null || !carriesDatabaseContent(original)) {
            return original;
        }
        StringBuilder summary = new StringBuilder("redacted database failure: ");
        for (Throwable link = original; link != null; link = link.getCause()) {
            summary.append(link.getClass().getName());
            if (link.getCause() != null && link.getCause() != link) {
                summary.append(" <- ");
            }
        }
        violatedConstraint(original)
                .ifPresent(
                        constraint ->
                                summary.append(" [constraint=").append(constraint).append(']'));
        return new RedactedThrowable(summary.toString());
    }

    /**
     * The name of the violated constraint, and nothing else from the server's error message.
     *
     * <p>Hibernate's own extractor cannot supply this: it scrapes {@code "violates check
     * constraint"} out of the message text, and the last-owner rule arrives as a custom {@code
     * RAISE} from a constraint trigger with a message of its own. The name is what a caller may
     * branch on — {@code ck_households_at_least_one_owner} means "a household must keep an owner" —
     * and branching on message text would break the first time PostgreSQL rewords anything.
     */
    public static Optional<String> violatedConstraint(Throwable original) {
        for (Throwable link = original; link != null; link = link.getCause()) {
            if (link instanceof PSQLException psql) {
                ServerErrorMessage serverError = psql.getServerErrorMessage();
                return serverError == null
                        ? Optional.empty()
                        : Optional.ofNullable(serverError.getConstraint());
            }
            if (link.getCause() == link) {
                break;
            }
        }
        return Optional.empty();
    }

    private static boolean carriesDatabaseContent(Throwable original) {
        for (Throwable link = original; link != null; link = link.getCause()) {
            if (link instanceof SQLException || link instanceof DataIntegrityViolationException) {
                return true;
            }
            if (link.getCause() == link) {
                break;
            }
        }
        return false;
    }
}
