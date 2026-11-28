package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * File import over real HTTP against real PostgreSQL (docs/features/file-import.md).
 *
 * <p>The cases are the ones that would make import wrong rather than merely incomplete: a preview
 * that writes, a commit that writes something other than what the preview showed, a re-import that
 * doubles the ledger, two identical purchases collapsed into one, another account's identifier
 * suppressing a real row, a commit that half-happens.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImportIT extends ImportApiTestBase {

    // ------------------------------------------------------------------- preview writes nothing

    @Test
    void a_preview_writes_no_row_no_payee_no_batch_and_no_file() throws IOException {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        long transactions = count("transactions");
        long payees = count("payees");
        Instant started = Instant.now();

        ApiResponse previewed =
                preview(household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);

        assertThat(previewed.status()).isEqualTo(200);
        assertThat(previewed.numberField("new")).isEqualTo(5);
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("payees")).isEqualTo(payees);
        assertThat(count("import_batches")).isZero();
        assertThat(newFilesHolding("SENTINEL PAYEE QX7", started))
                .as("nothing from the statement in java.io.tmpdir (ADR-0038)")
                .isEmpty();
    }

    @Test
    void a_preview_is_never_cached() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");

        ApiResponse previewed =
                preview(household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);

        assertThat(previewed.header("Cache-Control").orElse("")).contains("no-store");
    }

    // ------------------------------------------------------------------------- preview = commit

    @Test
    void the_commit_writes_exactly_what_the_preview_showed() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);

        ApiResponse committed =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        previewed.stringField("previewDigest"),
                        null);

        assertThat(committed.status()).isEqualTo(201);
        UUID batch = UUID.fromString(committed.stringField("id"));
        assertThat(committed.header("Location")).contains("/api/imports/" + batch);
        assertThat(committed.numberField("imported")).isEqualTo(5);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT t.date::text AS date, t.amount::text AS amount, p.name AS payee,
                               t.note, t.status, t.category_id, t.external_id, t.import_batch_id
                          FROM transactions t LEFT JOIN payees p ON p.id = t.payee_id
                         ORDER BY t.date, t.amount
                        """);
        assertThat(rows).hasSize(5);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("status")).isEqualTo("CLEARED");
                            assertThat(row.get("category_id")).as("Needs a category").isNull();
                            assertThat((String) row.get("external_id"))
                                    .startsWith("ofx1:")
                                    .hasSize(69);
                            assertThat(row.get("import_batch_id")).isEqualTo(batch);
                        });
        assertThat(rows.get(0))
                .containsEntry("date", "2026-08-03")
                .containsEntry("amount", "-3.5000")
                .containsEntry("payee", "BLUE BOTTLE");
        // The preview's sample is in file order: the same five rows, the same figures.
        assertThat(previewed.body())
                .contains("\"payeeName\":\"BLUE BOTTLE\"")
                .contains("\"amount\":\"2400.0000\"")
                .contains("\"payeeName\":\"CORNER SHOP & DELI\"");
        assertThat(household.owner().get("/api/accounts/" + account).stringField("balance"))
                .isEqualTo(previewed.stringField("balanceAfter"));
    }

    @Test
    void a_retried_commit_returns_the_batch_it_already_wrote_with_200() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        String digest =
                preview(household.owner(), account, "OFX", file, null).stringField("previewDigest");
        ApiResponse first = commit(household.owner(), account, "OFX", file, null, digest, null);

        ApiResponse retried = commit(household.owner(), account, "OFX", file, null, digest, null);

        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.stringField("id")).isEqualTo(first.stringField("id"));
        assertThat(count("transactions")).isEqualTo(5);
        assertThat(count("import_batches")).isEqualTo(1);
    }

    @Test
    void a_mismatched_digest_writes_nothing_and_is_stale() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");

        ApiResponse refused =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        fixture("ofx1-happy.qfx"),
                        null,
                        "0".repeat(64),
                        null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("import-preview-stale");
        assertThat(count("transactions")).isZero();
        assertThat(count("payees")).isZero();
        assertThat(count("import_batches")).isZero();
    }

    @Test
    void a_row_imported_by_someone_else_since_the_preview_makes_it_stale() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String coffee = "D8/3'26\nT-3.50\nPBLUE BOTTLE\n^\n";
        String salary = "D8/5'26\nT2400.00\nPACME PAYROLL\n^\n";
        byte[] statement = utf8("!Type:Bank\n" + coffee + salary);
        String stale =
                preview(household.owner(), account, "QIF", statement, QIF_OPTIONS)
                        .stringField("previewDigest");
        // Grace imports a different file that shares the coffee. Ada's preview no longer
        // describes what her commit would write: the coffee is now a duplicate.
        previewThenCommit(
                household.member(), account, "QIF", utf8("!Type:Bank\n" + coffee), QIF_OPTIONS);

        ApiResponse refused =
                commit(household.owner(), account, "QIF", statement, QIF_OPTIONS, stale, null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("import-preview-stale");
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    void the_same_preview_committed_by_two_members_is_one_batch() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        String digest =
                preview(household.owner(), account, "OFX", file, null).stringField("previewDigest");
        previewThenCommit(household.member(), account, "OFX", file, null);

        ApiResponse second = commit(household.owner(), account, "OFX", file, null, digest, null);

        // Same file, same ledger, same digest: the batch Grace wrote IS the one Ada asked for.
        assertThat(second.status()).isEqualTo(200);
        assertThat(count("import_batches")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(5);
    }

    // ------------------------------------------------------------------------------- duplicates

    @Test
    void importing_the_same_file_again_adds_nothing() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        previewThenCommit(household.owner(), account, "OFX", file, null);

        ApiResponse again = preview(household.owner(), account, "OFX", file, null);
        ApiResponse committed =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        again.stringField("previewDigest"),
                        null);

        assertThat(again.numberField("new")).isZero();
        assertThat(again.numberField("duplicate")).isEqualTo(5);
        assertThat(committed.numberField("imported")).isZero();
        assertThat(count("transactions")).isEqualTo(5);
    }

    @Test
    void two_identical_coffees_both_import_and_an_overlapping_file_adds_only_the_new_one() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String coffee = "D8/3'26\nT-3.50\nPBLUE BOTTLE\n^\n";
        byte[] twoCoffees = utf8("!Type:Bank\n" + coffee + coffee);
        byte[] threeCoffees = utf8("!Type:Bank\n" + coffee + coffee + coffee);

        ApiResponse first =
                previewThenCommit(household.owner(), account, "QIF", twoCoffees, QIF_OPTIONS);
        ApiResponse again =
                previewThenCommit(household.owner(), account, "QIF", twoCoffees, QIF_OPTIONS);
        ApiResponse overlap =
                previewThenCommit(household.owner(), account, "QIF", threeCoffees, QIF_OPTIONS);

        assertThat(first.numberField("imported")).isEqualTo(2);
        assertThat(again.numberField("imported")).isZero();
        assertThat(overlap.numberField("imported")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(3);
    }

    @Test
    void the_preview_says_identical_rows_will_both_be_imported() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String coffee = "D8/3'26\nT-3.50\nPBLUE BOTTLE\n^\n";

        ApiResponse previewed =
                preview(
                        household.owner(),
                        account,
                        "QIF",
                        utf8("!Type:Bank\n" + coffee + coffee),
                        QIF_OPTIONS);

        assertThat(previewed.body()).contains("\"identical\":[{").contains("\"count\":2");
    }

    @Test
    void two_accounts_with_the_same_fitid_both_import() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "2211");
        UUID other = createAccount(household.owner(), "Other bank", "9999");
        byte[] file = fixture("ofx1-happy.qfx");

        previewThenCommit(household.owner(), current, "OFX", file, null);
        ApiResponse second = previewThenCommit(household.owner(), other, "OFX", file, null);

        assertThat(second.numberField("imported"))
                .as("the account is inside the hash, so the other bank's FITIDs suppress nothing")
                .isEqualTo(5);
        assertThat(count("transactions")).isEqualTo(10);
    }

    @Test
    void a_row_resembling_a_typed_one_is_suspected_and_left_out_unless_included() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        UUID typed = createTransaction(household.owner(), account, "2026-08-11", "-54.20", null);
        byte[] file = fixture("ofx1-happy.qfx");

        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);
        assertThat(previewed.numberField("suspected")).isEqualTo(1);
        assertThat(previewed.body())
                .contains("\"kind\":\"SUSPECTED_DUPLICATE\"")
                .contains("\"transactionId\":\"" + typed + "\"");

        ApiResponse byDefault =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        previewed.stringField("previewDigest"),
                        null);

        assertThat(byDefault.numberField("imported")).isEqualTo(4);
        assertThat(byDefault.numberField("excluded")).isEqualTo(1);
    }

    @Test
    void a_suspected_row_can_be_included_by_name() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        createTransaction(household.owner(), account, "2026-08-11", "-54.20", null);
        byte[] file = fixture("ofx1-happy.qfx");
        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);

        ApiResponse included =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        previewed.stringField("previewDigest"),
                        "\"overrides\":[{\"row\":4,\"include\":true}]");

        assertThat(included.numberField("imported")).isEqualTo(5);
        assertThat(count("transactions")).isEqualTo(6);
    }

    @Test
    void include_all_imports_every_suspected_row() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        createTransaction(household.owner(), account, "2026-08-11", "-54.20", null);
        byte[] file = fixture("ofx1-happy.qfx");
        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);

        ApiResponse included =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        previewed.stringField("previewDigest"),
                        "\"suspectedDuplicates\":\"INCLUDE\"");

        assertThat(included.numberField("imported")).isEqualTo(5);
    }

    @Test
    void one_typed_coffee_cannot_suppress_two_imported_ones() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        createTransaction(household.owner(), account, "2026-08-03", "-3.50", null);

        ApiResponse previewed =
                preview(household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);

        assertThat(previewed.numberField("suspected")).as("one to one").isEqualTo(1);
    }

    @Test
    void an_override_may_only_name_a_suspected_row() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        previewThenCommit(household.owner(), account, "OFX", file, null);
        ApiResponse previewed = preview(household.owner(), account, "OFX", file, null);

        ApiResponse namingADuplicate =
                commit(
                        household.owner(),
                        account,
                        "OFX",
                        file,
                        null,
                        previewed.stringField("previewDigest"),
                        "\"overrides\":[{\"row\":1,\"include\":true}]");

        assertThat(namingADuplicate.status()).isEqualTo(400);
        assertThat(namingADuplicate.stringField("code")).isEqualTo("validation-failed");
        assertThat(namingADuplicate.body()).contains("\"field\":\"overrides\"");
        assertThat(count("transactions")).isEqualTo(5);
    }

    @Test
    void an_override_naming_an_unreadable_row_is_refused() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        byte[] file = fixture("csv-happy.csv");
        ApiResponse previewed = preview(household.owner(), account, "CSV", file, CSV_OPTIONS);
        assertThat(previewed.numberField("unreadable")).isEqualTo(1);

        ApiResponse refused =
                commit(
                        household.owner(),
                        account,
                        "CSV",
                        file,
                        CSV_OPTIONS,
                        previewed.stringField("previewDigest"),
                        "\"overrides\":[{\"row\":7,\"include\":true}]");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(count("transactions")).isZero();
    }

    // ------------------------------------------------------------------------- account changes

    @Test
    void an_archived_account_is_refused_at_preview_and_at_commit() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        String digest =
                preview(household.owner(), account, "OFX", file, null).stringField("previewDigest");
        household.owner().post("/api/accounts/" + account + "/archive", null);

        ApiResponse atCommit = commit(household.owner(), account, "OFX", file, null, digest, null);
        ApiResponse atPreview = preview(household.owner(), account, "OFX", file, null);

        assertThat(atCommit.status()).isEqualTo(409);
        assertThat(atCommit.stringField("code")).isEqualTo("account-archived");
        assertThat(atPreview.stringField("code")).isEqualTo("account-archived");
        assertThat(count("transactions")).isZero();
    }

    @Test
    void a_currency_change_between_preview_and_commit_makes_the_preview_stale() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        byte[] file = fixture("csv-happy.csv");
        String digest =
                preview(household.owner(), account, "CSV", file, CSV_OPTIONS)
                        .stringField("previewDigest");
        long version = household.owner().get("/api/accounts/" + account).numberField("version");
        assertThat(
                        household
                                .owner()
                                .patch(
                                        "/api/accounts/" + account,
                                        "{\"currency\":\"EUR\",\"version\":" + version + "}")
                                .status())
                .isEqualTo(200);

        ApiResponse refused =
                commit(household.owner(), account, "CSV", file, CSV_OPTIONS, digest, null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.stringField("code")).isEqualTo("import-preview-stale");
    }

    @Test
    void the_account_hint_is_computed_and_no_account_number_digit_comes_back() {
        Household household = seedHousehold();
        UUID matching = createAccount(household.owner(), "Current", "2211");
        UUID other = createAccount(household.owner(), "Other", "4412");

        ApiResponse matches =
                preview(household.owner(), matching, "OFX", fixture("ofx1-happy.qfx"), null);
        ApiResponse differs =
                preview(household.owner(), other, "OFX", fixture("ofx1-happy.qfx"), null);

        assertThat(matches.stringField("accountHint")).isEqualTo("MATCHES");
        assertThat(differs.stringField("accountHint")).isEqualTo("DIFFERS");
        for (ApiResponse response : List.of(matches, differs)) {
            assertThat(response.body())
                    .doesNotContain("8877665544332211")
                    .doesNotContain("887766554433")
                    .doesNotContain("SENTINELBANK77")
                    .doesNotContain("sentinel.userid.qx7")
                    .doesNotContain("SENTINEL INSTITUTION");
        }
        assertThat(matches.stringField("bankClosingBalance")).isEqualTo("-700.7900");
    }

    // -------------------------------------------------------------------------------- atomicity

    @Test
    void a_commit_that_fails_on_its_last_row_leaves_no_row_no_payee_and_no_batch() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        String digest =
                preview(household.owner(), account, "OFX", file, null).stringField("previewDigest");
        // The test seam: a trigger that refuses the file's last row, as a constraint would.
        jdbc.execute(
                """
                CREATE FUNCTION test_refuse_the_last_row() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.amount = -3141.5900 THEN
                        RAISE EXCEPTION 'refused by the test seam' USING ERRCODE = 'check_violation';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute(
                "CREATE TRIGGER test_refuse_the_last_row BEFORE INSERT ON transactions"
                        + " FOR EACH ROW EXECUTE FUNCTION test_refuse_the_last_row()");
        try {
            ApiResponse failed =
                    commit(household.owner(), account, "OFX", file, null, digest, null);

            assertThat(failed.status()).isEqualTo(409);
            assertThat(count("transactions")).isZero();
            assertThat(count("payees")).isZero();
            assertThat(count("import_batches")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_the_last_row ON transactions");
            jdbc.execute("DROP FUNCTION test_refuse_the_last_row()");
        }
    }

    @Test
    void two_commits_of_one_preview_at_once_write_one_batch_and_never_half_of_one()
            throws Exception {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        byte[] file = fixture("ofx1-happy.qfx");
        String digest =
                preview(household.owner(), account, "OFX", file, null).stringField("previewDigest");
        List<Callable<ApiResponse>> both =
                List.of(
                        () -> commit(household.owner(), account, "OFX", file, null, digest, null),
                        () -> commit(household.member(), account, "OFX", file, null, digest, null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<ApiResponse> outcome : pool.invokeAll(both)) {
                statuses.add(outcome.get().status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).contains(201);
        assertThat(statuses).allMatch(status -> status == 201 || status == 200 || status == 409);
        assertThat(count("import_batches")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(5);
    }

    // ------------------------------------------------------------------------------------- undo

    @Test
    void undo_removes_the_rows_and_the_batch_but_keeps_a_row_since_paired() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        UUID savings = createAccount(household.owner(), "Savings", null);
        ApiResponse committed =
                previewThenCommit(
                        household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);
        UUID batch = UUID.fromString(committed.stringField("id"));
        UUID salary =
                jdbc.queryForObject("SELECT id FROM transactions WHERE amount = 2400", UUID.class);
        UUID moved = createTransaction(household.owner(), savings, "2026-08-06", "-2400.00", null);
        ApiResponse paired =
                household
                        .owner()
                        .post(
                                "/api/transactions/" + salary + "/pair",
                                "{\"withTransactionId\":\""
                                        + moved
                                        + "\",\"version\":0,\"withVersion\":0}");
        assertThat(paired.status()).isEqualTo(200);
        assertThat(household.owner().get("/api/imports/" + batch).numberField("removable"))
                .isEqualTo(4);

        ApiResponse undone = household.owner().delete("/api/imports/" + batch);

        assertThat(undone.status()).isEqualTo(204);
        assertThat(count("transactions")).as("the paired leg and its partner stay").isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT import_batch_id FROM transactions WHERE id = ?",
                                UUID.class,
                                salary))
                .isNull();
        assertThat(household.owner().get("/api/imports/" + batch).status()).isEqualTo(404);
        assertThat(
                        previewThenCommit(
                                        household.owner(),
                                        account,
                                        "OFX",
                                        fixture("ofx1-happy.qfx"),
                                        null)
                                .numberField("imported"))
                .as(
                        "a deleted imported row comes back on the next import (decision 6); the paired one does not")
                .isEqualTo(4);
    }

    @Test
    void the_history_lists_imports_newest_first_with_no_file_name() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");
        previewThenCommit(household.owner(), account, "OFX", fixture("ofx1-happy.qfx"), null);

        ApiResponse history = household.viewer().get("/api/imports?accountId=" + account);

        assertThat(history.status()).isEqualTo(200);
        assertThat(history.numberField("totalElements")).isEqualTo(1);
        assertThat(history.body())
                .contains("\"format\":\"OFX\"")
                .doesNotContain("qfx")
                .doesNotContain("fileName");
    }

    // ---------------------------------------------------------------------------- csv and mapping

    @Test
    void a_csv_with_no_mapping_is_described_and_nothing_is_classified() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse described =
                preview(household.owner(), account, "CSV", fixture("csv-happy.csv"), null);

        assertThat(described.status()).isEqualTo(200);
        assertThat(described.body())
                .contains("\"needs\":[\"MAPPING\"]")
                .contains("\"previewDigest\":null")
                .contains("\"headerLabels\":[\"Date\",\"Description\",\"Reference\",\"Amount\"]");
    }

    @Test
    void a_commit_cannot_skip_a_question_the_preview_asked() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                commit(
                        household.owner(),
                        account,
                        "CSV",
                        fixture("csv-happy.csv"),
                        null,
                        "a".repeat(64),
                        null);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("\"field\":\"options.columns\"");
    }

    @Test
    void saving_a_mapping_with_a_commit_preselects_it_and_spots_a_changed_header() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String saving = CSV_OPTIONS.replace("\"columns\"", "\"saveMapping\":true,\"columns\"");
        previewThenCommit(household.owner(), account, "CSV", fixture("csv-happy.csv"), saving);

        ApiResponse saved = household.owner().get("/api/accounts/" + account + "/import-mapping");
        ApiResponse same =
                preview(household.owner(), account, "CSV", fixture("csv-happy.csv"), CSV_OPTIONS);
        ApiResponse changed =
                preview(
                        household.owner(),
                        account,
                        "CSV",
                        utf8("When,What,Ref,How much\n03/08/2026,X,R,-1.00\n"),
                        CSV_OPTIONS);

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.stringField("dateFormat")).isEqualTo("dd/MM/uuuu");
        assertThat(saved.body())
                .contains("\"0\":\"DATE\"")
                .doesNotContain("BLUE BOTTLE")
                .doesNotContain("TX-1001");
        assertThat(same.body()).contains("\"mappingMatchesSaved\":true");
        assertThat(changed.body()).contains("\"mappingMatchesSaved\":false");
    }

    @Test
    void a_mapping_can_be_saved_replaced_with_its_version_and_forgotten() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        String mapping =
                """
                {"delimiter":";","headerRow":1,"dateFormat":"uuuu-MM-dd","decimalSeparator":",",
                 "groupingSeparator":".","columns":{"0":"DATE","1":"AMOUNT"},
                 "headerLabels":["Datum","Betrag"]}
                """;

        ApiResponse created =
                household.owner().put("/api/accounts/" + account + "/import-mapping", mapping);
        ApiResponse stale =
                household.owner().put("/api/accounts/" + account + "/import-mapping", mapping);
        ApiResponse replaced =
                household
                        .owner()
                        .put(
                                "/api/accounts/" + account + "/import-mapping",
                                mapping.replace(
                                        "}\n",
                                        ",\"version\":" + created.numberField("version") + "}\n"));
        ApiResponse forgotten =
                household.owner().delete("/api/accounts/" + account + "/import-mapping");

        assertThat(created.status()).isEqualTo(200);
        assertThat(stale.status()).as("replacing needs the version").isEqualTo(409);
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(forgotten.status()).isEqualTo(204);
        assertThat(household.owner().get("/api/accounts/" + account + "/import-mapping").status())
                .isEqualTo(404);
    }

    @Test
    void options_that_do_not_apply_to_the_format_are_refused_naming_the_field() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "2211");

        ApiResponse refused =
                preview(
                        household.owner(),
                        account,
                        "OFX",
                        fixture("ofx1-happy.qfx"),
                        "{\"delimiter\":\",\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("\"field\":\"options.delimiter\"");
    }

    @Test
    void a_charset_outside_the_allowlist_is_refused_whatever_it_is_called() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused =
                preview(
                        household.owner(),
                        account,
                        "CSV",
                        fixture("csv-happy.csv"),
                        "{\"charset\":\"UTF-7\"}");

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.stringField("code")).isEqualTo("import-encoding-unsupported");
    }

    // ------------------------------------------------------------------------- refusing the file

    @Test
    void an_xxe_upload_is_refused_and_reaches_nothing() throws IOException {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        AtomicInteger hits = new AtomicInteger();
        HttpServer trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trap.createContext(
                "/",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        trap.start();
        try {
            byte[] file =
                    utf8(
                            com.budgetowl.imports.domain.ImportFixtures.text(
                                            "ofx2-xxe.ofx.template")
                                    .replace(
                                            "@@SYSTEM@@",
                                            "http://127.0.0.1:"
                                                    + trap.getAddress().getPort()
                                                    + "/"));

            ApiResponse refused = preview(household.owner(), account, "OFX", file, null);

            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.stringField("code")).isEqualTo("import-file-malformed");
            assertThat(refused.body()).contains("\"reason\":\"dtd\"");
            assertThat(hits.get()).as("requests made on the attacker's behalf").isZero();
        } finally {
            trap.stop(0);
        }
    }

    @Test
    void every_refusal_carries_only_its_code_and_a_fixed_reason() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        Set<String> reasons =
                Set.of(
                        "dtd",
                        "binary",
                        "header",
                        "line-too-long",
                        "too-many-columns",
                        "too-deep",
                        "too-many-elements",
                        "element-too-long",
                        "encrypted",
                        "compressed",
                        "not-a-statement",
                        "unclosed-quote");
        Map<String, byte[]> hostile =
                Map.of(
                        "ofx1-doctype.ofx", fixture("ofx1-doctype.ofx"),
                        "ofx1-entity.ofx", fixture("ofx1-entity.ofx"),
                        "ofx1-cdata.ofx", fixture("ofx1-cdata.ofx"),
                        "ofx2-billion-laughs.ofx", fixture("ofx2-billion-laughs.ofx"));

        for (Map.Entry<String, byte[]> file : hostile.entrySet()) {
            ApiResponse refused = preview(household.owner(), account, "OFX", file.getValue(), null);

            assertThat(refused.status()).as(file.getKey()).isEqualTo(400);
            assertThat(refused.stringField("detail")).isEqualTo("The file could not be read.");
            assertThat(reasons).contains(refused.stringField("reason"));
            assertThat(refused.body())
                    .doesNotContain("ENTITY")
                    .doesNotContain("proc/self")
                    .doesNotContain("Exception");
        }
    }

    @Test
    void invalid_base64_and_a_file_name_field_are_malformed_requests() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse notBase64 =
                household
                        .owner()
                        .post(
                                "/api/imports/preview",
                                "{\"accountId\":\""
                                        + account
                                        + "\",\"format\":\"CSV\",\"file\":\"@@@\"}");
        ApiResponse withName =
                household
                        .owner()
                        .post(
                                "/api/imports/preview",
                                "{\"accountId\":\""
                                        + account
                                        + "\",\"format\":\"CSV\",\"file\":\"\",\"fileName\":\"x.csv\"}");

        assertThat(notBase64.status()).isEqualTo(400);
        assertThat(notBase64.stringField("code")).isEqualTo("malformed-request");
        assertThat(withName.stringField("code")).isEqualTo("malformed-request");
    }

    @Test
    void an_empty_file_is_refused() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);

        ApiResponse refused = preview(household.owner(), account, "CSV", new byte[0], null);

        assertThat(refused.stringField("code")).isEqualTo("import-file-empty");
    }
}
