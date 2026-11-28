package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * <b>No payee, category name, amount, weekly figure or basis ever reaches a log</b> (threat model,
 * attack 13), across the whole journey: start, answers, a stale refusal, a {@code CHECK} violation
 * written straight to the table (PostgreSQL puts the failing row in the error's {@code DETAIL}),
 * completion, and every read.
 *
 * <p>Captured at {@code DEBUG} with this application's own loggers at {@code DEBUG} too — what an
 * operator turns on when something is wrong — and again with Spring MVC at {@code TRACE}, the level
 * at which it prints whole request and response bodies, so the redacted {@code toString} on every
 * request, response and view record is the only thing between a household's week and the log.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CheckInDisclosureIT extends CheckInTestBase {

    private static final List<String> SENSITIVE =
            List.of(
                    "BLUE BOTTLE",
                    "Mountain Strength",
                    "COMCAST",
                    "WHOLE FOODS",
                    "UBER",
                    "Northwind",
                    "Emergency fund",
                    "Eating Out",
                    "Internet",
                    "487.0000",
                    "63.0000",
                    "480.0000",
                    "543.0000",
                    "550.0000",
                    "611.5700",
                    "94.10",
                    "81.40",
                    "126.00",
                    "71.99",
                    // Full-scale, so a nanosecond timing such as "54552620 ns" is not a match.
                    "2620.00",
                    "2403.00",
                    "550.1234");

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Logger ours = (Logger) LoggerFactory.getLogger("com.budgetowl");
    private final Logger springWeb = (Logger) LoggerFactory.getLogger("org.springframework.web");

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
        ours.setLevel(Level.INFO);
        springWeb.setLevel(null);
    }

    @Test
    void logs_nothing_sensitive_at_debug() {
        Canvas canvas = canvasWeek();
        capture(Level.DEBUG);

        String basis = walkThrough(canvas);

        List<String> logged = loggedLines();
        assertThat(logged)
                .as("the journey ran and DEBUG produced its own lines, so this proves something")
                .anyMatch(line -> line.contains("check-in answered"))
                .anyMatch(line -> line.contains("check-in completed"));
        assertNothingSensitive(logged, basis);
    }

    @Test
    void logs_nothing_sensitive_with_the_web_layer_traced() {
        Canvas canvas = canvasWeek();
        capture(Level.DEBUG);
        springWeb.setLevel(Level.TRACE);

        String basis = walkThrough(canvas);

        List<String> logged = loggedLines();
        assertThat(logged)
                .as("TRACE printed the bodies Spring MVC wrote, so this proves something")
                .anyMatch(line -> line.contains("WeekResponse["));
        assertNothingSensitive(logged, basis);
    }

    /** Every path that logs or renders. Returns a basis the server issued. */
    private String walkThrough(Canvas canvas) {
        ApiClient owner = canvas.household().owner();
        owner.get(W34);
        answerTheCanvas(canvas, owner);
        answer(
                owner,
                "alloc",
                "{\"choice\":\"GOAL\",\"goalId\":\"%s\",\"amount\":\"63.00\"}"
                        .formatted(canvas.id("fund")));
        JsonNode week = json(owner.get(W34));
        String basis = week.get("basis").asString();
        String body =
                "{\"version\":%d,\"basis\":\"%s\"}".formatted(week.get("version").asLong(), basis);

        // Refusals log through different code from a success.
        canvas.household()
                .viewer()
                .put(W34 + "/answers/cat." + canvas.id("ride"), "{\"choice\":\"LEAVE\"}");
        owner.put(W34 + "/answers/alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":\"5000.00\"}");
        owner.put(W34 + "/answers/alloc", "{\"choice\":\"ROLL_OVER\",\"amount\":63.00}");
        owner.put(W34 + "/answers/cat." + UUID.randomUUID(), "{\"choice\":\"LEAVE\"}");
        ok(
                owner.patch(
                        "/api/transactions/" + canvas.id("ride"),
                        "{\"note\":\"moved\",\"version\":0}"),
                200);
        assertThat(owner.post(W34 + "/complete", body).status()).isEqualTo(409);
        // The re-read and the re-answer the refusal asks for: the row moved, so it is asked again.
        JsonNode reread = json(owner.get(W34));
        long answerVersion =
                item(reread, "cat." + canvas.id("ride")).get("answer").get("version").asLong();
        answer(
                owner,
                "cat." + canvas.id("ride"),
                "{\"choice\":\"CATEGORY\",\"categoryId\":\"%s\",\"version\":%d}"
                        .formatted(seededCategoryId("Shopping"), answerVersion));

        // A CHECK violation, past the edge: the row's values are in PostgreSQL's DETAIL.
        UUID household = jdbc.queryForObject("SELECT id FROM households", UUID.class);
        assertThat(
                        catchThrowable(
                                () ->
                                        jdbc.update(
                                                """
                                                INSERT INTO check_ins (id, household_id, week_start,
                                                       status, window_start, week_target)
                                                VALUES (?, ?, DATE '2026-08-03', 'DRAFT',
                                                        DATE '2026-08-03', ?)
                                                """,
                                                UUID.randomUUID(),
                                                household,
                                                new java.math.BigDecimal("550.1234"))))
                .isNotNull();

        ok(complete(owner), 200);
        owner.get(W34);
        owner.get("/api/check-ins");
        owner.get("/api/payee-rules");
        owner.get("/api/charge-rules");
        owner.get("/api/bills?period=2026-08");
        return basis;
    }

    private void assertNothingSensitive(List<String> logged, String basis) {
        for (String sensitive : SENSITIVE) {
            assertThat(logged).as(sensitive).noneMatch(line -> line.contains(sensitive));
        }
        assertThat(logged).as("the basis is never logged").noneMatch(line -> line.contains(basis));
    }

    private void capture(Level level) {
        captured.start();
        root.setLevel(level);
        ours.setLevel(level);
        root.addAppender(captured);
    }

    private List<String> loggedLines() {
        List<ILoggingEvent> snapshot;
        synchronized (captured.list) {
            snapshot = new ArrayList<>(captured.list);
        }
        return snapshot.stream()
                .map(
                        event ->
                                event.getLoggerName()
                                        + " "
                                        + event.getFormattedMessage()
                                        + " "
                                        + (event.getThrowableProxy() == null
                                                ? ""
                                                : event.getThrowableProxy().getClassName()
                                                        + " "
                                                        + event.getThrowableProxy().getMessage()))
                .toList();
    }
}
