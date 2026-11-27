package com.budgetowl.common.web;

import com.budgetowl.common.DomainException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.RateLimitedException;
import com.budgetowl.common.RedactedThrowable;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error response in the application is built here (docs/guides/api-style.md). Controllers do
 * not build error responses, so there is one place where "what does the client see when this goes
 * wrong?" is answered — and one place to get the disclosure rules right.
 *
 * <p>Three rules this class exists to enforce:
 *
 * <ol>
 *   <li><b>No framework internals reach the client.</b> Not a stack trace, not a SQL fragment, not
 *       a class name, not the body the caller sent. The {@code correlationId} is the only thread
 *       back to the real error.
 *   <li><b>Nothing from a database failure reaches a log.</b> A PostgreSQL {@code CHECK} violation
 *       carries the whole rejected row in its {@code DETAIL} — for {@code users}, that is the
 *       plaintext password (docs/memory/gotchas.md). Every log call here passes through {@link
 *       RedactedThrowable#of}, which is a gate rather than a convention: the unsafe call is not
 *       available at any of these call sites, because {@link #log} takes what it is given and
 *       redacts it.
 *   <li><b>Errors are not an oracle.</b> The codes are coarse on purpose where a precise one would
 *       answer a question the caller has no right to ask — see {@link ErrorCode}.
 * </ol>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * The last-owner rule is a {@code DEFERRABLE INITIALLY DEFERRED} constraint trigger, so it
     * fires at {@code COMMIT} — after the service method has returned and its try/catch has gone.
     * This handler is the transaction boundary's outside, which is the only place it can be caught.
     */
    private static final String LAST_OWNER_CONSTRAINT = "ck_households_at_least_one_owner";

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> handleDomain(
            DomainException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(exception.errorCode(), correlationId, exception);
        Map<String, Object> problem =
                ApiProblem.of(
                        exception.errorCode(),
                        ApiProblem.instanceOf(request),
                        exception.params(),
                        correlationId);
        HttpHeaders headers = problemHeaders();
        if (exception instanceof RateLimitedException rateLimited) {
            headers.add(
                    HttpHeaders.RETRY_AFTER, Long.toString(rateLimited.retryAfter().toSeconds()));
        }
        return new ResponseEntity<>(problem, headers, exception.errorCode().status());
    }

    /**
     * A path variable or query parameter that will not convert — {@code /api/accounts/zzz}, {@code
     * ?size=abc}, {@code ?includeArchived=maybe}.
     *
     * <p>Without this it fell through to {@link #handleUnexpected} as a {@code 500}, and the
     * exception message carries the caller's raw string, so {@code ?size=<64 KB>} wrote 64 KB plus
     * a stack trace into the log at {@code ERROR} on every request. That is a cheap disk-fill on a
     * self-hoster's box, an audit-trail flood that buries the authentication events, and arbitrary
     * attacker-controlled text in the log stream for anything downstream that parses it. {@link
     * RedactedThrowable} does not help: it redacts only SQL-shaped throwables.
     *
     * <p>So: a {@code 400} naming the parameter, and {@code null} to {@code log} like every other
     * client error, which keeps the message out of the log entirely.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleUnconvertableParameter(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.VALIDATION_FAILED, correlationId, null);
        return problem(
                ApiProblem.ofFieldErrors(
                        ApiProblem.instanceOf(request),
                        List.of(
                                new ApiProblem.FieldProblem(
                                        exception.getName(), "must be a valid value")),
                        correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleInvalidBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiProblem.FieldProblem> errors =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        new ApiProblem.FieldProblem(
                                                error.getField(), error.getDefaultMessage()))
                        .toList();
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.VALIDATION_FAILED, correlationId, null);
        return problem(
                ApiProblem.ofFieldErrors(ApiProblem.instanceOf(request), errors, correlationId));
    }

    /**
     * A constrained handler parameter, or a {@code @Valid} body validated alongside one.
     *
     * <p><b>A body is expanded into its fields.</b> When a handler has a constrained parameter
     * <em>and</em> a {@code @Valid} body — {@code POST /api/invitations/{token}/accept} has both —
     * Spring validates the body inside method validation, and it arrives here as a single {@code
     * ParameterErrors} rather than as a {@code MethodArgumentNotValidException}. Mapped by
     * parameter, the response named the field {@code "body"} and carried only the first message,
     * where it had always named {@code "displayName"}. So a body's own field errors are unpacked,
     * exactly as {@link #handleInvalidBody} does: the field name and the message, and <b>never the
     * rejected value</b> — on that route the body holds a password.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> handleInvalidParameter(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<ApiProblem.FieldProblem> errors =
                exception.getParameterValidationResults().stream()
                        .flatMap(ApiExceptionHandler::fieldProblemsOf)
                        .toList();
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.VALIDATION_FAILED, correlationId, null);
        return problem(
                ApiProblem.ofFieldErrors(ApiProblem.instanceOf(request), errors, correlationId));
    }

    private static java.util.stream.Stream<ApiProblem.FieldProblem> fieldProblemsOf(
            org.springframework.validation.method.ParameterValidationResult result) {
        if (result instanceof org.springframework.validation.method.ParameterErrors body) {
            return body.getFieldErrors().stream()
                    .map(
                            error ->
                                    new ApiProblem.FieldProblem(
                                            error.getField(), error.getDefaultMessage()));
        }
        return java.util.stream.Stream.of(
                new ApiProblem.FieldProblem(
                        result.getMethodParameter().getParameterName(),
                        result.getResolvableErrors().isEmpty()
                                ? "is invalid"
                                : result.getResolvableErrors().get(0).getDefaultMessage()));
    }

    /**
     * A malformed or unparseable body. The exception's own message quotes the JSON it choked on —
     * which is the request body, which on these endpoints is a password. It is never included.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        if (BodyTooLargeException.isIn(exception)) {
            // A body stopped at the import cap while Jackson was reading it (file-import.md rule
            // 3): too large, not malformed — the same 413 nginx and the declared-length check give.
            log(ErrorCode.IMPORT_FILE_TOO_LARGE, correlationId, null);
            return problem(
                    ApiProblem.of(
                            ErrorCode.IMPORT_FILE_TOO_LARGE,
                            ApiProblem.instanceOf(request),
                            Map.of(),
                            correlationId));
        }
        log(ErrorCode.MALFORMED_REQUEST, correlationId, null);
        return problem(
                ApiProblem.of(
                        ErrorCode.MALFORMED_REQUEST,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    /**
     * Two people edited one row and the second one lost.
     *
     * <p>Caught here rather than in a service because Hibernate can only discover it at flush,
     * which is after the service method has returned and its try/catch has gone — the same reason
     * the last-owner rule is handled here. The loser is told; nothing is overwritten.
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> handleStaleVersion(
            org.springframework.dao.OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.STALE_VERSION, correlationId, null);
        return problem(
                ApiProblem.of(
                        ErrorCode.STALE_VERSION,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    /**
     * A row lock this request needed was not granted: another transaction held it past the lock
     * timeout, or PostgreSQL broke a deadlock by rolling this one back.
     *
     * <p>A {@code 409 record-busy}, never a {@code 500}: nothing was written — the transaction
     * rolled back whole — and the same request can be sent again. Debts take their row locks with a
     * short timeout for exactly this (ADR-0043, {@code DebtLocks}). Both the Spring and the JPA
     * forms are listed: a lock taken through the {@code EntityManager} directly, rather than a
     * repository, is not translated into Spring's.
     */
    @ExceptionHandler({
        org.springframework.dao.PessimisticLockingFailureException.class,
        jakarta.persistence.PessimisticLockException.class,
        jakarta.persistence.LockTimeoutException.class
    })
    ResponseEntity<Map<String, Object>> handleLockNotGranted(
            RuntimeException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.RECORD_BUSY, correlationId, null);
        // Which of the three it was — a lock timeout or a deadlock — tells an operator what to
        // look for. The class name only: the message can quote the SQL and its parameters.
        log.debug(
                "lock not granted correlationId={} exception={}",
                correlationId,
                exception.getClass().getName());
        return problem(
                ApiProblem.of(
                        ErrorCode.RECORD_BUSY,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    /**
     * The database refused the write.
     *
     * <p>The only thing read out of the exception is the violated constraint's <em>name</em>, and
     * the only branch taken on it is the last-owner rule — identified by name rather than by
     * message text, because a message is prose that PostgreSQL may reword and this is a contract.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handleIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        String constraint = RedactedThrowable.violatedConstraint(exception).orElse("");
        ErrorCode code =
                LAST_OWNER_CONSTRAINT.equals(constraint)
                        ? ErrorCode.LAST_OWNER
                        : ErrorCode.CONFLICT;
        String correlationId = ApiProblem.newCorrelationId();
        log(code, correlationId, exception);
        return problem(
                ApiProblem.of(code, ApiProblem.instanceOf(request), Map.of(), correlationId));
    }

    /** Reached when authorization is refused past the filter chain — method security, mostly. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.FORBIDDEN, correlationId, null);
        return problem(
                ApiProblem.of(
                        ErrorCode.FORBIDDEN,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, Object>> handleAuthentication(
            AuthenticationException exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        log(ErrorCode.NOT_AUTHENTICATED, correlationId, null);
        return problem(
                ApiProblem.of(
                        ErrorCode.NOT_AUTHENTICATED,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    /**
     * Everything else.
     *
     * <p>Spring MVC's own refusals — unknown path, wrong method, unsupported media type — already
     * know their status and implement {@link ErrorResponse}. They are reshaped into our envelope
     * rather than answered with the framework's, so a client never has to parse two error formats.
     * Anything that is not one of those is ours, is a bug, and is a 500 with a correlation id and
     * nothing else.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        String correlationId = ApiProblem.newCorrelationId();
        if (exception instanceof ErrorResponse refusal) {
            int status = refusal.getStatusCode().value();
            log.warn("request refused status={} correlationId={}", status, correlationId);
            return new ResponseEntity<>(
                    ApiProblem.build(
                            status,
                            "request-rejected",
                            "Request rejected",
                            ApiProblem.instanceOf(request),
                            correlationId),
                    problemHeaders(),
                    status(status));
        }
        log(ErrorCode.INTERNAL_ERROR, correlationId, exception);
        return problem(
                ApiProblem.of(
                        ErrorCode.INTERNAL_ERROR,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        correlationId));
    }

    private static ResponseEntity<Map<String, Object>> problem(Map<String, Object> problem) {
        return new ResponseEntity<>(
                problem, problemHeaders(), status((Integer) problem.get("status")));
    }

    private static HttpHeaders problemHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return headers;
    }

    private static org.springframework.http.HttpStatusCode status(int status) {
        return org.springframework.http.HttpStatusCode.valueOf(status);
    }

    /**
     * The only logging call site for a handled failure, and the reason it takes the throwable
     * rather than a message: everything is redacted on the way in, so no caller can choose not to.
     */
    private static void log(ErrorCode code, String correlationId, Throwable cause) {
        Throwable safe = RedactedThrowable.of(cause);
        if (code.status() >= 500) {
            log.error("request failed code={} correlationId={}", code.code(), correlationId, safe);
        } else if (safe == null) {
            log.warn("request refused code={} correlationId={}", code.code(), correlationId);
        } else {
            log.warn(
                    "request refused code={} correlationId={} cause={}",
                    code.code(),
                    correlationId,
                    safe.toString());
        }
    }
}
