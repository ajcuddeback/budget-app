package com.budgetowl.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A response, kept as text.
 *
 * <p>{@link #body()} is the bytes the client received. The disclosure tests search that string for
 * a token or a hash rather than deserializing it, because a DTO that cannot hold a secret proves
 * nothing about the JSON a misconfigured serializer might produce.
 */
public record ApiResponse(int status, String body, Map<String, List<String>> headers) {

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    public List<String> headerValues(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    /**
     * Pulls one string field out of the body without a JSON parser and without a DTO, so a test
     * that reads a value and a test that asserts a value is absent are looking at the same text.
     */
    public String stringField(String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no string field '" + name + "' in: " + body);
        }
        return matcher.group(1);
    }

    /**
     * The body with its correlation id blanked.
     *
     * <p>Every problem response carries a fresh correlation id, so two failures are never
     * byte-identical. Blanking it is what lets "an unknown email and a wrong password return the
     * same body" be asserted on everything that could actually distinguish them.
     */
    public String bodyWithoutCorrelationId() {
        return body.replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "\"correlationId\":\"\"");
    }
}
