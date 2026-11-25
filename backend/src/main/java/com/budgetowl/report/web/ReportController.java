package com.budgetowl.report.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.report.service.ReportService;
import com.budgetowl.report.web.ReportResponses.IncomeAndSpendingResponse;
import com.budgetowl.report.web.ReportResponses.SpendingByCategoryResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports: two documents, each with a JSON and a CSV representation (docs/features/reporting.md).
 *
 * <p><b>{@code GET} only, and no id of any kind.</b> The household is the caller's; there is
 * nothing to enumerate. Any other method on these paths is a {@code 405}, and {@code
 * ReportAccessIT} holds that, so a write cannot appear here unreviewed.
 *
 * <p><b>The CSV is a second representation of the same resource</b>, chosen by {@code Accept:
 * text/csv}, not an export endpoint. It is built from the very response object the JSON is, so the
 * two cannot disagree.
 *
 * <p><b>The CSV body is {@code byte[]}, on purpose.</b> Spring MVC logs the body it writes at
 * {@code DEBUG}, and for a {@code String} that is the text itself — every category name and figure
 * in the file. A {@code byte[]} is logged as its type.
 *
 * <p><b>{@code from} and {@code to} are {@code required = false} with {@code @NotNull}</b>, which
 * looks backwards and is deliberate. A missing required parameter is a {@code
 * MissingServletRequestParameterException}, which the shared handler can only answer as a generic
 * {@code request-rejected}. Optional-plus-{@code @NotNull} sends the missing value through Spring's
 * native method validation instead, which answers {@code 400 validation-failed} naming the field —
 * the same answer as a malformed one.
 */
@RestController
@RequestMapping("/api/reports")
// Deliberately NOT @Validated: Spring MVC validates constrained @RequestParams natively and
// answers 400. @Validated would swap that for an unhandled ConstraintViolationException — a 500.
// ArchitectureTest.nothing_is_validated_at_class_level forbids it; see docs/memory/gotchas.md.
public class ReportController {

    /**
     * Exactly four digits, a hyphen and a month, so {@code YearMonth.parse} never sees a signed or
     * five-digit year such as {@code +10000-01} (attack 5).
     */
    static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    private static final String PERIOD_MESSAGE = "must be a month in the form 2026-08";

    static final String TEXT_CSV = "text/csv";

    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Report 1: income and spending, by month, per currency. */
    @GetMapping(path = "/income-and-spending", produces = MediaType.APPLICATION_JSON_VALUE)
    IncomeAndSpendingResponse incomeAndSpending(
            TransportAuthentication caller,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String from,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String to) {
        return IncomeAndSpendingResponse.of(
                reports.incomeAndSpending(
                        caller.userId(), YearMonth.parse(from), YearMonth.parse(to)));
    }

    /** Report 1 as a CSV file: one record per currency per month. */
    @GetMapping(path = "/income-and-spending", produces = TEXT_CSV)
    ResponseEntity<byte[]> incomeAndSpendingCsv(
            TransportAuthentication caller,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String from,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String to) {
        IncomeAndSpendingResponse report =
                IncomeAndSpendingResponse.of(
                        reports.incomeAndSpending(
                                caller.userId(), YearMonth.parse(from), YearMonth.parse(to)));
        exported("income-and-spending", caller, report.from(), report.to());
        return csv(
                "income-and-spending",
                report.from(),
                report.to(),
                ReportCsv.incomeAndSpending(report));
    }

    /** Report 2: spending by category against the preceding range, per currency. */
    @GetMapping(path = "/spending-by-category", produces = MediaType.APPLICATION_JSON_VALUE)
    SpendingByCategoryResponse spendingByCategory(
            TransportAuthentication caller,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String from,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String to) {
        return SpendingByCategoryResponse.of(
                reports.spendingByCategory(
                        caller.userId(), YearMonth.parse(from), YearMonth.parse(to)));
    }

    /** Report 2 as a CSV file: one record per category row, then the uncategorised outflow. */
    @GetMapping(path = "/spending-by-category", produces = TEXT_CSV)
    ResponseEntity<byte[]> spendingByCategoryCsv(
            TransportAuthentication caller,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String from,
            @RequestParam(required = false)
                    @NotNull
                    @Pattern(regexp = PERIOD, message = PERIOD_MESSAGE)
                    String to) {
        SpendingByCategoryResponse report =
                SpendingByCategoryResponse.of(
                        reports.spendingByCategory(
                                caller.userId(), YearMonth.parse(from), YearMonth.parse(to)));
        exported("spending-by-category", caller, report.from(), report.to());
        return csv(
                "spending-by-category",
                report.from(),
                report.to(),
                ReportCsv.spendingByCategory(report));
    }

    /**
     * An export is a business event: the household's figures are about to leave the browser's
     * protections for a disk. Logged by user, report and periods — the same facts the URL carries —
     * and never a figure or a name.
     */
    private static void exported(
            String report, TransportAuthentication caller, String from, String to) {
        log.info(
                "report exported report={} format=csv userId={} from={} to={}",
                report,
                caller.userId(),
                from,
                to);
    }

    /**
     * The file, with a name built only from the report and the two validated periods — no household
     * or category name ever goes into a header — and {@code no-store} set here as well as by the
     * security configuration, because the global rule is described in terms of JSON.
     */
    private static ResponseEntity<byte[]> csv(String report, String from, String to, byte[] body) {
        String filename = "budget-owl-" + report + "-" + from + "-" + to + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
