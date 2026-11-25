package com.budgetowl.config;

import com.budgetowl.imports.domain.ImportLimits;
import com.budgetowl.imports.domain.ImportReader;
import com.budgetowl.imports.web.ImportBodyLimitFilter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wiring for file import (slice 12).
 *
 * <p>The body-limit filter is registered <b>ahead of Spring Security</b>, on the two routes that
 * take a file. An unauthenticated stranger sending a two-gigabyte body is refused by its size
 * before anything authenticates it, parses it or buffers more than the cap — and a {@code 413}
 * reveals nothing about the route that a {@code 401} would not.
 */
@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfig {

    @Bean
    ImportLimits importLimits(ImportProperties properties) {
        return properties.limits();
    }

    /** Pure and stateless; one instance serves every request. */
    @Bean
    ImportReader importReader(ImportLimits limits, Clock clock) {
        return new ImportReader(limits, clock, System::nanoTime);
    }

    @Bean
    FilterRegistrationBean<ImportBodyLimitFilter> importBodyLimitFilter(
            ImportLimits limits, ProblemResponseWriter problems) {
        FilterRegistrationBean<ImportBodyLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new ImportBodyLimitFilter(limits.maximumBodyBytes(), problems::write));
        registration.addUrlPatterns("/api/imports", "/api/imports/preview");
        // Before Spring Security's chain, which Boot registers at -100.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
