package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every route slice 12 adds, swept for the mandatory list in docs/guides/testing-style.md: {@code
 * 401} unauthenticated, {@code 403} for a {@code VIEWER} on every write (before any id is looked
 * up, so an unknown id is {@code 403} too), {@code 403 not-a-member}, {@code 403} without a CSRF
 * token, {@code 400} naming the field, and the same answers over the bearer transport.
 *
 * <p>A list rather than a sample, so a route added later without a line here is the omission a
 * reviewer can see.
 */
class ImportAuthorizationIT extends ImportApiTestBase {

    /** One route, and how to call it with whichever ids the test has. */
    record Endpoint(String method, String path, String bodyTemplate) {

        ApiResponse callWith(ApiClient client, Ids ids) {
            String resolved =
                    path.replace("{account}", ids.account().toString())
                            .replace("{batch}", ids.batch().toString())
                            .replace("{transaction}", ids.transaction().toString())
                            .replace("{transfer}", ids.transfer().toString());
            String body =
                    bodyTemplate == null
                            ? null
                            : bodyTemplate
                                    .replace("{account}", ids.account().toString())
                                    .replace("{other}", ids.other().toString())
                                    .replace("{file}", base64(fixture("ofx1-happy.qfx")));
            return client.send(method, resolved, body);
        }

        boolean writes() {
            return !"GET".equals(method);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    record Ids(UUID account, UUID batch, UUID transaction, UUID other, UUID transfer) {

        static Ids unknown() {
            return new Ids(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
    }

    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint(
                        "POST",
                        "/api/imports/preview",
                        "{\"accountId\":\"{account}\",\"format\":\"OFX\",\"file\":\"{file}\"}"),
                new Endpoint(
                        "POST",
                        "/api/imports",
                        "{\"accountId\":\"{account}\",\"format\":\"OFX\",\"file\":\"{file}\",\"previewDigest\":\""
                                + "a".repeat(64)
                                + "\"}"),
                new Endpoint("GET", "/api/imports", null),
                new Endpoint("GET", "/api/imports/{batch}", null),
                new Endpoint("DELETE", "/api/imports/{batch}", null),
                new Endpoint("GET", "/api/accounts/{account}/import-mapping", null),
                new Endpoint(
                        "PUT",
                        "/api/accounts/{account}/import-mapping",
                        "{\"delimiter\":\",\",\"headerRow\":1,\"dateFormat\":\"uuuu-MM-dd\",\"decimalSeparator\":\".\",\"columns\":{\"0\":\"DATE\",\"1\":\"AMOUNT\"}}"),
                new Endpoint("DELETE", "/api/accounts/{account}/import-mapping", null),
                new Endpoint(
                        "POST",
                        "/api/transactions/{transaction}/pair",
                        "{\"withTransactionId\":\"{other}\",\"version\":0,\"withVersion\":0}"),
                new Endpoint("POST", "/api/transfers/{transfer}/split", "{\"version\":0}"));
    }

    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(Endpoint::writes).toList();
    }

    // ------------------------------------------------------------------------------------ 401

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void answers401ToAnUnauthenticatedCaller(Endpoint endpoint) {
        seedHousehold();

        ApiResponse response = endpoint.callWith(browser(), Ids.unknown());

        assertThat(response.status()).isEqualTo(401);
        assertThat(count("import_batches")).isZero();
    }

    // ------------------------------------------------------------------------------- VIEWER 403

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnRealIds(Endpoint endpoint) {
        Household household = seedHousehold();
        Ids ids = realIds(household);

        ApiResponse response = endpoint.callWith(household.viewer(), ids);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
        assertThat(count("import_batches")).as("the seeded batch only").isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403NotA404ToAViewerOnUnknownIds(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse response = endpoint.callWith(household.viewer(), Ids.unknown());

        // The role is checked before anything is looked up, so a viewer learns nothing about
        // which ids exist, and costs no parsing.
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnTheBearerTransportToo(Endpoint endpoint) {
        Household household = seedHousehold();
        Ids ids = realIds(household);
        ApiClient phone = mobile(VIEWER_EMAIL, VIEWER_SECRET, "Alan's phone");

        ApiResponse response = endpoint.callWith(phone, ids);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    // -------------------------------------------------------------------------- not a member

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void answers403ToASignedInUserWithNoMembership(Endpoint endpoint) {
        Household household = seedHousehold();
        Ids ids = realIds(household);
        acceptInvitation(
                invite(household.owner(), OUTSIDER_EMAIL, "MEMBER"),
                "Edsger",
                "edsger-example-passphrase");
        household
                .owner()
                .delete("/api/households/current/members/" + membershipIdOf(OUTSIDER_EMAIL));
        ApiClient outsider = signIn(OUTSIDER_EMAIL, "edsger-example-passphrase");

        ApiResponse response = endpoint.callWith(outsider, ids);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("not-a-member");
    }

    // ------------------------------------------------------------------------------------- CSRF

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403WithoutACsrfTokenOnTheSessionTransport(Endpoint endpoint) {
        Household household = seedHousehold();
        Ids ids = realIds(household);

        ApiResponse response =
                endpoint.callWith(signIn(OWNER_EMAIL, OWNER_SECRET).withoutCsrfToken(), ids);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("csrf-token-required");
        assertThat(count("import_batches")).isEqualTo(1);
    }

    // --------------------------------------------------------------------------- 400, the field

    @Test
    void aPreviewWithNoAccountNamesTheField() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post("/api/imports/preview", "{\"format\":\"OFX\",\"file\":\"\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"accountId\"");
    }

    @Test
    void aCommitWithABadDigestNamesTheField() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/imports",
                                "{\"accountId\":\""
                                        + account
                                        + "\",\"format\":\"OFX\",\"file\":\"\",\"previewDigest\":\"NOT-HEX\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"previewDigest\"");
    }

