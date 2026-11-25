package com.budgetowl.imports.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Everything read from one file, before it is compared with the ledger.
 *
 * <p>When {@link #needs} is not empty, the person has a question to answer first — which charset,
 * which columns, which date format — and {@link #rows} is {@code null}: nothing is classified, and
 * there is no digest to commit against until they answer.
 *
 * @param charset the allowed name used, or {@code null} when the person must choose
 * @param charsetCandidates when they must, each single-byte candidate that decodes the file, with a
 *     few decoded sample cells so they can see which one reads correctly
 * @param dateFormat the format used, or {@code null} for OFX or when the person must choose
 * @param dateFormatCandidates the distinct readings, when there is more than one
 * @param bankClosingBalance OFX {@code LEDGERBAL}, exact, shown and <b>never stored</b>
 * @param accountHint whether the file's account number ends in the account's {@code lastFour}. The
 *     number itself never leaves this package
 * @param csv what the mapping step shows, for CSV only
 */
public record FileReading(
        ImportFormat format,
        int decodedBytes,
        String charset,
        CharsetSource charsetSource,
        List<CharsetCandidate> charsetCandidates,
        ImportDateFormat dateFormat,
        DateFormatSource dateFormatSource,
        List<ImportDateFormat> dateFormatCandidates,
        Set<Need> needs,
        List<ReadRow> rows,
        LocalDate from,
        LocalDate to,
        BigDecimal bankClosingBalance,
        AccountHint accountHint,
        CsvShape csv) {

    public FileReading {
        charsetCandidates = List.copyOf(charsetCandidates);
        dateFormatCandidates = List.copyOf(dateFormatCandidates);
        needs = Set.copyOf(needs);
        rows = rows == null ? null : List.copyOf(rows);
    }

    FileReading withCsv(CsvShape shape) {
        return new FileReading(
                format,
                decodedBytes,
                charset,
                charsetSource,
                charsetCandidates,
                dateFormat,
                dateFormatSource,
                dateFormatCandidates,
                needs,
                rows,
                from,
                to,
                bankClosingBalance,
                accountHint,
                shape);
    }

    FileReading withDateCandidates(List<ImportDateFormat> candidates) {
        return new FileReading(
                format,
                decodedBytes,
                charset,
                charsetSource,
                charsetCandidates,
                dateFormat,
                dateFormatSource,
                candidates,
                needs,
                rows,
                from,
                to,
                bankClosingBalance,
                accountHint,
                csv);
    }

    public enum Need {
        CHARSET,
        MAPPING,
        DATE_FORMAT
    }

    public enum CharsetSource {
        BOM,
        DECLARED,
        CHOSEN,
        DETECTED
    }

    public enum DateFormatSource {
        CHOSEN,
        DETECTED
    }

    /** {@code MATCHES}, {@code DIFFERS} or {@code UNKNOWN} — never a digit from the file. */
    public enum AccountHint {
        MATCHES,
        DIFFERS,
        UNKNOWN
    }

    /**
     * @param samples a few decoded cells that contain non-ASCII text, which is where two charsets
     *     disagree. The person's own data, returned to them
     */
    public record CharsetCandidate(String charset, List<String> samples) {

        public CharsetCandidate {
            samples = List.copyOf(samples);
        }

        @Override
        public String toString() {
            return "CharsetCandidate[" + charset + "]";
        }
    }

    /** Counts only. This holds a bank statement. */
    @Override
    public String toString() {
        return "FileReading[format="
                + format
                + ", rows="
                + (rows == null ? "none" : rows.size())
                + ", needs="
                + needs
                + "]";
    }
}
