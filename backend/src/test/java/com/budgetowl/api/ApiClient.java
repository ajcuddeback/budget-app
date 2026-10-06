package com.budgetowl.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An HTTP client that behaves like a real caller: it keeps cookies, echoes the CSRF token, and
 * hands back the <b>raw response body as a string</b>.
 *
 * <p>Written by hand rather than using a fluent test client, for three reasons that matter to what
 * is being proved here:
 *
 * <ul>
 *   <li>The disclosure tests assert on the serialized JSON, not on a deserialized DTO. A client
 *       that maps the body into a type would hide exactly the leak they are looking for.
 *   <li>Cookie handling has to be explicit. A jar with its own policy might quietly decline to send
 *       a {@code Secure} cookie over the test's plain HTTP, and the session tests would pass for
 *       the wrong reason.
 *   <li>The CSRF tests need to send a wrong token, a missing token and a stale one deliberately.
 * </ul>
 */
public final class ApiClient {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    private String bearerToken;
    private boolean sendCsrfToken = true;
    private String forcedCsrfToken;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Switches this client to the mobile transport: a bearer token and no cookies. */
    public ApiClient withBearerToken(String token) {
        this.bearerToken = token;
        this.cookies.clear();
        return this;
    }

    /** Replays one cookie, which is what a stolen session cookie is. */
    public ApiClient withCookie(String name, String value) {
        this.cookies.put(name, value);
        return this;
    }

    /** Sends no CSRF header at all, whatever is in the jar. */
    public ApiClient withoutCsrfToken() {
        this.sendCsrfToken = false;
        return this;
    }

    /** Sends a CSRF header that does not match the cookie. */
    public ApiClient withCsrfToken(String token) {
        this.forcedCsrfToken = token;
        return this;
    }

    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public Map<String, String> cookies() {
        return Map.copyOf(cookies);
    }

    public ApiResponse get(String path) {
        return send("GET", path, null);
    }

    public ApiResponse post(String path, String jsonBody) {
        return send("POST", path, jsonBody);
    }

    public ApiResponse put(String path, String jsonBody) {
        return send("PUT", path, jsonBody);
    }

    public ApiResponse patch(String path, String jsonBody) {
        return send("PATCH", path, jsonBody);
    }

    public ApiResponse delete(String path) {
        return send("DELETE", path, null);
    }

    public ApiResponse send(String method, String path, String jsonBody) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        request.method(
                method,
                jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookieHeader());
        }
        csrfHeader().ifPresent(token -> request.header(CSRF_HEADER, token));

        try {
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            rememberCookies(response);
            return new ApiResponse(
                    response.statusCode(), response.body(), response.headers().map());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(method + " " + path + " failed", failure);
        }
    }

    private Optional<String> csrfHeader() {
        if (forcedCsrfToken != null) {
            return Optional.of(forcedCsrfToken);
        }
        return sendCsrfToken ? Optional.ofNullable(cookies.get(CSRF_COOKIE)) : Optional.empty();
    }

    private String cookieHeader() {
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private void rememberCookies(HttpResponse<String> response) {
        List<String> setCookies = response.headers().allValues("set-cookie");
        for (String header : setCookies) {
            String[] parts = header.split(";");
            int equals = parts[0].indexOf('=');
            if (equals < 0) {
                continue;
            }
            String name = parts[0].substring(0, equals).trim();
            String value = parts[0].substring(equals + 1).trim();
            if (value.isEmpty() || header.contains("Max-Age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
