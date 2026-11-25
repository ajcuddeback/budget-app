package com.budgetowl.report.web;

import com.budgetowl.common.csv.CsvWriter;
import com.budgetowl.common.csv.CsvWriter.Column;
import com.budgetowl.report.web.ReportResponses.CategorySpendingResponse;
import com.budgetowl.report.web.ReportResponses.CurrencyIncomeAndSpendingResponse;
import com.budgetowl.report.web.ReportResponses.CurrencySpendingResponse;
import com.budgetowl.report.web.ReportResponses.IncomeAndSpendingResponse;
import com.budgetowl.report.web.ReportResponses.MonthResponse;
import com.budgetowl.report.web.ReportResponses.SpendingByCategoryResponse;
import java.util.Arrays;

/**
 * The CSV representation of the two reports (decision 8).
 *
 * <p><b>Built from the same response objects the JSON is</b>, so the export and the on-screen table
 * cannot disagree: they are two renderings of one value. Amounts are the wire's plain decimal
 * strings, periods are {@code 2026-08}, headers are column codes and not prose (ADR-0023).
 *
 * <p>All quoting, the byte-order mark and the formula-injection escaping are {@link CsvWriter}'s.
 * The only decision made here is <b>which columns are text</b>: the category and parent names,
 * which a household typed. Every other column is typed and validated before it is written.
 */
final class ReportCsv {

    /** The CSV rows of report 2 are of three kinds, named by a code rather than by prose. */
    static final String CATEGORY = "CATEGORY";

    static final String SUBCATEGORY = "SUBCATEGORY";
    static final String UNCATEGORISED = "UNCATEGORISED";

    private ReportCsv() {}

    /** One record per currency per month: exactly the "Show the figures" table. */
    static byte[] incomeAndSpending(IncomeAndSpendingResponse report) {
        CsvWriter csv =
                CsvWriter.withColumns(
                        Column.currency("currency"),
                        Column.period("period"),
                        Column.amount("income"),
                        Column.amount("spending"),
                        Column.amount("uncategorisedIn"),
                        Column.amount("uncategorisedOut"),
                        Column.amount("kept"),
                        Column.bool("complete"));
        for (CurrencyIncomeAndSpendingResponse currency : report.currencies()) {
            for (MonthResponse month : currency.months()) {
                csv.row(
                        currency.currency(),
                        month.period(),
                        month.income(),
                        month.spending(),
                        month.uncategorisedIn(),
                        month.uncategorisedOut(),
                        month.kept(),
                        Boolean.toString(month.complete()));
            }
        }
        return csv.toBytes();
    }

    /**
     * One record per category row, children after their parent, then the uncategorised outflow, per
     * currency.
     */
    static byte[] spendingByCategory(SpendingByCategoryResponse report) {
        CsvWriter csv =
                CsvWriter.withColumns(
                        Column.currency("currency"),
                        Column.period("from"),
                        Column.period("to"),
                        Column.period("comparedFrom"),
                        Column.period("comparedTo"),
                        Column.code("row"),
                        Column.text("category"),
                        Column.text("parent"),
                        Column.bool("archived"),
                        Column.amount("spending"),
                        Column.amount("comparedSpending"),
                        Column.ratio("change"),
                        Column.ratio("share"),
                        Column.amount("direct"),
                        Column.count("count"));
        for (CurrencySpendingResponse currency : report.currencies()) {
            for (CategorySpendingResponse top : currency.categories()) {
                category(csv, report, currency, CATEGORY, top, null);
                for (CategorySpendingResponse child : top.children()) {
                    category(csv, report, currency, SUBCATEGORY, child, top.name());
                }
            }
            csv.row(
                    Arrays.asList(
                            currency.currency(),
                            report.from(),
                            report.to(),
                            report.comparedFrom(),
                            report.comparedTo(),
                            UNCATEGORISED,
                            null,
                            null,
                            null,
                            currency.uncategorised().spending(),
                            null,
                            null,
                            null,
                            null,
                            Long.toString(currency.uncategorised().count())));
        }
        return csv.toBytes();
    }

    private static void category(
            CsvWriter csv,
            SpendingByCategoryResponse report,
            CurrencySpendingResponse currency,
            String kind,
            CategorySpendingResponse row,
            String parent) {
        csv.row(
                Arrays.asList(
                        currency.currency(),
                        report.from(),
                        report.to(),
                        report.comparedFrom(),
                        report.comparedTo(),
                        kind,
                        row.name(),
                        parent,
                        Boolean.toString(row.archived()),
                        row.spending(),
                        row.comparedSpending(),
                        row.change(),
                        row.share(),
                        row.direct(),
                        null));
    }
}
