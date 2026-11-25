package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.imports.domain.FileReading.AccountHint;
import com.budgetowl.imports.domain.FileReading.CharsetCandidate;
import com.budgetowl.imports.domain.FileReading.CharsetSource;
import com.budgetowl.imports.domain.FileReading.DateFormatSource;
import com.budgetowl.imports.domain.FileReading.Need;
import com.budgetowl.imports.domain.ImportAmountLexer.Lexed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Reads one statement file from its bytes, in the order docs/features/file-import.md decides.
 *
 * <ol>
 *   <li><b>Bytes are counted</b> before anything decodes them, and an archive is refused by its
 *       magic number.
 *   <li><b>The charset is decided</b> by an allowlist: a BOM, else a declaration that decodes, else
 *       the person's choice, else strict UTF-8, else the person is asked. Never guessed.
 *   <li>A NUL anywhere refuses the file. An empty one is refused.
 *   <li><b>The format is sniffed</b> and must be the one declared.
 *   <li><b>Lines are counted</b> for CSV and QIF before their parser runs.
 *   <li>The format's reader takes the file apart, within its caps and the parse deadline.
 *   <li>Every row is interpreted by one interpreter, whatever its format.
 * </ol>
 *
 * <p>Pure: no database, no Spring, no clock but the one it is given. The same bytes and options
 * read on the same day always produce the same rows, which is what lets the commit re-parse the
 * file and arrive at the preview's digest (ADR-0037).
 */
public final class ImportReader {

    /** Sample cells shown per candidate charset. */
    private static final int CHARSET_SAMPLES = 3;

    private final ImportLimits limits;
    private final Clock clock;
    private final LongSupplier nanoTime;

    public ImportReader(ImportLimits limits, Clock clock, LongSupplier nanoTime) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** The bounds this reader enforces, for a caller that has to check one before reading. */
    public ImportLimits limits() {
        return limits;
    }

