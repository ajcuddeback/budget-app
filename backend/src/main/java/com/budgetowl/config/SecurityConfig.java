package com.budgetowl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Deny by default (non-negotiable #2). Every endpoint is authenticated unless it appears in the
 * explicit, reviewed exception list below — adding to that list is a security change.
 *
 * <p>Authentication itself arrives in slice 2. Until then there is nothing to authenticate with,
 * which is exactly why the default must already be "deny": a skeleton that permits everything and
 * is tightened later is a skeleton that ships permitting everything.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder mvc = PathPatternRequestMatcher.withDefaults();
        http.authorizeHttpRequests(
                        auth ->
                                auth
                                        // Liveness and readiness only. NOT the whole actuator
                                        // surface: /env and /configprops leak configuration, and
                                        // an unauthenticated /shutdown is exactly what it sounds
                                        // like.
                                        .requestMatchers(mvc.matcher("/actuator/health"))
                                        .permitAll()
                                        .requestMatchers(mvc.matcher("/actuator/health/**"))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // No session cookie is issued yet, so there is no CSRF token to carry. Slice 2
                // turns this on with the session transport (ADR-0018); bearer tokens do not need
                // it.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Without an explicit entry point Spring Security answers an unauthenticated
                // request with 403, because no login mechanism is configured to send the caller
                // to. This is an API: the correct answer is 401, and every endpoint's mandatory
                // first test asserts it (docs/guides/testing-style.md).
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
