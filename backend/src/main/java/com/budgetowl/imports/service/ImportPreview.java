package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.CsvShape;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a preview shows: everything the person needs to decide, and nothing written
 * (docs/features/file-import.md, "Response: preview").
 *
 * <p>When {@link #needs} is not empty — a charset, a mapping or a date format must be chosen first
 * — there is no digest, no counts and no rows: nothing has been classified, so there is nothing to
 * commit against.
 *
 * <p><b>Bounded</b>, although it is a list-shaped response: {@code attention} holds at most {@link
 * ImportService#ATTENTION_LIMIT} rows, {@code sample} at most {@link ImportService#SAMPLE_SIZE}. It
 * is the result of an action on one upload, not a stored collection, and paging it would need the
 * server-side state ADR-0037 avoids.
 *
 * <p>Everything in it is the uploader's own data returned to them over TLS with {@code
 * Cache-Control: no-store}. {@link #toString} prints counts only.
 *
 * @param balanceNow the account's balance in the ledger today
 * @param balanceAfter with every new row imported and suspected ones left out, the default
 * @param balanceAfterWithSuspected with suspected ones included too
 */
public record ImportPreview(
        String previewDigest,
        AccountSummary account,
        ImportFormat format,
        int decodedBytes,
        String charset,
        FileReading.CharsetSource charsetSource,
        List<FileReading.CharsetCandidate> charsetCandidates,
        List<String> charsetsAllowed,
        String dateFormat,
        FileReading.DateFormatSource dateFormatSource,
        List<String> dateFormatCandidates,
        Set<FileReading.Need> needs,
        Statement statement,
        Counts counts,
        Totals totals,
        BigDecimal balanceNow,
        BigDecimal balanceAfter,
        BigDecimal balanceAfterWithSuspected,
        List<Attention> attention,
        boolean attentionTruncated,
        List<Sample> sample,
        List<Identical> identical,
        boolean reusedIdentifier,
        boolean textCut,
        CsvShape csv,
        Boolean mappingMatchesSaved) {

    /** The account, as the confirm button names it. */
    public record AccountSummary(UUID id, String name, String lastFour, String currency) {

        @Override
        public String toString() {
            return "AccountSummary[" + id + "]";
        }
    }

    /**
     * @param bankClosingBalance OFX {@code LEDGERBAL}: "your bank says". Returned, never stored
     */
    public record Statement(
            LocalDate from,
            LocalDate to,
            BigDecimal bankClosingBalance,
            FileReading.AccountHint accountHint) {

        @Override
        public String toString() {
            return "Statement[hint=" + accountHint + "]";
        }
    }

    public record Counts(
            int read, int fresh, int duplicate, int suspected, int unreadable, int zero) {}

    /** Of the rows imported by default. Never summed across currencies: there is only one. */
    public record Totals(BigDecimal moneyIn, BigDecimal moneyOut, String currency) {

        @Override
        public String toString() {
            return "Totals[" + currency + "]";
        }
    }

    public enum AttentionKind {
        SUSPECTED_DUPLICATE,
        DUPLICATE,
        UNREADABLE
    }

    /**
     * A row that needs a decision or an explanation.
     *
     * @param code for an unreadable row, its {@code row-*} code
     * @param column for an unreadable CSV row, the column the problem is in
     * @param text for an unreadable row, the offending cell, normalised and cut to 64 characters
     * @param resembles the ledger row a suspected duplicate looks like, or the one an exact
     *     duplicate already is
     */
    public record Attention(
            int row,
            AttentionKind kind,
            LocalDate date,
            BigDecimal amount,
            String payeeName,
            String code,
            Integer column,
            String text,
            Resembles resembles) {

        @Override
        public String toString() {
            return "Attention[row=" + row + ", " + kind + "]";
        }
    }

    public record Resembles(UUID transactionId, LocalDate date) {}

    /** A new row, in the ledger's own shape so the screen can lay it out the same way. */
    public record Sample(
            int row,
            LocalDate date,
            BigDecimal amount,
            String currency,
            String payeeName,
            String note,
            boolean payeeCut,
            boolean noteCut) {

        @Override
        public String toString() {
            return "Sample[row=" + row + "]";
        }
    }

    /**
     * Genuinely identical new rows — the same date, amount and payee — which all import. Said out
     * loud, so nobody excludes one thinking it is a mistake (rule 9).
     */
    public record Identical(LocalDate date, BigDecimal amount, String payeeName, int count) {

        @Override
        public String toString() {
            return "Identical[count=" + count + "]";
        }
    }

    @Override
    public String toString() {
        return "ImportPreview[format=" + format + ", needs=" + needs + ", counts=" + counts + "]";
    }
}
