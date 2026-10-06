package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * First run, and the endpoint a stranger reaches first.
 *
 * <p>The threat model's sentence for this one is <i>"an attacker finds an internet-facing
 * fresh-looking instance and POSTs {@code /api/setup/first-user} to become its administrator"</i>.
 * The interesting test is therefore not that setup works — it is that it works <b>once</b>,
 * including when two callers arrive at the same instant.
 */
class SetupIT extends ApiTestBase {

    @Test
    void reportsAFreshInstanceAsNotYetSetUp() {
        ApiResponse response = anonymous().get("/api/setup/status");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"setupComplete\":false");
        assertThat(response.body()).contains("\"passwordLoginEnabled\":true");
    }

    @Test
    void tellsAnUnauthenticatedVisitorNothingAboutTheHouseholdOrItsMembers() {
        seedHousehold();

        ApiResponse response = anonymous().get("/api/setup/status");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"setupComplete\":true");
        assertThat(response.body()).doesNotContain(OWNER_EMAIL).doesNotContain("Ada");
    }

    @Test
    void createsTheFirstUserAsOwnerOfANewHousehold() {
        ApiResponse response = createFirstUser();

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Location")).contains("/api/households/current");
        assertThat(response.stringField("email")).isEqualTo(OWNER_EMAIL);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT role FROM household_members", String.class))
                .isEqualTo("OWNER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT setup_completed_at IS NOT NULL FROM instance_settings",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void refusesASecondFirstUser() {
        createFirstUser();

        ApiResponse response = createFirstUser();

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("setup-already-complete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1L);
    }

    /**
     * Two callers, released at the same instant. Exactly one may win — and the loser must leave
     * nothing behind, because a half-created administrator is an account somebody else can finish.
     */
    @RepeatedTest(3)
    void exactlyOneOfTwoSimultaneousCallersBecomesTheAdministrator() throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        Callable<ApiResponse> attempt =
                () -> {
                    ready.await(10, TimeUnit.SECONDS);
                    return createFirstUser();
                };

        List<ApiResponse> outcomes;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<ApiResponse>> futures = pool.invokeAll(List.of(attempt, attempt));
            outcomes = List.of(futures.get(0).get(), futures.get(1).get());
        }

        assertThat(outcomes.stream().filter(response -> response.status() == 201).count())
                .as("exactly one caller may become the instance administrator")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(response -> response.status() == 409).count())
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM households", Long.class))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM household_members WHERE role = 'OWNER'",
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void refusesAMalformedRequestWithAProblemNamingTheField() {
        ApiResponse response =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"not-an-address","displayName":"Ada","password":"short",
                                 "householdName":"","baseCurrency":"pounds"}
                                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.header("Content-Type")).contains("application/problem+json");
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains("\"field\":\"email\"");
        assertThat(response.body()).contains("\"field\":\"baseCurrency\"");
    }

    @Test
    void refusesAnUnknownFieldRatherThanIgnoringIt() {
        ApiResponse response =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"%s",
                                 "householdName":"Home","baseCurrency":"GBP",
                                 "isInstanceAdmin":true}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("malformed-request");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }

    @Test
    void refusesAPasswordFromTheBundledBreachedList() {
        ApiResponse response =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"correcthorsebatterystaple",
                                 "householdName":"Home","baseCurrency":"GBP"}
                                """
                                        .formatted(OWNER_EMAIL));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("password-unacceptable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }
}
