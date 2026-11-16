package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Which credentials the application resolves from which environment variables (ADR-0030).
 *
 * <p>Two roles mean four placeholders in {@code application.yml}, arranged so that the pool serving
 * requests uses the restricted role while Flyway uses the owner — and so that an instance which has
 * not been reconfigured yet keeps starting. Every integration test in the suite overrides these
 * properties directly, so the placeholder chain itself is only exercised here. It is the difference
 * between "an upgrade keeps working" and "an upgrade fails to start", which is not a thing to find
 * out from a user.
 *
 * <p>No container and no Spring context: this resolves {@code application.yml} against a controlled
 * set of environment variables, which is exactly what the chain is made of.
 */
class DatabaseCredentialResolutionTest {

    @Test
    void anUnreconfiguredInstanceConnectsAsTheOwnerForBoth() {
        // Only DB_PASSWORD set — the first-run contract (nothing configured beyond a database
        // password) and what every existing instance looks like the moment it is upgraded.
        StandardEnvironment environment = environmentWith(Map.of("DB_PASSWORD", "owner-secret"));

        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("budgetowl");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("owner-secret");
        assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("budgetowl");
        assertThat(environment.getProperty("spring.flyway.password")).isEqualTo("owner-secret");
    }

    @Test
    void aReconfiguredInstanceSplitsTheTwoRoles() {
        StandardEnvironment environment =
                environmentWith(
                        Map.of(
                                "DB_USER", "owl_owner",
                                "DB_PASSWORD", "owner-secret",
                                "DB_APP_USER", "budgetowl_app",
                                "DB_APP_PASSWORD", "app-secret"));

        // The pool that serves requests: the role that cannot change the schema.
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("budgetowl_app");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("app-secret");
        // Flyway: the owner, and nothing else uses these.
        assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("owl_owner");
        assertThat(environment.getProperty("spring.flyway.password")).isEqualTo("owner-secret");
    }

    @Test
    void theApplicationCredentialsFallBackToTheOwnersOwnValues() {
        // A deployment with a renamed owner and no application role at all. The fallback has to
        // follow DB_USER rather than the literal default, or the app would try to log in as
        // "budgetowl" with a password that belongs to "owl_owner".
        StandardEnvironment environment =
                environmentWith(Map.of("DB_USER", "owl_owner", "DB_PASSWORD", "owner-secret"));

        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("owl_owner");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("owner-secret");
    }

    @Test
    void aMissingDatabasePasswordStillFailsLoudly() {
        // security-model.md: every secret is ${ENV_VAR} with no fallback default. Adding a second
        // credential must not accidentally introduce a silent empty-string default for the first.
        StandardEnvironment environment = environmentWith(Map.of());

        assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB_PASSWORD");
        assertThatThrownBy(() -> environment.getProperty("spring.flyway.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    private static StandardEnvironment environmentWith(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        // Stands in for the container's environment. First, because that is where it sits in a
        // real Spring Boot application too.
        environment.getPropertySources().addFirst(new MapPropertySource("environment", variables));
        for (PropertySource<?> source : applicationYaml()) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private static List<PropertySource<?>> applicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("application.yml is not on the test classpath", e);
        }
    }
}
