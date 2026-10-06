package com.budgetowl.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The absolute cap on a session's life, which Spring Session has no notion of.
 *
 * <p>An idle timeout alone means a stolen cookie used every twenty minutes lasts forever. Both
 * timeouts are required by docs/architecture/security-model.md, and this is the half that is ours.
 */
class AbsoluteSessionTimeoutFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-26T20:00:00Z");
    private static final Duration CAP = Duration.ofHours(12);

    private final AbsoluteSessionTimeoutFilter filter =
            new AbsoluteSessionTimeoutFilter(CAP, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void leavesAYoungSessionAlone() throws Exception {
        MockHttpServletRequest request =
                requestWithSessionCreatedAt(NOW.minus(Duration.ofHours(11)));
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("ada", null));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession(false)).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void endsASessionThatHasOutlivedTheCapHoweverBusyItHasBeen() throws Exception {
        MockHttpServletRequest request =
                requestWithSessionCreatedAt(NOW.minus(Duration.ofHours(13)));
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("ada", null));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the request carries on unauthenticated, and is refused with an ordinary 401")
                .isNull();
    }

    @Test
    void doesNothingWhenThereIsNoSessionAtAll() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession(false))
                .as("a bearer request must not be given a session by a filter that only looks")
                .isNull();
    }

    private static MockHttpServletRequest requestWithSessionCreatedAt(Instant createdAt) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session =
                new MockHttpSession(request.getServletContext()) {
                    @Override
                    public long getCreationTime() {
                        return createdAt.toEpochMilli();
                    }
                };
        request.setSession(session);
        return request;
    }
}
