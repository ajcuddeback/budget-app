package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>Another household's accounts, imports, mappings and rows are indistinguishable from ones that
 * never existed</b>, on every route slice 12 adds (ADR-0008 as amended by ADR-0017).
 *
 * <p>Every id belongs to a <b>real second household</b>, seeded with plain SQL — a random UUID
 * answers {@code 404} even against an unscoped {@code findById}, so it cannot prove anything (see
 * {@code MoneyCrossHouseholdIT}). The singleton index is dropped to seed it and restored
 * afterwards, exactly as there.
 *
 * <p>And a refused write touches nothing of theirs: the assertions read their rows back afterwards.
 */
class ImportCrossHouseholdIT extends ImportApiTestBase {

    private static final String THEIR_HOUSEHOLD = "Hopper import";

    @Autowired private PlatformTransactionManager transactionManager;

    private record Theirs(UUID account, UUID batch, UUID row, UUID transfer) {}

    @AfterEach
    void restoreTheSingletonIndex() {
        jdbc.update("DELETE FROM households WHERE name = ?", THEIR_HOUSEHOLD);
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
    }

    static List<String> routesNamingTheirAccount() {
        return List.of(
                "POST /api/imports/preview",
                "POST /api/imports",
                "GET /api/imports?accountId=",
                "GET /api/accounts/{account}/import-mapping",
                "PUT /api/accounts/{account}/import-mapping",
                "DELETE /api/accounts/{account}/import-mapping");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routesNamingTheirAccount")
    void answers404ForTheirAccountOnEveryRoute(String route) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        ApiResponse response = call(us, route, theirs);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.stringField("code")).isEqualTo("not-found");
        assertTheirsUntouched(theirs);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routesNamingTheirAccount")
    void answers404ForTheirAccountOnTheBearerTransportToo(String route) {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        assertThat(call(phone, route, theirs).status()).isEqualTo(404);
        assertTheirsUntouched(theirs);
    }

    @Test
    void answers404ForTheirBatchAndCannotUndoIt() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();
        ApiClient us = signIn(OWNER_EMAIL, OWNER_SECRET);

