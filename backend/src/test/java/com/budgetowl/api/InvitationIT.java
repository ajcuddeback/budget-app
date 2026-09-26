package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Invitations, which are the only way into a household once the first user exists — and therefore
 * the only remaining way in for an attacker who does not have a password.
 *
 * <p>The link is a credential. These tests are about what it can and cannot be made to do: after it
 * has been used, after it has been revoked, after it has expired, when it was never real, and when
 * somebody else is already signed in on the browser that opens it. All four failures have to look
 * the same, or the link's history is disclosed to whoever found it in a chat backup.
 */
class InvitationIT extends ApiTestBase {

    private static final String JOINER_EMAIL = "chris@example.com";
    private static final String JOINER_SECRET = "chris-example-passphrase";

    @Test
    void createsALinkOnlyTheOwnerCanCreate() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/households/current/invitations",
                                """
                                {"email":"%s","role":"MEMBER"}
                                """
                                        .formatted(JOINER_EMAIL));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.stringField("token")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(response.stringField("acceptPath")).startsWith("/join/");
        assertThat(response.body()).doesNotContain("tokenHash").doesNotContain("token_hash");
    }

    @Test
    void storesOnlyAHashOfTheToken() {
        Household household = seedHousehold();

        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");

        String stored =
                jdbc.queryForObject(
                        "SELECT token_hash FROM household_invitations WHERE email = CAST(? AS citext)",
                        String.class,
                        JOINER_EMAIL);
        assertThat(stored).matches("^[0-9a-f]{64}$").isNotEqualTo(token);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM household_invitations WHERE token_hash = ?",
                                Long.class,
                                token))
                .as("a database dump must not yield a working link")
                .isZero();
    }

    @Test
    void createsTheUserAndTheMembershipWhenTheInvitedPersonIsNew() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "VIEWER");

        ApiResponse response = acceptInvitation(token, "Chris", JOINER_SECRET);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("role")).isEqualTo("VIEWER");
        assertThat(response.body()).contains("\"userCreated\":true");
        assertThat(signIn(JOINER_EMAIL, JOINER_SECRET).get("/api/households/current").status())
                .isEqualTo(200);
    }

    @Test
    void addsAnExistingUserWithoutAskingForAPassword() {
        Household household = seedHousehold();
        acceptInvitation(invite(household.owner(), JOINER_EMAIL, "MEMBER"), "Chris", JOINER_SECRET);
        household.owner().delete("/api/households/current/members/" + membershipIdOf(JOINER_EMAIL));

        ApiResponse response =
                browser()
                        .post(
                                "/api/invitations/"
                                        + invite(household.owner(), JOINER_EMAIL, "VIEWER")
                                        + "/accept",
                                "{}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"userCreated\":false");
        assertThat(response.stringField("role")).isEqualTo("VIEWER");
    }

    @Test
    void refusesASecondAcceptanceOfTheSameLink() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");
        assertThat(acceptInvitation(token, "Chris", JOINER_SECRET).status()).isEqualTo(200);

        ApiResponse second = acceptInvitation(token, "Chris Again", JOINER_SECRET);

        assertThat(second.status()).isEqualTo(404);
        assertThat(second.stringField("code")).isEqualTo("invitation-unusable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household_members", Long.class))
                .isEqualTo(4L);
    }

    /**
     * Used, revoked, expired and never-existed are one answer. Anything else tells whoever holds
     * the link what happened to it — and "already accepted" in particular confirms that somebody
     * joined.
     */
    @Test
    void answersIdenticallyForUsedRevokedExpiredAndUnknownLinks() {
        Household household = seedHousehold();

        String used = invite(household.owner(), JOINER_EMAIL, "MEMBER");
        acceptInvitation(used, "Chris", JOINER_SECRET);

        String revoked = invite(household.owner(), "dana@example.com", "MEMBER");
        household
                .owner()
                .delete(
                        "/api/households/current/invitations/"
                                + invitationIdOf("dana@example.com"));

        String expired = invite(household.owner(), "erik@example.com", "MEMBER");
        // Both columns: ck_household_invitations_expires_after_creation means an invitation
        // cannot be made to expire before it was created, even by a test.
        jdbc.update(
                "UPDATE household_invitations"
                        + " SET created_at = now() - interval '9 days',"
                        + "     expires_at = now() - interval '2 days'"
                        + " WHERE email = CAST('erik@example.com' AS citext)");

        ApiResponse afterUse = acceptInvitation(used, "Anyone", JOINER_SECRET);
        ApiResponse afterRevoke = acceptInvitation(revoked, "Anyone", JOINER_SECRET);
        ApiResponse afterExpiry = acceptInvitation(expired, "Anyone", JOINER_SECRET);
        ApiResponse neverExisted =
                acceptInvitation("a-token-nobody-ever-issued", "Anyone", JOINER_SECRET);

        assertThat(afterUse.status()).isEqualTo(404);
        assertThat(afterRevoke.bodyWithoutCorrelationId())
                .isEqualTo(afterUse.bodyWithoutCorrelationId());
        assertThat(afterExpiry.bodyWithoutCorrelationId())
                .isEqualTo(afterUse.bodyWithoutCorrelationId());
        assertThat(neverExisted.bodyWithoutCorrelationId())
                .isEqualTo(afterUse.bodyWithoutCorrelationId());
    }

    @Test
    void refusesAcceptanceWhileSomebodyElseIsSignedIn() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");

        ApiResponse response =
                household
                        .member()
                        .post(
                                "/api/invitations/" + token + "/accept",
                                """
                                {"displayName":"Chris","password":"%s"}
                                """
                                        .formatted(JOINER_SECRET));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("invitation-requires-sign-out");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household_members", Long.class))
                .isEqualTo(3L);
    }

    @Test
    void refusesToInviteSomebodyWhoIsAlreadyAMemberWithoutConfirmingTheAddress() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/households/current/invitations",
                                """
                                {"email":"%s","role":"MEMBER"}
                                """
                                        .formatted(MEMBER_EMAIL));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("invitation-refused");
        assertThat(response.body())
                .as("the refusal must not confirm that this address is a member")
                .doesNotContain(MEMBER_EMAIL);
    }

    @Test
    void refusesANewUserWithNoDisplayNameOrPassword() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");

        ApiResponse response = browser().post("/api/invitations/" + token + "/accept", "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(3L);
    }

    @Test
    void revokesALinkBeforeItIsUsed() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");

        ApiResponse revoked =
                household
                        .owner()
                        .delete(
                                "/api/households/current/invitations/"
                                        + invitationIdOf(JOINER_EMAIL));

        assertThat(revoked.status()).isEqualTo(204);
        assertThat(acceptInvitation(token, "Chris", JOINER_SECRET).status()).isEqualTo(404);
    }

    @Test
    void refusesToRevokeAnInvitationThatWasAlreadyAccepted() {
        Household household = seedHousehold();
        String token = invite(household.owner(), JOINER_EMAIL, "MEMBER");
        acceptInvitation(token, "Chris", JOINER_SECRET);

        ApiResponse response =
                household
                        .owner()
                        .delete(
                                "/api/households/current/invitations/"
                                        + invitationIdOf(JOINER_EMAIL));

        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void answers404ForAnInvitationThatIsNotThisHouseholds() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .delete(
                                "/api/households/current/invitations/"
                                        + "00000000-0000-4000-8000-00000000beef");

        assertThat(response.status()).isEqualTo(404);
    }

    private String invitationIdOf(String email) {
        return jdbc.queryForObject(
                "SELECT id FROM household_invitations WHERE email = CAST(? AS citext)"
                        + " ORDER BY created_at DESC LIMIT 1",
                String.class,
                email);
    }
}
