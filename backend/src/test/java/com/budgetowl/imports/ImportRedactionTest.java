package com.budgetowl.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportAmountLexer;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.domain.RawRow;
import com.budgetowl.imports.domain.ReadRow;
import com.budgetowl.imports.domain.RowProblem;
import com.budgetowl.imports.service.ImportPreview;
import com.budgetowl.imports.web.ImportRequests.CommitImportRequest;
import com.budgetowl.imports.web.ImportRequests.ImportOptionsRequest;
import com.budgetowl.imports.web.ImportRequests.OverrideRequest;
import com.budgetowl.imports.web.ImportRequests.PreviewImportRequest;
import com.budgetowl.imports.web.ImportRequests.SaveMappingRequest;
import com.budgetowl.imports.web.ImportRequests.SuspectedChoice;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every record that carries a statement's content prints none of it.
 *
 * <p>{@code ImportDisclosureIT} proves nothing reaches the log through the paths that run today. It
 * cannot prove a record is safe to log tomorrow: a framework logger that starts printing a request
 * body, or a {@code log.debug("{}", row)} somebody adds, would print whatever {@code toString}
 * says. So each record is built here holding sentinels — in plain text and, for the file, as the
 * base64 that actually travels — and its {@code toString} is checked directly (file-import.md,
 * "What reaches the logs").
 */
class ImportRedactionTest {

    private static final String PAYEE = "SENTINEL PAYEE RX9";
    private static final String NOTE = "SENTINEL NOTE RX9";
    private static final String ACCOUNT_NUMBER = "8877665544332211";
    private static final String AMOUNT = "3141.59";
    private static final String FILE =
            Base64.getEncoder()
                    .encodeToString(
                            ("<OFX><ACCTID>" + ACCOUNT_NUMBER + "</ACCTID><NAME>" + PAYEE)
                                    .getBytes(StandardCharsets.UTF_8));
    private static final List<String> SENTINELS =
            List.of(PAYEE, NOTE, ACCOUNT_NUMBER, AMOUNT, FILE, "RX9", "Debit RX9");

    @Test
    void requestsPrintLengthsAndCountsNeverTheFileOrTheMapping() {
        ImportOptionsRequest options =
                new ImportOptionsRequest(
                        "UTF-8",
                        ",",
                        1,
                        "uuuu-MM-dd",
                        ".",
                        null,
                        false,
                        Map.of("0", CsvColumnRole.DATE),
                        "In RX9",
                        "Out RX9",
                        true);

        assertRedacted(
                new PreviewImportRequest(UUID.randomUUID(), ImportFormat.OFX, FILE, options));
        assertRedacted(
                new CommitImportRequest(
                        UUID.randomUUID(),
                        ImportFormat.OFX,
                        FILE,
                        options,
                        "a".repeat(64),
                        SuspectedChoice.EXCLUDE,
                        List.of(new OverrideRequest(3, true))));
        assertRedacted(options);
        assertRedacted(
                new SaveMappingRequest(
                        null,
                        ",",
                        1,
                        "uuuu-MM-dd",
                        ".",
                        null,
                        false,
                        Map.of("0", CsvColumnRole.DATE),
                        "In RX9",
                        "Out RX9",
                        List.of("Debit RX9"),
                        0L));
    }

    @Test
    void rowsPrintTheirRecordNumberOnly() {
        assertRedacted(
                new RawRow(
                        7,
                        "2026-08-01",
                        AMOUNT,
                        null,
                        ImportAmountLexer.Direction.NONE,
                        PAYEE,
                        NOTE,
                        ACCOUNT_NUMBER,
                        "GBP",
                        null,
                        null,
                        null,
                        0,
                        1));
        assertRedacted(
                new ReadRow(
                        7,
                        ReadRow.Kind.UNREADABLE,
                        LocalDate.of(2026, 8, 1),
                        new BigDecimal(AMOUNT),
                        PAYEE,
                        false,
                        NOTE,
                        false,
                        PAYEE.toLowerCase(java.util.Locale.ROOT),
                        ACCOUNT_NUMBER,
                        RowProblem.DATE_UNREADABLE,
                        2,
                        "Debit RX9"));
    }

    @Test
    void thePreviewsPartsPrintNoPayeeNoteAmountOrBalance() {
        LocalDate day = LocalDate.of(2026, 8, 1);
        BigDecimal amount = new BigDecimal(AMOUNT);

        assertRedacted(new ImportPreview.AccountSummary(UUID.randomUUID(), PAYEE, "2211", "GBP"));
        assertRedacted(
                new ImportPreview.Statement(day, day, amount, FileReading.AccountHint.MATCHES));
        assertRedacted(new ImportPreview.Totals(amount, amount.negate(), "GBP"));
        assertRedacted(
                new ImportPreview.Attention(
                        3,
                        ImportPreview.AttentionKind.UNREADABLE,
                        day,
                        amount,
                        PAYEE,
                        "row-date-unreadable",
                        1,
                        "Debit RX9",
                        null));
        assertRedacted(new ImportPreview.Sample(4, day, amount, "GBP", PAYEE, NOTE, false, false));
        assertRedacted(new ImportPreview.Identical(day, amount, PAYEE, 2));
    }

    private static void assertRedacted(Object record) {
        String printed = record.toString();
        for (String sentinel : SENTINELS) {
            assertThat(printed)
                    .as("%s printed %s", record.getClass().getSimpleName(), sentinel)
                    .doesNotContain(sentinel);
        }
    }
}