    /**
     * @param declared the format the request says this is
     * @param currency the target account's
     * @throws ImportRefusal for every file-level refusal, each with its fixed code
     */
    public FileReading read(
            byte[] bytes, ImportFormat declared, Currency currency, ReadOptions options) {
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(options, "options");
        ParseDeadline deadline = new ParseDeadline(limits.parseDeadline(), nanoTime);

        if (bytes.length > limits.maximumBytes()) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FILE_TOO_LARGE);
        }
        if (bytes.length == 0) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FILE_EMPTY);
        }
        if (isArchive(bytes)) {
            throw ImportRefusal.malformed(MalformedReason.BINARY);
        }

        Decoded decoded = decode(bytes, options.charset());
        if (decoded.text() == null) {
            return asking(declared, bytes.length, Set.of(Need.CHARSET), decoded.candidates(), null);
        }
        String text = decoded.text();
        if (text.indexOf('\u0000') >= 0) {
            // Binary content, or UTF-16 read as something else.
            throw ImportRefusal.malformed(MalformedReason.BINARY);
        }
        if (text.isBlank()) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FILE_EMPTY);
        }

        FormatSniffer.Kind kind = FormatSniffer.sniff(text);
        boolean matches =
                switch (declared) {
                    case OFX ->
                            kind == FormatSniffer.Kind.OFX_SGML
                                    || kind == FormatSniffer.Kind.OFX_XML;
                    case QIF -> kind == FormatSniffer.Kind.QIF;
                    case CSV -> kind == FormatSniffer.Kind.OTHER;
                };
        if (!matches) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FORMAT_MISMATCH);
        }
        deadline.checkNow();

        ImportDateParser dates = new ImportDateParser(clock);
        StatementInterpreter interpreter = new StatementInterpreter(dates, deadline);
        return switch (declared) {
            case OFX ->
                    ofx(
                            text,
                            kind,
                            bytes.length,
                            decoded,
                            currency,
                            options,
                            deadline,
                            interpreter);
            case QIF ->
                    qif(
                            text,
                            bytes.length,
                            decoded,
                            currency,
                            options,
                            deadline,
                            dates,
                            interpreter);
            case CSV ->
                    csv(
                            text,
                            bytes.length,
                            decoded,
                            currency,
                            options,
                            deadline,
                            dates,
                            interpreter);
        };
    }

    // --------------------------------------------------------------------------------- formats

    private FileReading ofx(
            String text,
            FormatSniffer.Kind kind,
            int size,
            Decoded decoded,
            Currency currency,
            ReadOptions options,
            ParseDeadline deadline,
            StatementInterpreter interpreter) {
        RawStatement statement =
                kind == FormatSniffer.Kind.OFX_SGML
                        ? new OfxSgmlReader(limits, deadline).read(text)
                        : new OfxXmlReader(limits, deadline).read(text);
        if (!statement.currency().strip().equalsIgnoreCase(currency.getCurrencyCode())) {
            throw ImportRefusal.of(ErrorCode.IMPORT_CURRENCY_MISMATCH);
        }
        List<ReadRow> rows =
                interpreter.interpret(
                        statement,
                        ImportFormat.OFX,
                        null,
                        ImportAmountLexer.Style.OFX,
                        currency,
                        options.invertSign());
        BigDecimal closing = null;
        if (statement.ledgerBalance() != null) {
            Lexed lexed =
                    ImportAmountLexer.lex(
                            statement.ledgerBalance(),
                            ImportAmountLexer.Style.OFX,
                            currency,
                            ImportAmountLexer.Direction.NONE);
            closing = lexed instanceof Lexed.Amount amount ? amount.value() : null;
        }
        return reading(
                ImportFormat.OFX,
                size,
                decoded,
                null,
                null,
                rows,
                closing,
                hint(statement.accountIdentifier(), options.lastFour()),
                null);
    }

    private FileReading qif(
            String text,
            int size,
            Decoded decoded,
            Currency currency,
            ReadOptions options,
            ParseDeadline deadline,
            ImportDateParser dates,
            StatementInterpreter interpreter) {
        prescan(text, limits.qifMaximumLines());
        RawStatement statement = new QifReader(limits, deadline).read(text);
        DateChoice choice = chooseDateFormat(statement, options.dateFormat(), dates, true);
        if (choice.format() == null) {
            return asking(ImportFormat.QIF, size, Set.of(Need.DATE_FORMAT), List.of(), decoded)
                    .withDateCandidates(choice.candidates());
        }
        List<ReadRow> rows =
                interpreter.interpret(
                        statement,
                        ImportFormat.QIF,
                        choice.format(),
                        ImportAmountLexer.Style.QIF,
                        currency,
                        options.invertSign());
        return reading(
                ImportFormat.QIF,
                size,
                decoded,
                choice.format(),
                choice.source(),
                rows,
                null,
                AccountHint.UNKNOWN,
                null);
    }

    private FileReading csv(
            String text,
            int size,
            Decoded decoded,
            Currency currency,
            ReadOptions options,
            ParseDeadline deadline,
            ImportDateParser dates,
            StatementInterpreter interpreter) {
        prescan(text, limits.csvMaximumLines());
        CsvReader.Result result =
                new CsvReader(limits, deadline, dates)
                        .read(
                                text,
                                options.mapping(),
                                options.csvDelimiter(),
                                options.csvHeaderRow());
        if (options.mapping() == null) {
            return asking(ImportFormat.CSV, size, Set.of(Need.MAPPING), List.of(), decoded)
                    .withCsv(result.shape());
        }
        RawStatement statement = result.statement();
        DateChoice choice =
                chooseDateFormat(
                        statement,
                        options.dateFormat() != null
                                ? options.dateFormat()
                                : options.mapping().dateFormat(),
                        dates,
                        false);
        if (choice.format() == null) {
            return asking(ImportFormat.CSV, size, Set.of(Need.DATE_FORMAT), List.of(), decoded)
                    .withCsv(result.shape())
                    .withDateCandidates(choice.candidates());
        }
        List<ReadRow> rows =
                interpreter.interpret(
                        statement,
                        ImportFormat.CSV,
                        choice.format(),
                        options.mapping().amountStyle(),
                        currency,
                        options.invertSign());
        return reading(
                ImportFormat.CSV,
                size,
                decoded,
                choice.format(),
                choice.source(),
                rows,
                null,
                AccountHint.UNKNOWN,
                result.shape());
    }

    // ----------------------------------------------------------------------------- charset

    /** The decoded text and how its charset was decided, or the candidates to ask about. */
    private record Decoded(
            String text, String charset, CharsetSource source, List<CharsetCandidate> candidates) {}

    private static Decoded decode(byte[] bytes, String chosen) {
        Optional<ImportCharsets.Bom> bom = ImportCharsets.bomOf(bytes);
        if (bom.isPresent()) {
            String charset = bom.get().charset();
            String text =
                    ImportCharsets.decode(bytes, bom.get().length(), charset)
                            // A BOM that promises an encoding the bytes do not follow is not
                            // text in any charset we could offer instead.
                            .orElseThrow(() -> ImportRefusal.malformed(MalformedReason.BINARY));
            return new Decoded(text, charset, CharsetSource.BOM, List.of());
        }
        Optional<String> declared = FormatSniffer.declaredCharset(bytes);
        if (declared.isPresent()) {
            Optional<String> text = ImportCharsets.decode(bytes, 0, declared.get());
            if (text.isPresent()) {
                return new Decoded(text.get(), declared.get(), CharsetSource.DECLARED, List.of());
            }
            // A declaration the bytes do not follow decides nothing; fall through to the person.
        }
        if (chosen != null) {
            Optional<String> text = ImportCharsets.decode(bytes, 0, chosen);
            if (text.isPresent()) {
                return new Decoded(stripBom(text.get()), chosen, CharsetSource.CHOSEN, List.of());
            }
        }
        Optional<String> utf8 = ImportCharsets.decode(bytes, 0, "UTF-8");
        if (utf8.isPresent()) {
            return new Decoded(utf8.get(), "UTF-8", CharsetSource.DETECTED, List.of());
        }
        return new Decoded(null, null, null, candidates(bytes));
    }

    /** Each single-byte charset that decodes the file, with the cells where they disagree. */
    private static List<CharsetCandidate> candidates(byte[] bytes) {
        List<CharsetCandidate> candidates = new ArrayList<>();
        for (String charset : ImportCharsets.SINGLE_BYTE_CANDIDATES) {
            Optional<String> text = ImportCharsets.decode(bytes, 0, charset);
            if (text.isEmpty()) {
                continue;
            }
            List<String> samples = new ArrayList<>(CHARSET_SAMPLES);
            for (String line : text.get().split("\\r\\n|\\n|\\r", 2000)) {
                if (samples.size() == CHARSET_SAMPLES) {
                    break;
                }
                if (line.chars().anyMatch(character -> character > 0x7F)) {
                    samples.add(ImportText.cut(ImportText.normalise(line), 64));
                }
            }
            candidates.add(new CharsetCandidate(charset, samples));
        }
        return candidates;
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // ----------------------------------------------------------------------------- internals

    /**
     * Counts physical lines and their length before any CSV or QIF parser runs. Bounded by the
     * file's own length, which the byte cap already bounded.
     */
    private void prescan(String text, int maximumLines) {
        int lines = 0;
        int lineLength = 0;
        int length = text.length();
        for (int index = 0; index < length; index++) {
            char character = text.charAt(index);
            if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < length && text.charAt(index + 1) == '\n') {
                    index++;
                }
                lines++;
                lineLength = 0;
                if (lines > maximumLines) {
                    throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
                }
                continue;
            }
            if (++lineLength > limits.maximumLineLength()) {
                throw ImportRefusal.malformed(MalformedReason.LINE_TOO_LONG);
            }
        }
        if (lineLength > 0 && lines + 1 > maximumLines) {
            throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
        }
    }

    private record DateChoice(
            ImportDateFormat format, DateFormatSource source, List<ImportDateFormat> candidates) {}

    /**
     * The chosen format; or the one format that reads every row to one set of dates; or nothing,
     * with the candidates, when that is the person's to resolve (rule 6).
     */
    private static DateChoice chooseDateFormat(
            RawStatement statement, ImportDateFormat chosen, ImportDateParser dates, boolean qif) {
        if (chosen != null) {
            return new DateChoice(chosen, DateFormatSource.CHOSEN, List.of());
        }
        List<String> cells = new ArrayList<>();
        for (RawRow row : statement.rows()) {
            if (row.problem() == null && row.date() != null && !row.date().isBlank()) {
                cells.add(row.date());
            }
        }
        if (cells.isEmpty()) {
            // Nothing to read, so nothing to be wrong about.
            return new DateChoice(ImportDateFormat.ISO, DateFormatSource.DETECTED, List.of());
        }
        List<ImportDateFormat> readings = dates.readingsOfAll(cells, qif);
        if (readings.size() == 1) {
            return new DateChoice(readings.get(0), DateFormatSource.DETECTED, List.of());
        }
        return new DateChoice(
                null, null, readings.isEmpty() ? List.of(ImportDateFormat.values()) : readings);
    }

    /** {@code MATCHES}, {@code DIFFERS} or {@code UNKNOWN}. The number is not kept. */
    static AccountHint hint(String accountIdentifier, String lastFour) {
        if (accountIdentifier == null || lastFour == null) {
            return AccountHint.UNKNOWN;
        }
        String number = accountIdentifier.strip();
        if (number.length() < 4) {
            return AccountHint.UNKNOWN;
        }
        String ending = number.substring(number.length() - 4);
        for (int index = 0; index < ending.length(); index++) {
            char character = ending.charAt(index);
            if (character < '0' || character > '9') {
                // A masked or alphanumeric identifier: no honest answer either way.
                return AccountHint.UNKNOWN;
            }
        }
        return ending.equals(lastFour) ? AccountHint.MATCHES : AccountHint.DIFFERS;
    }

    /** Zip, gzip, bzip2, 7z, xz, zstd and RAR, by their first bytes. */
    private static boolean isArchive(byte[] bytes) {
        return startsWith(bytes, 0x50, 0x4B, 0x03, 0x04)
                || startsWith(bytes, 0x50, 0x4B, 0x05, 0x06)
                || startsWith(bytes, 0x1F, 0x8B)
                || startsWith(bytes, 0x42, 0x5A, 0x68)
                || startsWith(bytes, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
                || startsWith(bytes, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)
                || startsWith(bytes, 0x28, 0xB5, 0x2F, 0xFD)
                || startsWith(bytes, 0x52, 0x61, 0x72, 0x21);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static FileReading asking(
            ImportFormat format,
            int size,
            Set<Need> needs,
            List<CharsetCandidate> candidates,
            Decoded decoded) {
        return new FileReading(
                format,
                size,
                decoded == null ? null : decoded.charset(),
                decoded == null ? null : decoded.source(),
                candidates,
                null,
                null,
                List.of(),
                needs,
                null,
                null,
                null,
                null,
                AccountHint.UNKNOWN,
                null);
    }

    private static FileReading reading(
            ImportFormat format,
            int size,
            Decoded decoded,
            ImportDateFormat dateFormat,
            DateFormatSource dateSource,
            List<ReadRow> rows,
            BigDecimal closing,
            AccountHint hint,
            CsvShape csv) {
        LocalDate from = null;
        LocalDate to = null;
        for (ReadRow row : rows) {
            if (row.date() == null) {
                continue;
            }
            from = from == null || row.date().isBefore(from) ? row.date() : from;
            to = to == null || row.date().isAfter(to) ? row.date() : to;
        }
        return new FileReading(
                format,
                size,
                decoded.charset(),
                decoded.source(),
                List.of(),
                dateFormat,
                dateSource,
                List.of(),
                Set.of(),
                rows,
                from,
                to,
                closing,
                hint,
                csv);
    }
}
