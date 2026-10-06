package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The household, its members, and the one rule that cannot be allowed to fail: a household always
 * has at least one {@code OWNER}.
 *
 * <p>The concurrent version of that rule is proved against the database in {@code LastOwnerRuleIT}
 * — it is a deferred constraint trigger, and no amount of care in a service can replace it. What is
 * proved here is the answer a caller gets: a 409 with a specific reason, which is one of the few
 * places in this API where a specific message is the right thing to say.
 */
class HouseholdIT extends ApiTestBase {

    @Test
    void showsTheHouseholdWithTheCallersOwnRole() {
        Household household = seedHousehold();

        ApiResponse response = household.member().get("/api/households/current");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("name")).isEqualTo("Ada and Grace");
        assertThat(response.stringField("baseCurrency")).isEqualTo("GBP");
        assertThat(response.stringField("role")).isEqualTo("MEMBER");
    }

    @Test
    void letsAnOwnerRenameTheHouseholdAndChangeItsBaseCurrency() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .put(
                                "/api/households/current",
                                "{\"name\":\"The Lovelace household\",\"baseCurrency\":\"EUR\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("name")).isEqualTo("The Lovelace household");
        assertThat(jdbc.queryForObject("SELECT base_currency FROM households", String.class))
                .isEqualTo("EUR");
    }

    @Test
    void refusesACurrencyThatIsNotACurrency() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .put(
                                "/api/households/current",
                                "{\"name\":\"Home\",\"baseCurrency\":\"ZZZ\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
    }

    @Test
    void listsTheMembersInACollectionEnvelope() {
        Household household = seedHousehold();

        ApiResponse response = household.member().get("/api/households/current/members");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"totalElements\":3").contains("\"page\":0");
        assertThat(response.body()).contains(OWNER_EMAIL).contains(VIEWER_EMAIL);
    }

    @Test
    void clampsAnOversizedPageRatherThanRefusingIt() {
        Household household = seedHousehold();

        ApiResponse response =
                household.member().get("/api/households/current/members?page=0&size=5000");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"size\":200");
    }

    @Test
    void letsAMemberSetTheirOwnDisplayCurrencyAndLocale() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .member()
                        .patch(
                                "/api/households/current/members/me",
                                "{\"displayCurrency\":\"USD\",\"locale\":\"en-GB\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("displayCurrency")).isEqualTo("USD");
        assertThat(response.stringField("locale")).isEqualTo("en-GB");
        assertThat(jdbc.queryForObject("SELECT base_currency FROM households", String.class))
                .as("a display preference never changes what is recorded (ADR-0022)")
                .isEqualTo("GBP");
    }

    @Test
    void resetsAMembersPreferencesToTheHouseholdDefault() {
        Household household = seedHousehold();
        household
                .member()
                .patch(
                        "/api/households/current/members/me",
                        "{\"displayCurrency\":\"USD\",\"locale\":\"en-GB\"}");

        ApiResponse response =
                household
                        .member()
                        .patch(
                                "/api/households/current/members/me",
                                "{\"displayCurrency\":null,\"locale\":null}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"displayCurrency\":null");
    }

    // --------------------------------------------------------------------------- last owner

    @Test
    void refusesToLetTheLastOwnerLeave() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .delete("/api/households/current/members/" + membershipIdOf(OWNER_EMAIL));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("last-owner");
        assertThat(household.owner().get("/api/households/current").status()).isEqualTo(200);
    }

    @Test
    void letsAnOwnerLeaveOnceThereIsAnotherOne() {
        Household household = seedHousehold();
        household
                .owner()
                .patch(
                        "/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL),
                        "{\"role\":\"OWNER\"}");

        ApiResponse response =
                household
                        .owner()
                        .delete("/api/households/current/members/" + membershipIdOf(OWNER_EMAIL));

        assertThat(response.status()).isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM household_members WHERE role = 'OWNER'",
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void letsAMemberLeaveOfTheirOwnAccord() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .member()
                        .delete("/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL));

        assertThat(response.status()).isEqualTo(204);
        assertThat(household.member().get("/api/households/current").status())
                .as("leaving revokes access on the very next request")
                .isEqualTo(401);
    }

    /**
     * Removal revokes access and <b>never</b> deletes the household's financial data — that data
     * belongs to the household, not the person (ADR-0017). There is no financial data yet, so what
     * is asserted here is the half that exists: the user survives, the membership does not.
     */
    @Test
    void removesAMembersAccessWithoutRemovingTheirUser() {
        Household household = seedHousehold();
        ApiClient phone = mobile(MEMBER_EMAIL, MEMBER_SECRET, "Grace's phone");

        ApiResponse response =
                household
                        .owner()
                        .delete("/api/households/current/members/" + membershipIdOf(MEMBER_EMAIL));

        assertThat(response.status()).isEqualTo(204);
        assertThat(phone.get("/api/households/current").status())
                .as("a removed member keeps using a mobile token unless revocation is immediate")
                .isEqualTo(401);
        assertThat(household.member().get("/api/households/current").status()).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE email = CAST(? AS citext)",
                                Long.class,
                                MEMBER_EMAIL))
                .isEqualTo(1L);
    }

    @Test
    void refusesToRemoveAMemberOfNoHousehold() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .delete(
                                "/api/households/current/members/"
                                        + "00000000-0000-4000-8000-00000000beef");

        assertThat(response.status()).isEqualTo(404);
    }
}
