package com.budgetowl.config;

import com.budgetowl.auth.service.AuthTokenService;
import com.budgetowl.auth.web.AbsoluteSessionTimeoutFilter;
import com.budgetowl.auth.web.BearerTokenAuthenticationFilter;
import com.budgetowl.common.ErrorCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Deny by default (non-negotiable #2), two credential transports, one authorization (ADR-0018).
 *
 * <p><b>The public routes are the five below and nothing else.</b> Setup status, first user, login,
 * token issue, invitation acceptance — each is public because the caller cannot possibly be
 * authenticated yet, and each is rate-limited or single-claim in the service behind it. Adding a
 * sixth is a security change and needs to be reviewed as one.
 *
 * <p><b>CSRF is never disabled.</b> It is exempted for exactly two shapes, both of which carry no
 * ambient credential for a hostile page to ride on: a request authenticated by an {@code
 * Authorization: Bearer} header, and {@code POST /api/auth/token}, which is how a cookie-less
 * mobile client obtains one in the first place. Everything else — including login and invitation
 * acceptance, which browsers reach — requires the token. {@code SameSite=Lax} on the session cookie
 * is defence in depth behind that, not instead of it.
 *
 * <p>Roles are <b>not</b> configured here. {@code hasRole(...)} on a path would be a second
 * authorization system disagreeing with the service layer, and it cannot express the rules this
 * feature has anyway ("nobody may change their own role", "an owner or the member themselves").
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SecurityProperties.class, InvitationProperties.class})
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            SecurityProperties properties,
            AuthTokenService tokens,
            SecurityContextRepository securityContextRepository,
            ProblemResponseWriter problems,
            Clock clock)
            throws Exception {
        PathPatternRequestMatcher.Builder route = PathPatternRequestMatcher.withDefaults();

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Opt out of deferred loading, so the XSRF-TOKEN cookie is issued on the first safe
        // request rather than only once something happens to read the token. A client that cannot
        // get a token cannot make a state-changing request at all.
        csrfHandler.setCsrfRequestAttributeName(null);

        CookieCsrfTokenRepository csrfCookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfCookies.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));

        http.authorizeHttpRequests(
                        auth ->
                                auth
                                        // Liveness and readiness only. NOT the whole actuator
                                        // surface: /env and /configprops leak configuration, and
                                        // an unauthenticated /shutdown is exactly what it sounds
                                        // like.
                                        .requestMatchers(route.matcher("/actuator/health"))
                                        .permitAll()
                                        .requestMatchers(route.matcher("/actuator/health/**"))
                                        .permitAll()
                                        // The five public routes. A fresh instance has nobody to
                                        // authenticate as; a login cannot require being logged in;
                                        // and an invited person has no account until they accept.
                                        .requestMatchers(
                                                route.matcher(HttpMethod.GET, "/api/setup/status"))
                                        .permitAll()
                                        .requestMatchers(
                                                route.matcher(
                                                        HttpMethod.POST, "/api/setup/first-user"))
                                        .permitAll()
                                        .requestMatchers(
                                                route.matcher(HttpMethod.POST, "/api/auth/login"))
                                        .permitAll()
                                        .requestMatchers(
                                                route.matcher(HttpMethod.POST, "/api/auth/token"))
                                        .permitAll()
                                        .requestMatchers(
                                                route.matcher(
                                                        HttpMethod.POST,
                                                        "/api/invitations/{token}/accept"))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfCookies)
                                        .csrfTokenRequestHandler(csrfHandler)
                                        .ignoringRequestMatchers(csrfExempt(route)))
                .securityContext(
                        context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        // Session fixation: the id changes at login. Applied by
                                        // SessionLogin through the strategy bean below, and
                                        // declared here too so the chain agrees with it.
                                        .sessionFixation()
                                        .changeSessionId())
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none'; frame-ancestors"
                                                                        + " 'none'; base-uri 'none';"
                                                                        + " form-action 'none'"))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .frameOptions(frames -> frames.deny())
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31_536_000)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Without an explicit entry point Spring Security answers an unauthenticated
                // request with 403, because no login mechanism is configured to send the caller
                // to. This is an API: the correct answer is 401, and every endpoint's mandatory
                // first test asserts it (docs/guides/testing-style.md).
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                ErrorCode.NOT_AUTHENTICATED))
                                        .accessDeniedHandler(
                                                (request, response, denied) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                deniedCode(denied))))
                .addFilterBefore(
                        new BearerTokenAuthenticationFilter(tokens), AuthorizationFilter.class)
                .addFilterBefore(
                        new AbsoluteSessionTimeoutFilter(
                                properties.session().absoluteTimeout(), clock),
                        AuthorizationFilter.class);
        return http.build();
    }

    /**
     * BCrypt at cost 12 or more, behind a {@link DelegatingPasswordEncoder} so the algorithm can be
     * upgraded later without a flag day: every stored hash carries its own {@code {id}} prefix, and
     * the database refuses anything that does not ({@code ck_users_password_hash_encoded}).
     *
     * <p>PBKDF2 is registered for verification only. There is deliberately no {@code noop} encoder
     * in the map — a plaintext "hash" would be storable, and {@link
     * PasswordEncoderFactories#createDelegatingPasswordEncoder()} includes several algorithms whose
     * libraries we do not ship.
     */
    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties properties) {
        Map<String, PasswordEncoder> encoders =
                Map.of(
                        "bcrypt",
                        new BCryptPasswordEncoder(properties.bcryptStrength()),
                        "pbkdf2",
                        Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        return new DelegatingPasswordEncoder("bcrypt", encoders);
    }

    /** Sessions are stored in PostgreSQL by Spring Session; this is what reads and writes them. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Session fixation protection: {@code changeSessionId} at login, and a test that proves it. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    /**
     * The two CSRF exemptions, both of them requests with no ambient credential: a bearer-
     * authenticated call, and the endpoint a cookie-less client uses to get a bearer token.
     */
    private static RequestMatcher csrfExempt(PathPatternRequestMatcher.Builder route) {
        RequestMatcher tokenIssue = route.matcher(HttpMethod.POST, "/api/auth/token");
        return request ->
                BearerTokenAuthenticationFilter.isBearerRequest(request)
                        || tokenIssue.matches(request);
    }

    /**
     * A refused CSRF token and a refused role both arrive as {@code AccessDeniedException}, and a
     * client has to tell them apart: one means "retry with a token", the other means "you may not
     * do this at all".
     */
    private static ErrorCode deniedCode(AccessDeniedException denied) {
        return denied instanceof CsrfException
                ? ErrorCode.CSRF_TOKEN_REQUIRED
                : ErrorCode.FORBIDDEN;
    }
}
