package com.budgetowl.common.web;

import com.budgetowl.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the one error shape this API returns: RFC 7807 {@code application/problem+json}.
 *
 * <p>The {@code code} and its {@code params} are the contract — <b>the client renders the
 * sentence</b> (ADR-0023), so a translation ships without a backend release. {@code title} and
 * {@code detail} are English developer-facing fallbacks for logs and are never the string a user
 * sees.
 *
 * <p>A plain map rather than Spring's {@code ProblemDetail} for one reason: not every error is
 * produced by a controller. An unauthenticated request is refused by the filter chain, before any
 * {@code @RestControllerAdvice} exists to handle it, and that response has to be byte-identical in
 * shape to the ones the advice builds. Two renderers of "the same" object drift; one builder
 * cannot.
 *
 * <p>Nothing here ever carries a stack trace, a SQL fragment, a class name or a value the caller
 * submitted. The {@code correlationId} is what ties a user's report to the real error in the logs,
 * and it is the only thing that does.
 */
public final class ApiProblem {

    private static final String TYPE_PREFIX = "https://budgetapp.dev/errors/";

    private ApiProblem() {}

    public static String newCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Map<String, Object> of(
            ErrorCode errorCode,
            String instancePath,
            Map<String, Object> params,
            String correlationId) {
        Map<String, Object> problem =
                build(
                        errorCode.status(),
                        errorCode.code(),
                        errorCode.title(),
                        instancePath,
                        correlationId);
        if (!params.isEmpty()) {
            problem.put("params", params);
        }
        return problem;
    }

    public static Map<String, Object> ofFieldErrors(
            String instancePath, List<FieldProblem> errors, String correlationId) {
        Map<String, Object> problem =
                build(
                        ErrorCode.VALIDATION_FAILED.status(),
                        ErrorCode.VALIDATION_FAILED.code(),
                        ErrorCode.VALIDATION_FAILED.title(),
                        instancePath,
                        correlationId);
        problem.put("errors", errors);
        return problem;
    }

    public static Map<String, Object> build(
            int status, String code, String title, String instancePath, String correlationId) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", TYPE_PREFIX + code);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", title + ".");
        if (instancePath != null) {
            problem.put("instance", instancePath);
        }
        problem.put("code", code);
        problem.put("correlationId", correlationId);
        return problem;
    }

    /**
     * One invalid field. The message is a developer-facing fallback like the title; it names the
     * rule that failed and never echoes the value that failed it — a validation message that quotes
     * the input is how a password reaches a log.
     */
    public record FieldProblem(String field, String message) {}
}
