package com.budgetowl.config;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an RFC 7807 body for the two refusals that happen <b>inside the filter chain</b>, before
 * any controller — and therefore before {@code ApiExceptionHandler} can see them: not
 * authenticated, and access denied (which includes a missing or invalid CSRF token).
 *
 * <p>Without this, those two are the only responses in the API with an empty body and no
 * correlation id, which is exactly the pair a client has to handle most often.
 */
@Component
public class ProblemResponseWriter {

    private final ObjectMapper json;

    public ProblemResponseWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        Map<String, Object> problem =
                ApiProblem.of(
                        errorCode,
                        ApiProblem.instanceOf(request),
                        Map.of(),
                        ApiProblem.newCorrelationId());
        response.setStatus(errorCode.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