        assertThat(us.get("/api/imports/" + theirs.batch()).status()).isEqualTo(404);
        assertThat(us.delete("/api/imports/" + theirs.batch()).status()).isEqualTo(404);
        assertTheirsUntouched(theirs);
    }

    @Test
    void theHistoryNeverListsTheirImports() {
        seedHousehold();
        seedASecondHousehold();

        ApiResponse history = signIn(OWNER_EMAIL, OWNER_SECRET).get("/api/imports");

        assertThat(history.status()).isEqualTo(200);
        assertThat(history.numberField("totalElements")).isZero();
    }

    @Test
    void cannotPullTheirRowIntoOurTransferNorPairTheirRowFromThePath() {
        Household household = seedHousehold();
        Theirs theirs = seedASecondHousehold();
        UUID ours = createAccount(household.owner(), "Ours", null);
        UUID row = createTransaction(household.owner(), ours, "2026-08-01", "4321.00", null);

        ApiResponse pulling =
                household
                        .owner()
                        .post(
                                "/api/transactions/" + row + "/pair",
                                "{\"withTransactionId\":\""
                                        + theirs.row()
                                        + "\",\"version\":0,\"withVersion\":0}");
        ApiResponse fromThePath =
                household
                        .owner()
                        .post(
                                "/api/transactions/" + theirs.row() + "/pair",
                                "{\"withTransactionId\":\""
                                        + row
                                        + "\",\"version\":0,\"withVersion\":0}");

        assertThat(pulling.status()).isEqualTo(404);
        assertThat(fromThePath.status()).isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT transfer_group_id FROM transactions WHERE id = ?",
                                UUID.class,
                                row))
                .isNull();
        assertTheirsUntouched(theirs);
    }

    @Test
    void cannotSplitTheirTransfer() {
        seedHousehold();
        Theirs theirs = seedASecondHousehold();

        ApiResponse refused =
                signIn(OWNER_EMAIL, OWNER_SECRET)
                        .post("/api/transfers/" + theirs.transfer() + "/split", "{\"version\":0}");

        assertThat(refused.status()).isEqualTo(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE transfer_group_id = ?",
                                Long.class,
                                theirs.transfer()))
                .isEqualTo(2L);
    }

    // ------------------------------------------------------------------------------- internals

    private ApiResponse call(ApiClient client, String route, Theirs theirs) {
        String file = base64(fixture("ofx1-happy.qfx"));
        String mapping =
                "{\"delimiter\":\",\",\"headerRow\":1,\"dateFormat\":\"uuuu-MM-dd\",\"decimalSeparator\":\".\",\"columns\":{\"0\":\"DATE\",\"1\":\"AMOUNT\"}}";
        return switch (route) {
            case "POST /api/imports/preview" ->
                    client.post(
                            "/api/imports/preview",
                            "{\"accountId\":\""
                                    + theirs.account()
                                    + "\",\"format\":\"OFX\",\"file\":\""
                                    + file
                                    + "\"}");
            case "POST /api/imports" ->
                    client.post(
                            "/api/imports",
                            "{\"accountId\":\""
                                    + theirs.account()
                                    + "\",\"format\":\"OFX\",\"file\":\""
                                    + file
                                    + "\",\"previewDigest\":\""
                                    + "c".repeat(64)
                                    + "\"}");
            case "GET /api/imports?accountId=" ->
                    client.get("/api/imports?accountId=" + theirs.account());
            case "GET /api/accounts/{account}/import-mapping" ->
                    client.get("/api/accounts/" + theirs.account() + "/import-mapping");
            case "PUT /api/accounts/{account}/import-mapping" ->
                    client.put("/api/accounts/" + theirs.account() + "/import-mapping", mapping);
            case "DELETE /api/accounts/{account}/import-mapping" ->
                    client.delete("/api/accounts/" + theirs.account() + "/import-mapping");
            default -> throw new IllegalArgumentException(route);
        };
    }

    private void assertTheirsUntouched(Theirs theirs) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE account_id = ?",
                                Long.class,
                                theirs.account()))
                .as("their ledger exactly as long as it was")
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM import_batches WHERE id = ?",
                                Long.class,
                                theirs.batch()))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM import_mappings WHERE account_id = ?",
                                Long.class,
                                theirs.account()))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT transfer_group_id FROM transactions WHERE id = ?",
                                UUID.class,
                                theirs.row()))
                .isNull();
    }

    private Theirs seedASecondHousehold() {
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID savings = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        UUID row = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            jdbc.update(
                                    "INSERT INTO users (id, email, display_name) VALUES (?, 'grace-import@example.com', 'Grace')",
                                    owner);
                            jdbc.update(
                                    "INSERT INTO households (id, name, base_currency) VALUES (?, ?, 'GBP')",
                                    household,
                                    THEIR_HOUSEHOLD);
                            jdbc.update(
                                    "INSERT INTO household_members (id, household_id, user_id, role) VALUES (?, ?, ?, 'OWNER')",
                                    UUID.randomUUID(),
                                    household,
                                    owner);
                            jdbc.update(
                                    "INSERT INTO accounts (id, household_id, name, type, currency, opening_balance) VALUES (?, ?, 'Grace''s current', 'CHECKING', 'GBP', 0)",
                                    account,
                                    household);
                            jdbc.update(
                                    "INSERT INTO accounts (id, household_id, name, type, currency, opening_balance) VALUES (?, ?, 'Grace''s savings', 'SAVINGS', 'GBP', 0)",
                                    savings,
                                    household);
                            jdbc.update(
                                    "INSERT INTO import_batches (id, household_id, account_id, imported_by, format, imported_count, duplicate_count, excluded_count, unreadable_count, zero_count, preview_digest) VALUES (?, ?, ?, ?, 'OFX', 1, 0, 0, 0, 0, ?)",
                                    batch,
                                    household,
                                    account,
                                    owner,
                                    "d".repeat(64));
                            jdbc.update(
                                    "INSERT INTO transactions (id, household_id, account_id, date, amount, external_id, import_batch_id) VALUES (?, ?, ?, DATE '2026-08-01', -4321.0000, ?, ?)",
                                    row,
                                    household,
                                    account,
                                    "ofx1:" + "e".repeat(64),
                                    batch);
                            jdbc.update(
                                    "INSERT INTO transactions (id, household_id, account_id, date, amount, transfer_group_id) VALUES (?, ?, ?, DATE '2026-08-02', -10.0000, ?)",
                                    UUID.randomUUID(),
                                    household,
                                    account,
                                    transfer);
                            jdbc.update(
                                    "INSERT INTO transactions (id, household_id, account_id, date, amount, transfer_group_id) VALUES (?, ?, ?, DATE '2026-08-02', 10.0000, ?)",
                                    UUID.randomUUID(),
                                    household,
                                    savings,
                                    transfer);
                            jdbc.update(
                                    "INSERT INTO import_mappings (id, household_id, account_id, delimiter, date_format, decimal_separator, header_row, columns) VALUES (?, ?, ?, ',', 'uuuu-MM-dd', '.', 1, '{\"roles\":{\"0\":\"DATE\",\"1\":\"AMOUNT\"}}'::jsonb)",
                                    UUID.randomUUID(),
                                    household,
                                    account);
                        });
        return new Theirs(account, batch, row, transfer);
    }
}
