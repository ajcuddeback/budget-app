package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.instance.service.MetaRateLimiter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/meta} — the one route the mobile app calls before anybody has signed in
 * (ADR-0042).
 *
 * <p>The threat is not that it does too little but that it says too much. A public endpoint that
 * grows a build number, a commit, a hostname or a user count tells anyone sweeping an address range
 * which bug this box has and that a household lives here. So the body is pinned <b>exactly</b>, as
 * a string, not as a set of fields that must be present: a fourth field fails here.
 */
class MetaIT extends ApiTestBase {

    private static final String EXACT_BODY =
            "{\"app\":\"budget-owl\",\"apiVersion\":1,\"minimumClientApiVersion\":1}";

    @Test
    void answersWithExactlyTheIdentityAndNothingElse() {
        ApiResponse response = anonymous().get("/api/meta");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(EXACT_BODY);
        assertThat(response.header("Content-Type")).get().asString().startsWith("application/json");
    }

    @Test
    void isNeverStoredByACacheOnTheWay() {
        ApiResponse response = anonymous().get("/api/meta");

        assertThat(response.header("Cache-Control")).contains("no-store");
    }

    @Test
    void saysTheSameThingOnAFreshInstanceAsOnOneInUse() {
        String fresh = anonymous().get("/api/meta").body();

        seedHousehold();
        String inUse = anonymous().get("/api/meta").body();

        assertThat(inUse)
                .as("whether anybody has set this instance up is not the meta route's to say")
                .isEqualTo(fresh)
                .isEqualTo(EXACT_BODY);
    }

    @Test
    void tellsASignedInCallerNothingMoreThanAStranger() {
        seedHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Phone · Android");

        ApiResponse response = phone.get("/api/meta");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(EXACT_BODY);
        assertThat(response.body()).doesNotContain(OWNER_EMAIL).doesNotContain("Ada");
    }

    @Test
    void namesNoServerSoftwareOrVersionInItsHeaders() {
        ApiResponse response = anonymous().get("/api/meta");

        for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
            for (String value : header.getValue()) {
                assertThat(value)
                        .as(header.getKey())
                        .doesNotContainIgnoringCase("tomcat")
                        .doesNotContainIgnoringCase("spring")
                        .doesNotContainIgnoringCase("java");
            }
        }
        assertThat(response.header("Server")).isEmpty();
        assertThat(response.header("X-Powered-By")).isEmpty();
    }

    @Test
    void leavesNoSessionBehind() {
        ApiResponse response = anonymous().get("/api/meta");

        assertThat(response.headerValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("BUDGETOWL_SESSION="));
    }

    @Test
    void isPublicOnlyForReading() {
        ApiResponse response = browser().post("/api/meta", "{}");

        assertThat(response.status())
                .as("only GET is public; anything else is an ordinary authenticated request")
                .isEqualTo(401);
    }

    @Test
    void isRateLimitedPerCallerLikeTheOtherPublicRoutes() {
        ApiClient caller = anonymous();
        for (int call = 0; call < MetaRateLimiter.PERMITTED_PER_WINDOW; call++) {
            assertThat(caller.get("/api/meta").status()).isEqualTo(200);
        }

        ApiResponse refused = caller.get("/api/meta");

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.stringField("code")).isEqualTo("rate-limited");
        assertThat(refused.header("Retry-After"))
                .get()
                .asString()
                .matches("[1-9][0-9]*")
                .as("never a permanent lock");
    }
}
