package com.budgetowl.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The rule this class exists to enforce, tested directly: <b>a database failure never reaches a log
 * or a response</b>.
 *
 * <p>A PostgreSQL {@code CHECK} violation puts the entire rejected row in the error {@code DETAIL},
 * so when {@code ck_users_password_hash_encoded} fires the exception message contains the plaintext
 * password that was refused. The sentinel below stands in for it. If someone changes the handler to
 * log the throwable directly — the obvious, natural thing to write — this fails.
 */
class ApiExceptionHandlerTest {

    private static final String REJECTED_PASSWORD = "SENTINEL-PLAINTEXT-FROM-THE-FAILING-ROW";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLogs() {
        captured.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
    }

    @Test
    void neverLogsTheRejectedRowFromACheckViolation() {
        DataIntegrityViolationException violation =
                checkViolation("ck_users_password_hash_encoded");

        handler.handleIntegrityViolation(violation, request("/api/setup/first-user"));

        assertThat(everythingLogged())
                .as("the DETAIL line of a CHECK violation contains the row that was refused")
                .doesNotContain(REJECTED_PASSWORD);
        assertThat(everythingLogged()).contains("ck_users_password_hash_encoded");
    }

    @Test
    void neverReturnsTheRejectedRowToTheCaller() {
        DataIntegrityViolationException violation =
                checkViolation("ck_users_password_hash_encoded");

        ResponseEntity<Map<String, Object>> response =
                handler.handleIntegrityViolation(violation, request("/api/setup/first-user"));

        assertThat(response.getBody().toString()).doesNotContain(REJECTED_PASSWORD);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "conflict");
    }

    /**
     * The last-owner rule arrives here and nowhere else: it is a deferred constraint trigger, so it
     * fires at {@code COMMIT}, after the service method and its try/catch have both returned.
     */
    @Test
    void recognisesTheLastOwnerRuleByItsConstraintName() {
        DataIntegrityViolationException violation =
                checkViolation("ck_households_at_least_one_owner");

        ResponseEntity<Map<String, Object>> response =
                handler.handleIntegrityViolation(
                        violation, request("/api/households/current/members/1"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "last-owner");
    }

    @Test
    void doesNotBranchOnTheMessageText() {
        // Same wording, no constraint name: a message is prose PostgreSQL may reword, so the
        // handler must not be reading it.
        DataIntegrityViolationException violation = checkViolation(null);

        ResponseEntity<Map<String, Object>> response =
                handler.handleIntegrityViolation(violation, request("/api/households/current"));

        assertThat(response.getBody()).containsEntry("code", "conflict");
    }

    @Test
    void answersEveryProblemAsProblemJson() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleIntegrityViolation(
                        checkViolation("whatever"), request("/api/households/current"));

        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody())
                .containsKeys("type", "title", "status", "code", "correlationId");
    }

    @Test
    void redactsPathSegmentsThatLookLikeCredentials() {
        String redacted =
                ApiProblem.redactOpaqueSegments(
                        "/api/invitations/e--0_IKqh0y9VF5p7TlI-ZiCAhWkIuS8guIErELvHsk/accept");

        assertThat(redacted).isEqualTo("/api/invitations/{redacted}/accept");
    }

    @Test
    void leavesIdsAloneBecauseAnIdIsNotACredential() {
        String path = "/api/households/current/members/3f2504e0-4f89-41d3-9a0c-0305e82c3301";

        assertThat(ApiProblem.redactOpaqueSegments(path)).isEqualTo(path);
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    /** Shaped like the real thing, DETAIL line and all. */
    private static DataIntegrityViolationException checkViolation(String constraint) {
        ServerErrorMessage serverError =
                new ServerErrorMessage(
                        String.join(
                                "\u0000",
                                "SERROR",
                                "C23514",
                                "Mnew row for relation \"users\" violates check constraint",
                                "DFailing row contains (ada@example.com, "
                                        + REJECTED_PASSWORD
                                        + ").",
                                constraint == null ? "Rnothing" : "n" + constraint,
                                ""));
        SQLException psql = new PSQLException(serverError, true);
        return new DataIntegrityViolationException(
                "could not execute statement [" + serverError.getMessage() + "]", psql);
    }

    private String everythingLogged() {
        return captured.list.stream()
                .map(
                        event ->
                                event.getFormattedMessage()
                                        + " "
                                        + String.valueOf(event.getThrowableProxy()))
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