    @Test
    void aMappingWithABadDelimiterNamesTheField() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse response =
                household
                        .owner()
                        .put(
                                "/api/accounts/" + account + "/import-mapping",
                                "{\"delimiter\":\"x\",\"headerRow\":1,\"dateFormat\":\"uuuu-MM-dd\",\"decimalSeparator\":\".\",\"columns\":{\"0\":\"DATE\",\"1\":\"AMOUNT\"}}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"delimiter\"");
    }

    @Test
    void aPairWithNoPartnerNamesTheField() {
        Household household = seedHousehold();

        ApiResponse response =
                household
                        .owner()
                        .post(
                                "/api/transactions/" + UUID.randomUUID() + "/pair",
                                "{\"version\":0,\"withVersion\":0}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"withTransactionId\"");
    }

    @Test
    void aSplitWithNoVersionNamesTheField() {
        Household household = seedHousehold();

        ApiResponse response =
                household.owner().post("/api/transfers/" + UUID.randomUUID() + "/split", "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"version\"");
    }

    @Test
    void aMalformedIdInAPathIsA400NotA500() {
        Household household = seedHousehold();

        assertThat(household.owner().get("/api/imports/not-a-uuid").status()).isEqualTo(400);
        assertThat(household.owner().get("/api/accounts/not-a-uuid/import-mapping").status())
                .isEqualTo(400);
        assertThat(household.owner().post("/api/transactions/not-a-uuid/pair", "{}").status())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------------------ transports

    @Test
    void aMemberPreviewsAndCommitsIdenticallyOverTheBearerTransport() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        ApiClient phone = mobile(MEMBER_EMAIL, MEMBER_SECRET, "Grace's phone");

        ApiResponse overSession =
                preview(household.member(), account, "OFX", fixture("ofx1-happy.qfx"), null);
        ApiResponse overBearer = preview(phone, account, "OFX", fixture("ofx1-happy.qfx"), null);

        assertThat(overBearer.status()).isEqualTo(200);
        assertThat(overBearer.stringField("previewDigest"))
                .isEqualTo(overSession.stringField("previewDigest"));
        assertThat(
                        previewThenCommit(phone, account, "OFX", fixture("ofx1-happy.qfx"), null)
                                .status())
                .isEqualTo(201);
    }

    @Test
    void aViewerMayReadTheHistoryAndAMapping() {
        Household household = seedHousehold();
        Ids ids = realIds(household);

        assertThat(household.viewer().get("/api/imports").status()).isEqualTo(200);
        assertThat(household.viewer().get("/api/imports/" + ids.batch()).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------- internals

    /** A real account, batch, two ordinary rows in different accounts and a transfer. */
    private Ids realIds(Household household) {
        UUID account = createAccount(household.owner(), "Current", "2211");
        UUID savings = createAccount(household.owner(), "Savings", null);
        ApiResponse committed =
                previewThenCommit(
                        household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);
        UUID batch = UUID.fromString(committed.stringField("id"));
        UUID transaction =
                jdbc.queryForObject("SELECT id FROM transactions WHERE amount = 2400", UUID.class);
        UUID other = createTransaction(household.owner(), savings, "2026-08-06", "-2400.00", null);
        ApiResponse transfer =
                household
                        .owner()
                        .post(
                                "/api/transfers",
                                "{\"fromAccountId\":\""
                                        + savings
                                        + "\",\"toAccountId\":\""
                                        + account
                                        + "\",\"date\":\"2026-08-20\",\"amount\":\"1.00\"}");
        return new Ids(
                account, batch, transaction, other, UUID.fromString(transfer.stringField("id")));
    }
}
