package com.budgetowl.config;

import com.budgetowl.household.service.MembershipService;
import com.budgetowl.imports.service.ImportThrottle;
import com.budgetowl.imports.web.ImportAdmissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Admits the two routes that take a file — role, rate limit, concurrency gate — before their body
 * is read ({@link ImportAdmissionInterceptor}). Exactly those two paths: the history, undo and
 * mapping routes take no file and need no permit.
 */
@Configuration
public class ImportWebConfig implements WebMvcConfigurer {

    private final MembershipService memberships;
    private final ImportThrottle throttle;

    public ImportWebConfig(MembershipService memberships, ImportThrottle throttle) {
        this.memberships = memberships;
        this.throttle = throttle;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImportAdmissionInterceptor(memberships, throttle))
                .addPathPatterns("/api/imports", "/api/imports/preview");
    }
}
