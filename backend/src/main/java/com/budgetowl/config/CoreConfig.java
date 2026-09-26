package com.budgetowl.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that are not about security but are needed everywhere. */
@Configuration
public class CoreConfig {

    /**
     * Injected rather than called statically, so a test can fix time instead of waiting for it —
     * and so nothing in production calls {@code Instant.now()} at a point a test cannot reach
     * (docs/guides/testing-style.md).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
