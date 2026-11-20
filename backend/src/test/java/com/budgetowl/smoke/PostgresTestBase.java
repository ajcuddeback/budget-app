package com.budgetowl.smoke;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every integration test in the JVM (ADR-0009).
 *
 * <p>Started manually and never stopped: Ryuk reaps it when the JVM exits, and a container per test
 * class turns a fast suite into a slow one, which is how integration tests stop being run. The
 * image is pinned — "latest" makes the build depend on what Docker Hub published today.
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("budgetowl")
                    .withUsername("budgetowl")
                    .withPassword("test-only-not-a-secret");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway has its own credentials since ADR-0030, and application.yml resolves them from
        // ${DB_USER}/${DB_PASSWORD}, which no test environment sets. The container has ONE role, so
        // here they are the same credentials as above: these tests connect as the owner, which is
        // precisely why the restricted path needs ApplicationRoleRestrictionsIT to cover it.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
