package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two parameters, at the edge and in the service (docs/features/reporting.md, attacks 4 and 5).
 *
 * <p>A constraint is only proven by sending something that violates it and reading the status code
 * ({@code docs/memory/gotchas.md}, "{@code @Validated} on a controller turns a 400 into a 500"). So
 * every malformed shape is sent, to both reports and in both representations, and each must be a
 * {@code 400} naming the field — never a {@code 500}.
 */
class ReportRangeIT extends ReportTestBase {

    private Household household;

    @BeforeEach
    void seedAHousehold() {
        household = seedHousehold();
    }

    static List<String> reports() {
        return List.of("/api/reports/income-and-spending", "/api/reports/spending-by-category");
    }

    /** Each malformed value, in each parameter, on each report, in each representation. */
    static Stream<Arguments> malformed() {
        List<String> values =
                List.of(
                        "%2B10000-01",
                        "10000-01",
                        "2026-13",
                        "2026-00",
                        "26-08",
                        "2026-8",
                        "",
                        "2026-08-01",
                        "abc",
                        "%E2%80%8B2026-08",
                        "-2026-08");
        return reports().stream()
                .flatMap(
                        path ->
                                Stream.of("from", "to")
                                        .flatMap(
                                                field ->
                                                        Stream.of("application/json", "text/csv")
                                                                .flatMap(
                                                                        accept ->
                                                                                values.stream()
                                                                                        .map(
                                                                                                value ->
                                                                                                        Arguments
                                                                                                                .of(
                                                                                                                        path,
                                                                                                                        field,
                                                                                                                        value,
                                                                                                                        accept)))));
    }

    @ParameterizedTest(name = "{0} {1}={2} as {3}")
    @MethodSource("malformed")
    void aMalformedPeriodIs400NamingTheFieldAndNever500(
            String path, String field, String value, String accept) {
        String query =
                "from".equals(field)
                        ? "?from=" + value + "&to=2026-08"
                        : "?from=2026-08&to=" + value;

        ApiResponse response = household.owner().getAccepting(path + query, accept);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains("\"field\":\"" + field + "\"");
        assertThat(response.header("Content-Type")).get().asString().contains("problem+json");
    }

    @ParameterizedTest(name = "{0} without {1}")
    @MethodSource("missing")
    void aMissingPeriodIs400NamingTheField(String path, String field) {
        String query = "from".equals(field) ? "?to=2026-08" : "?from=2026-08";

        ApiResponse response = household.owner().get(path + query);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
        assertThat(response.body()).contains("\"field\":\"" + field + "\"");
    }

    static Stream<Arguments> missing() {
        return reports().stream()
                .flatMap(path -> Stream.of(Arguments.of(path, "from"), Arguments.of(path, "to")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void neitherPeriodIsDefaultedByTheServer(String path) {
        ApiResponse response = household.owner().get(path);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"field\":\"from\"").contains("\"field\":\"to\"");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void fromAfterToIs422ReportRangeInvalid(String path) {
        ApiResponse response = household.owner().get(path + "?from=2026-09&to=2026-08");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.stringField("code")).isEqualTo("report-range-invalid");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void twentyFiveMonthsIs422WithTheLimitInParams(String path) {
        ApiResponse response = household.owner().get(path + "?from=2024-08&to=2026-08");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.stringField("code")).isEqualTo("report-range-too-long");
        assertThat(response.body()).contains("\"maximumMonths\":24");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void twentyFourMonthsIsAccepted(String path) {
        ApiResponse response = household.owner().get(path + "?from=2024-09&to=2026-08");

        assertThat(response.status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void theWholeFourDigitRangeIsRefusedByTheCapNotByAnOverflow(String path) {
        // Attack 4: a scan of the whole ledger and 119,988 dense month rows, in a loop.
        ApiResponse response = household.owner().get(path + "?from=0001-01&to=9999-12");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.stringField("code")).isEqualTo("report-range-too-long");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reports")
    void theLastFourDigitMonthIsAnOrdinaryMonth(String path) {
        // The upper bound's exclusive end is 10000-01-01, a five-digit year. It is built from a
        // LocalDate and bound as a date, so it must be a 200 and not an overflow.
        ApiResponse response = household.owner().get(path + "?from=9999-12&to=9999-12");

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void yearZeroIsRefusedAsARangeRatherThanSentToTheDatabase() {
        // PostgreSQL has no year zero. Java does. A 422, not a question about 1 BC.
        ApiResponse response =
                household.owner().get("/api/reports/income-and-spending?from=0000-01&to=0000-12");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.stringField("code")).isEqualTo("report-range-invalid");
    }

    @Test
    void aComparisonThatWouldReachYearZeroIsRefusedToo() {
        ApiResponse response =
                household.owner().get("/api/reports/spending-by-category?from=0001-01&to=0001-01");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.stringField("code")).isEqualTo("report-range-invalid");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {"?from=2026-08&to=2026-08&from=2025-01", "?from=2026-08,2026-07&to=2026-08"})
    void aRepeatedOrListValueIsNotQuietlyHalfRead(String query) {
        ApiResponse response = household.owner().get("/api/reports/income-and-spending" + query);

        assertThat(response.status()).isEqualTo(400);
    }
}
