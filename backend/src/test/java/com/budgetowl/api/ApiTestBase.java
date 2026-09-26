package com.budgetowl.api;

import com.budgetowl.smoke.PostgresTestBase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The application, over real HTTP, against a real PostgreSQL.
 *
 * <p>Deliberately not {@code MockMvc}: every control this feature depends on lives in the filter
 * chain — the entry point that turns "not authenticated" into {@code 401}, CSRF, the bearer filter,
 * the session cookie's attributes. A servlet simulation can be configured to pass while the real
 * chain fails, and this is the one feature where that would matter most.
 *
 * <p>Two properties are overridden, both to make the suite runnable rather than to make it pass:
 * BCrypt's cost, because a 250ms hash per login turns this into a suite nobody runs; and the
 * session cookie's {@code Secure} flag, because the tests speak plain HTTP. {@code SessionCookieIT}
 * asserts the shipped default is the opposite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "budgetowl.security.bcrypt-strength=4",
            "server.servlet.session.cookie.secure=false"
        })
public abstract class ApiTestBase extends PostgresTestBase {

    protected static final String OWNER_EMAIL = "ada@example.com";
    protected static final String MEMBER_EMAIL = "grace@example.com";
    protected static final String VIEWER_EMAIL = "alan@example.com";
    protected static final String OUTSIDER_EMAIL = "edsger@example.com";

    /** Long enough for the policy, and containing "example" so no secret scanner has to guess. */
    protected static final String OWNER_SECRET = "ada-example-passphrase";

    protected static final String MEMBER_SECRET = "grace-example-passphrase";
    protected static final String VIEWER_SECRET = "alan-example-passphrase";

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                TRUNCATE household_invitations, auth_tokens, household_members, households, users,
                         spring_session_attributes, spring_session
                RESTART IDENTITY CASCADE
                """);
        jdbc.update(
                """
                UPDATE instance_settings
                   SET setup_completed_at = NULL,
                       registration_open = false,
                       password_login_enabled = true,
                       oidc_enabled = false,
                       oidc_provisioning_enabled = false,
                       oidc_owner_login_at = NULL
                 WHERE id = 1
                """);
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** A caller with no credentials of any kind. */
    protected ApiClient anonymous() {
        return new ApiClient(baseUrl());
    }

    /** A caller that has fetched a CSRF token, as a browser would before its first POST. */
    protected ApiClient browser() {
        ApiClient client = anonymous();
        client.get("/api/setup/status");
        return client;
    }

    // ----------------------------------------------------------------------------- fixtures

    /**
     * The household every authorization test needs: an owner, a member and a viewer, each created
     * the way a real one would be — setup for the first, invitations for the other two.
     */
    protected Household seedHousehold() {
        createFirstUser();
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        acceptInvitation(invite(owner, MEMBER_EMAIL, "MEMBER"), "Grace", MEMBER_SECRET);
        acceptInvitation(invite(owner, VIEWER_EMAIL, "VIEWER"), "Alan", VIEWER_SECRET);
        return new Household(
                owner, signIn(MEMBER_EMAIL, MEMBER_SECRET), signIn(VIEWER_EMAIL, VIEWER_SECRET));
    }

    protected ApiResponse createFirstUser() {
        return browser()
                .post(
                        "/api/setup/first-user",
                        """
                        {"email":"%s","displayName":"Ada","password":"%s",
                         "householdName":"Ada and Grace","baseCurrency":"GBP"}
                        """
                                .formatted(OWNER_EMAIL, OWNER_SECRET));
    }

    protected ApiClient signIn(String email, String secret) {
        ApiClient client = browser();
        ApiResponse response =
                client.post(
                        "/api/auth/login",
                        """
                        {"email":"%s","password":"%s"}
                        """
                                .formatted(email, secret));
        if (response.status() != 200) {
            throw new AssertionError("could not sign " + email + " in: " + response.body());
        }
        return client;
    }

    /**
     * @return the bearer token, which exists only in this response
     */
    protected String issueToken(String email, String secret, String deviceLabel) {
        ApiResponse response =
                anonymous()
                        .post(
                                "/api/auth/token",
                                """
                                {"email":"%s","password":"%s","deviceLabel":"%s"}
                                """
                                        .formatted(email, secret, deviceLabel));
        if (response.status() != 201) {
            throw new AssertionError("could not issue a token: " + response.body());
        }
        return response.stringField("token");
    }

    protected ApiClient mobile(String email, String secret, String deviceLabel) {
        return anonymous().withBearerToken(issueToken(email, secret, deviceLabel));
    }

    /**
     * @return the invitation token, which exists only in this response
     */
    protected String invite(ApiClient owner, String email, String role) {
        ApiResponse response =
                owner.post(
                        "/api/households/current/invitations",
                        """
                        {"email":"%s","role":"%s"}
                        """
                                .formatted(email, role));
        if (response.status() != 201) {
            throw new AssertionError("could not invite " + email + ": " + response.body());
        }
        return response.stringField("token");
    }

    protected ApiResponse acceptInvitation(String token, String displayName, String secret) {
        return browser()
                .post(
                        "/api/invitations/" + token + "/accept",
                        """
                        {"displayName":"%s","password":"%s"}
                        """
                                .formatted(displayName, secret));
    }

    protected UUID membershipIdOf(String email) {
        return jdbc.queryForObject(
                """
                SELECT m.id FROM household_members m JOIN users u ON u.id = m.user_id
                 WHERE u.email = CAST(? AS citext)
                """,
                UUID.class,
                email);
    }

    protected UUID userIdOf(String email) {
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE email = CAST(? AS citext)", UUID.class, email);
    }

    /** The three roles, each signed in. */
    protected record Household(ApiClient owner, ApiClient member, ApiClient viewer) {}
}
