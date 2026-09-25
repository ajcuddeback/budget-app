package com.budgetowl.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The slice-1 proof: the application starts, Flyway migrates a real PostgreSQL, the schema
 * validates against JPA, health is reachable without credentials, and everything else is not.
 *
 * <p>Testcontainers, never H2 (ADR-0009) — a migration that passes against H2 and fails against
 * PostgreSQL has tested nothing, and this one uses a PostgreSQL extension.
 *
 * <p>Runs over real HTTP on a random port, so the security filter chain is exercised exactly as it
 * will be in production rather than in a servlet simulation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIT extends PostgresTestBase {

    @LocalServerPort private int port;
    @Autowired private DataSource dataSource;

    private RestTestClient client;

    @BeforeEach
    void bindToRunningServer() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void contextLoadsAgainstRealPostgres() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayHasRunTheBaselineMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String application =
                jdbc.queryForObject("SELECT application FROM schema_metadata", String.class);
        assertThat(application).isEqualTo("budget-owl");

        Integer applied =
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void healthIsReachableWithoutCredentials() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("UP"));
    }

    @Test
    void everythingElseIsDeniedByDefault() {
        // Non-negotiable #2. A skeleton that permits everything ships permitting everything.
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/anything").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthDoesNotLeakDetails() {
        // show-details: never — component health names internals (database URLs, disk paths).
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectBody(String.class)
                .value(
                        body ->
                                assertThat(body)
                                        .doesNotContain("components")
                                        .doesNotContain("diskSpace"));
    }
}
