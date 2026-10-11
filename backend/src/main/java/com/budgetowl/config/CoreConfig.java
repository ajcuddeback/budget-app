package com.budgetowl.config;

import com.budgetowl.common.web.MoneyJsonModule;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that are not about security but are needed everywhere. */
@Configuration
public class CoreConfig {

    /**
     * Amounts are strings on the wire, never JSON numbers (ADR-0006). Registered as a bean so it
     * applies to <em>every</em> {@code ObjectMapper} Spring Boot builds, rather than to the one
     * mapper somebody remembered to configure.
     */
    @Bean
    MoneyJsonModule moneyJsonModule() {
        return new MoneyJsonModule();
    }

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
