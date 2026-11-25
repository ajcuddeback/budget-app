package com.budgetowl.imports.domain;

import static com.budgetowl.imports.domain.ImportFixtures.CLOCK;
import static com.budgetowl.imports.domain.ImportFixtures.GBP;
import static com.budgetowl.imports.domain.ImportFixtures.lastFour;
import static com.budgetowl.imports.domain.ImportFixtures.none;
import static com.budgetowl.imports.domain.ImportFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reader, from bytes, in the order the feature doc decides: size, charset, NUL, format, lines,
 * then the parser (docs/features/file-import.md rules 2–8 and the threat model).
 */
class ImportReaderTest {

    private static final String OFX1_HEADER =
            """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            """;

    // -------------------------------------------------------------------------------- happy paths

    @Test
    void readsAnOfx1QfxAndKeepsNothingButTheRows() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("ofx1-happy.qfx"),
                                ImportFormat.OFX,
                                GBP,
                                lastFour("2211"));

        assertThat(reading.needs()).isEmpty();
        assertThat(reading.charset()).isEqualTo("windows-1252");
        assertThat(reading.charsetSource()).isEqualTo(FileReading.CharsetSource.DECLARED);
        assertThat(reading.rows()).hasSize(5);
        assertThat(reading.rows().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(reading.rows().get(0).amount()).isEqualByComparingTo("-3.50");
        assertThat(reading.rows().get(0).payee()).isEqualTo("BLUE BOTTLE");
        assertThat(reading.rows().get(2).note()).isEqualTo("August salary");
        // &amp; decoded; the memo only repeats the payee, so it is dropped.
        assertThat(reading.rows().get(3).payee()).isEqualTo("CORNER SHOP & DELI");
        assertThat(reading.rows().get(3).note()).isNull();
        assertThat(reading.bankClosingBalance()).isEqualByComparingTo("-700.79");
        assertThat(reading.accountHint()).isEqualTo(FileReading.AccountHint.MATCHES);
        assertThat(reading.toString())
                .doesNotContain("8877665544332211")
                .doesNotContain("sentinel.userid.qx7")
                .doesNotContain("BLUE BOTTLE");
    }

    @Test
    void theAccountHintDiffersWithoutSayingWhatTheNumberWas() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("ofx1-happy.qfx"),
                                ImportFormat.OFX,
                                GBP,
                                lastFour("4412"));

        assertThat(reading.accountHint()).isEqualTo(FileReading.AccountHint.DIFFERS);
        assertThat(
                        reader().read(
                                        ImportFixtures.bytes("ofx1-happy.qfx"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none())
                                .accountHint())
                .isEqualTo(FileReading.AccountHint.UNKNOWN);
    }

    @Test
    void readsAnOfx2CardStatementInUtf8() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("ofx2-happy.ofx"),
                                ImportFormat.OFX,
                                GBP,
                                lastFour("4412"));

        assertThat(reading.charset()).isEqualTo("UTF-8");
        assertThat(reading.rows()).hasSize(2);
        assertThat(reading.rows().get(0).date())
                .as("23:30 in California on 31 July stays on 31 July")
                .isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(reading.rows().get(0).payee()).isEqualTo("Café Olé");
        assertThat(reading.rows().get(1).payee()).isEqualTo("CARD PAYMENT THANK YOU");
        assertThat(reading.accountHint()).isEqualTo(FileReading.AccountHint.MATCHES);
    }

    @Test
    void readsQifWithSplitsAsOneRowAndDropsTheAddress() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("qif-happy.qif"),
                                ImportFormat.QIF,
                                GBP,
                                new ReadOptions(
                                        null,
                                        ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null));

        assertThat(reading.rows()).hasSize(4);
        assertThat(reading.rows().get(1).amount()).isEqualByComparingTo("2400.00");
        assertThat(reading.rows().get(2).amount())
                .as("a split record imports as its total")
                .isEqualByComparingTo("-54.20");
        assertThat(reading.rows().get(3).payee())
                .as("a final record with no closing caret is accepted")
                .isEqualTo("MISSING CARET");
        assertThat(reading.rows())
                .noneMatch(row -> String.valueOf(row.note()).contains("Example Street"));
    }

    @Test
    void readsCsvWithItsMappingAndMarksTheZeroAndTheBadDate() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("csv-happy.csv"),
                                ImportFormat.CSV,
                                GBP,
                                csv(csvMapping(ImportDateFormat.DAY_MONTH_YEAR)));

        assertThat(reading.rows()).hasSize(6);
        assertThat(reading.rows().get(2).payee()).isEqualTo("ACME PAYROLL, AUGUST");
        assertThat(reading.rows().get(2).amount()).isEqualByComparingTo("2400.00");
        assertThat(reading.rows().get(3).payee()).isEqualTo("CORNER \"SHOP\"");
        assertThat(reading.rows().get(4).kind()).isEqualTo(ReadRow.Kind.ZERO);
        ReadRow bad = reading.rows().get(5);
        assertThat(bad.kind()).isEqualTo(ReadRow.Kind.UNREADABLE);
        assertThat(bad.problem()).isEqualTo(RowProblem.DATE_UNREADABLE);
        assertThat(bad.column()).isZero();
        assertThat(bad.text()).isEqualTo("31/02/2026");
        assertThat(bad.record()).as("counting the header, so the person can find it").isEqualTo(7);
    }

    @Test
    void describesACsvWithNoMappingAndSuggestsOneWithoutApplyingIt() {
        FileReading reading =
                reader().read(ImportFixtures.bytes("csv-happy.csv"), ImportFormat.CSV, GBP, none());

        assertThat(reading.needs()).containsExactly(FileReading.Need.MAPPING);
        assertThat(reading.rows()).isNull();
        CsvShape shape = reading.csv();
        assertThat(shape.columnCount()).isEqualTo(4);
        assertThat(shape.headerLabels())
                .containsExactly("Date", "Description", "Reference", "Amount");
        assertThat(shape.firstRecords()).hasSize(5);
        assertThat(shape.suggested().delimiter()).isEqualTo(',');
        assertThat(shape.suggested().headerRow()).isEqualTo(1);
        assertThat(shape.suggested().columns())
                .containsEntry(0, CsvColumnRole.DATE)
                .containsEntry(1, CsvColumnRole.PAYEE)
                .containsEntry(3, CsvColumnRole.AMOUNT);
        assertThat(shape.suggested().groupingSeparator()).isEqualTo(',');
    }

    @Test
    void anAmbiguousDateFormatIsAskedForAndNothingIsRead() {
        String file = "Date,Amount\n03/08/2026,-1.00\n05/07/2026,-2.00\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        null,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        FileReading reading = reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping));

        assertThat(reading.needs()).containsExactly(FileReading.Need.DATE_FORMAT);
        assertThat(reading.dateFormat()).isNull();
        assertThat(reading.dateFormatCandidates())
                .contains(ImportDateFormat.DAY_MONTH_YEAR, ImportDateFormat.MONTH_DAY_YEAR);
        assertThat(reading.rows()).isNull();
    }

    // ------------------------------------------------------------------------------ size and shape

    @Test
    void refusesAFileOverTheCapBeforeDecodingIt() {
        byte[] file = new byte[ImportLimits.FIVE_MIB + 1];

        assertRefused(
                () -> reader().read(file, ImportFormat.CSV, GBP, none()),
                ErrorCode.IMPORT_FILE_TOO_LARGE);
    }

    @Test
    void refusesAnEmptyOrBlankFile() {
        assertRefused(
                () -> reader().read(new byte[0], ImportFormat.CSV, GBP, none()),
                ErrorCode.IMPORT_FILE_EMPTY);
        assertRefused(
                () -> reader().read(utf8(" \r\n\t "), ImportFormat.CSV, GBP, none()),
                ErrorCode.IMPORT_FILE_EMPTY);
    }

    @Test
    void refusesANulAnywhere() {
        assertMalformed(
                () ->
                        reader().read(
                                        utf8("Date,Amount\n2026-08-01,\u00001\n"),
                                        ImportFormat.CSV,
                                        GBP,
                                        none()),
                "binary");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "504B0304",
                "1F8B0800",
                "425A6839",
                "377ABCAF271C",
                "FD377A585A00",
                "28B52FFD",
                "52617221"
            })
    void refusesAnArchive(String magic) {
        byte[] file = java.util.HexFormat.of().parseHex(magic + "00000000");

        assertMalformed(() -> reader().read(file, ImportFormat.CSV, GBP, none()), "binary");
    }

    @Test
    void refusesADeclaredFormatTheBytesAreNot() {
        assertRefused(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("qif-happy.qif"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_FORMAT_MISMATCH);
        assertRefused(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("ofx1-happy.qfx"),
                                        ImportFormat.CSV,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_FORMAT_MISMATCH);
        assertRefused(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("csv-happy.csv"),
                                        ImportFormat.QIF,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_FORMAT_MISMATCH);
    }

    @Test
    void refusesAHugeLineQuicklyBeforeTheCsvReaderSeesIt() {
        String file = "Date,Amount\n" + "x".repeat(4 * 1024 * 1024) + "\n";
        long started = System.nanoTime();

        assertMalformed(
                () -> reader().read(utf8(file), ImportFormat.CSV, GBP, none()), "line-too-long");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void refusesFourMillionEmptyLinesQuickly() {
        String file = "Date,Amount\n" + "\n".repeat(4_000_000);
        long started = System.nanoTime();

        assertRefused(
                () -> reader().read(utf8(file), ImportFormat.CSV, GBP, none()),
                ErrorCode.IMPORT_TOO_MANY_ROWS);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void refusesTenThousandColumns() {
        String file = "a" + ",a".repeat(9_999) + "\n";
        // 20,000 characters is over the line cap too, so cap the line first to reach the columns.
        ImportLimits wide =
                new ImportLimits(
                        ImportLimits.FIVE_MIB,
                        12_000,
                        200_000,
                        100_000,
                        10_000,
                        64,
                        32,
                        200_000,
                        64,
                        1024,
                        Duration.ofSeconds(15));
        ImportReader reader = new ImportReader(wide, CLOCK, System::nanoTime);

        assertMalformed(
                () ->
                        reader.read(
                                utf8(file),
                                ImportFormat.CSV,
                                GBP,
                                new ReadOptions(null, null, false, null, ',', null, null)),
                "too-many-columns");
    }

    @Test
    void refusesAnUnclosedQuoteWithoutSwallowingTheRestOfTheFile() {
        StringBuilder file =
                new StringBuilder("Date,Description,Amount\n2026-08-01,\"never closed,-1.00\n");
        for (int row = 0; row < 2000; row++) {
            file.append("2026-08-02,shop,-1.00\n");
        }
        long started = System.nanoTime();

        assertMalformed(
                () -> reader().read(utf8(file.toString()), ImportFormat.CSV, GBP, csv(csvLike())),
                "unclosed-quote");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void refusesTenThousandAndOneRowsWholeRatherThanTruncating() {
        StringBuilder file = new StringBuilder();
        for (int row = 0; row < 10_001; row++) {
            file.append("2026-08-01,-1.00\n");
        }
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        0,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        assertRefused(
                () -> reader().read(utf8(file.toString()), ImportFormat.CSV, GBP, csv(mapping)),
                ErrorCode.IMPORT_TOO_MANY_ROWS);
    }

    @Test
    void refusesTenThousandAndOneOfxTransactions() {
        StringBuilder body =
                new StringBuilder(
                        OFX1_HEADER
                                + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>GBP<BANKTRANLIST>");
        for (int row = 0; row < 10_001; row++) {
            body.append("<STMTTRN><DTPOSTED>20260801<TRNAMT>-1.00<FITID>F")
                    .append(row)
                    .append("</STMTTRN>");
        }
        body.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>");

        assertRefused(
                () -> reader().read(utf8(body.toString()), ImportFormat.OFX, GBP, none()),
                ErrorCode.IMPORT_TOO_MANY_ROWS);
    }

    @Test
    void refusesAFileThatOutrunsTheParseDeadline() {
        AtomicLong now = new AtomicLong();
        // Every read of the clock moves it a second: the 15-second deadline passes partway through.
        ImportReader slow =
                new ImportReader(ImportLimits.DECIDED, CLOCK, () -> now.getAndAdd(1_000_000_000L));
        StringBuilder file = new StringBuilder();
        for (int row = 0; row < 9_000; row++) {
            file.append("2026-08-01,-1.00\n");
        }
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        0,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        assertRefused(
                () -> slow.read(utf8(file.toString()), ImportFormat.CSV, GBP, csv(mapping)),
                ErrorCode.IMPORT_FILE_TOO_COMPLEX);
    }

    // ----------------------------------------------------------------------------------- OFX shape

    @Test
    void refusesSgmlDoctypeEntityAndMarkedSectionsAsDtd() {
        for (String fixture : List.of("ofx1-doctype.ofx", "ofx1-entity.ofx", "ofx1-cdata.ofx")) {
            assertMalformed(
                    () ->
                            reader().read(
                                            ImportFixtures.bytes(fixture),
                                            ImportFormat.OFX,
                                            GBP,
                                            none()),
                    "dtd");
        }
    }

    @Test
    void anUnknownSgmlEntityMarksTheRowAndABareAmpersandIsText() {
        FileReading reading =
                reader().read(
                                ImportFixtures.bytes("ofx1-unknown-entity.ofx"),
                                ImportFormat.OFX,
                                GBP,
                                none());

        assertThat(reading.rows().get(0).problem()).isEqualTo(RowProblem.ENTITY_UNKNOWN);
        assertThat(reading.rows().get(1).payee()).isEqualTo("AT&T");
    }

    @Test
    void refusesEncryptedCompressedAndBadHeaders() {
        assertMalformed(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace(
                                                                "SECURITY:NONE", "SECURITY:TYPE1")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                "encrypted");
        assertMalformed(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace(
                                                                "COMPRESSION:NONE",
                                                                "COMPRESSION:GZIP")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                "compressed");
        assertMalformed(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace(
                                                                "NEWFILEUID:NONE",
                                                                "NEWFILEUID:NONE\nEXTRA:1")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                "header");
        assertMalformed(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace("VERSION:102\n", "")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                "header");
        assertMalformed(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace(
                                                                "SECURITY:NONE",
                                                                "SECURITY:NONE\nSECURITY:NONE")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                "header");
    }

    @Test
    void refusesTwoStatementsInOneFile() {
        String one =
                "<STMTTRNRS><STMTRS><CURDEF>GBP<BANKTRANLIST></BANKTRANLIST></STMTRS></STMTTRNRS>";
        String file = OFX1_HEADER + "<OFX><BANKMSGSRSV1>" + one + one + "</BANKMSGSRSV1></OFX>";

        assertRefused(
                () -> reader().read(utf8(file), ImportFormat.OFX, GBP, none()),
                ErrorCode.IMPORT_MULTIPLE_ACCOUNTS);
    }

    @Test
    void refusesAnInvestmentStatement() {
        String file =
                OFX1_HEADER
                        + "<OFX><INVSTMTMSGSRSV1><INVSTMTTRNRS><INVSTMTRS><CURDEF>GBP</INVSTMTRS></INVSTMTTRNRS></INVSTMTMSGSRSV1></OFX>";

        assertMalformed(
                () -> reader().read(utf8(file), ImportFormat.OFX, GBP, none()), "not-a-statement");
    }

    @Test
    void refusesAStatementInAnotherCurrency() {
        String file =
                OFX1_HEADER
                        + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>USD<BANKTRANLIST></BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";

        assertRefused(
                () -> reader().read(utf8(file), ImportFormat.OFX, GBP, none()),
                ErrorCode.IMPORT_CURRENCY_MISMATCH);
    }

    @Test
    void refusesDepthElementCountAndLongLeavesWithinTheirCaps() {
        String deep = OFX1_HEADER + "<OFX>" + "<A>".repeat(40) + "x" + "</A>".repeat(40) + "</OFX>";
        assertMalformed(() -> reader().read(utf8(deep), ImportFormat.OFX, GBP, none()), "too-deep");

        String longLeaf = OFX1_HEADER + "<OFX><NAME>" + "x".repeat(2000) + "</OFX>";
        assertMalformed(
                () -> reader().read(utf8(longLeaf), ImportFormat.OFX, GBP, none()),
                "element-too-long");

        String longName = OFX1_HEADER + "<OFX><" + "N".repeat(100) + ">x</OFX>";
        assertMalformed(
                () -> reader().read(utf8(longName), ImportFormat.OFX, GBP, none()),
                "element-too-long");

        String many = OFX1_HEADER + "<OFX>" + "<X>1".repeat(200_001) + "</OFX>";
        assertMalformed(
                () -> reader().read(utf8(many), ImportFormat.OFX, GBP, none()),
                "too-many-elements");

        String xmlDeep =
                "<?xml version=\"1.0\"?><?OFX OFXHEADER=\"200\" VERSION=\"211\" SECURITY=\"NONE\"?><OFX>"
                        + "<A>".repeat(40)
                        + "</A>".repeat(40)
                        + "</OFX>";
        assertMalformed(
                () -> reader().read(utf8(xmlDeep), ImportFormat.OFX, GBP, none()), "too-deep");
    }

    @Test
    void refusesAnEncryptedOfx2() {
        String file =
                "<?xml version=\"1.0\"?><?OFX OFXHEADER=\"200\" VERSION=\"211\" SECURITY=\"TYPE1\"?><OFX></OFX>";

        assertMalformed(
                () -> reader().read(utf8(file), ImportFormat.OFX, GBP, none()), "encrypted");
    }

    // ------------------------------------------------------------------------------------ charsets

    @Test
    void aBomDecidesTheCharsetAndIsNotPartOfTheFirstLabel() {
        byte[] body = "Date,Amount\n2026-08-01,-1.00\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] file = new byte[body.length + 2];
        file[0] = (byte) 0xFF;
        file[1] = (byte) 0xFE;
        System.arraycopy(body, 0, file, 2, body.length);

        FileReading reading = reader().read(file, ImportFormat.CSV, GBP, none());

        assertThat(reading.charset()).isEqualTo("UTF-16LE");
        assertThat(reading.charsetSource()).isEqualTo(FileReading.CharsetSource.BOM);
        assertThat(reading.csv().headerLabels().get(0)).isEqualTo("Date");
    }

    @Test
    void refusesUtf7WhateverTheFileDeclares() {
        assertRefused(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("ofx2-utf7.ofx"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
        assertRefused(
                () ->
                        reader().read(
                                        utf8(
                                                OFX1_HEADER.replace("CHARSET:1252", "CHARSET:UTF-7")
                                                        + "<OFX></OFX>"),
                                        ImportFormat.OFX,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
    }

    @Test
    void refusesAUtf32Bom() {
        assertRefused(
                () ->
                        reader().read(
                                        new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 'a', 0, 0, 0},
                                        ImportFormat.CSV,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_ENCODING_UNSUPPORTED);
    }

    @Test
    void aWindows1252PoundInAUtf8FileIsAskedAboutWithSamplesNotGuessed() {
        byte[] file =
                "Date,Description,Amount\n2026-08-01,Café £ shop,-1.00\n"
                        .getBytes(Charset.forName("windows-1252"));

        FileReading reading = reader().read(file, ImportFormat.CSV, GBP, none());

        assertThat(reading.needs()).containsExactly(FileReading.Need.CHARSET);
        assertThat(reading.charset()).isNull();
        assertThat(reading.charsetCandidates())
                .anySatisfy(
                        candidate -> {
                            assertThat(candidate.charset()).isEqualTo("windows-1252");
                            assertThat(candidate.samples())
                                    .anyMatch(sample -> sample.contains("Café £ shop"));
                        });
    }

    @Test
    void aChosenCharsetIsUsedWhenNothingElseDecided() {
        byte[] file =
                "Date,Description,Amount\n2026-08-01,Café,-1.00\n"
                        .getBytes(Charset.forName("windows-1252"));

        FileReading reading =
                reader().read(
                                file,
                                ImportFormat.CSV,
                                GBP,
                                new ReadOptions(
                                        "windows-1252", null, false, null, null, null, null));

        assertThat(reading.charset()).isEqualTo("windows-1252");
        assertThat(reading.charsetSource()).isEqualTo(FileReading.CharsetSource.CHOSEN);
        assertThat(reading.csv().firstRecords().get(0).get(1)).isEqualTo("Café");
    }

    @Test
    void aByteWindows1252DoesNotDefineIsAnErrorNotAReplacementCharacter() {
        assertThat(ImportCharsets.decode(new byte[] {'a', (byte) 0x81, 'b'}, 0, "windows-1252"))
                .isEmpty();
    }

    @Test
    void theAllowlistIsANameComparisonNeverALookup() {
        assertThat(ImportCharsets.canonicalName("utf-7")).isEmpty();
        assertThat(ImportCharsets.canonicalName("IBM037")).isEmpty();
        assertThat(ImportCharsets.canonicalName("UTF-32")).isEmpty();
        assertThat(ImportCharsets.canonicalName("Windows-1252")).contains("windows-1252");
    }

    // ------------------------------------------------------------------------------------- QIF

    @Test
    void refusesInvestmentQifAndAQifForTwoAccounts() {
        assertMalformed(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("qif-invst.qif"),
                                        ImportFormat.QIF,
                                        GBP,
                                        none()),
                "not-a-statement");
        assertRefused(
                () ->
                        reader().read(
                                        ImportFixtures.bytes("qif-two-accounts.qif"),
                                        ImportFormat.QIF,
                                        GBP,
                                        none()),
                ErrorCode.IMPORT_MULTIPLE_ACCOUNTS);
    }

    @Test
    void aQifRecordWhoseTAndUDisagreeIsUnreadable() {
        String file = "!Type:Bank\nD8/3'26\nT-3.50\nU-3.55\nPX\n^\n";

        FileReading reading =
                reader().read(
                                utf8(file),
                                ImportFormat.QIF,
                                GBP,
                                new ReadOptions(
                                        null,
                                        ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null));

        assertThat(reading.rows().get(0).problem()).isEqualTo(RowProblem.AMOUNT_UNREADABLE);
    }

    @Test
    void aQifRecordWithNoDateIsMissingAField() {
        String file = "!Type:Bank\nT-3.50\nPX\n^\n";

        FileReading reading =
                reader().read(
                                utf8(file),
                                ImportFormat.QIF,
                                GBP,
                                new ReadOptions(
                                        null,
                                        ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null));

        assertThat(reading.rows().get(0).problem()).isEqualTo(RowProblem.MISSING_FIELD);
    }

    // ------------------------------------------------------------------------------------- CSV

    @Test
    void mixedLineEndingsAreAllRecordSeparators() {
        String file = "Date,Amount\r\n2026-08-01,-1.00\n2026-08-02,-2.00\r2026-08-03,-3.00";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        assertThat(reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)).rows())
                .hasSize(3);
    }

    @Test
    void debitAndCreditColumnsNeedExactlyOneEachRow() {
        String file =
                "Date,Out,In\n2026-08-01,3.50,\n2026-08-02,,10.00\n2026-08-03,1.00,1.00\n2026-08-04,,\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(
                                0,
                                CsvColumnRole.DATE,
                                1,
                                CsvColumnRole.DEBIT,
                                2,
                                CsvColumnRole.CREDIT),
                        null,
                        null);

        List<ReadRow> rows = reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)).rows();

        assertThat(rows.get(0).amount()).isEqualByComparingTo("-3.50");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("10.00");
        assertThat(rows.get(2).problem()).isEqualTo(RowProblem.AMOUNT_UNREADABLE);
        assertThat(rows.get(3).problem()).isEqualTo(RowProblem.MISSING_FIELD);
    }

    @Test
    void aDirectionColumnSaysWhichWayAndAnythingElseIsUnreadable() {
        String file =
                "Date,Amount,Type\n2026-08-01,3.50,DR\n2026-08-02,10.00,cr\n2026-08-03,1.00,??\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(
                                0,
                                CsvColumnRole.DATE,
                                1,
                                CsvColumnRole.AMOUNT,
                                2,
                                CsvColumnRole.DIRECTION),
                        "CR",
                        "DR");

        List<ReadRow> rows = reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)).rows();

        assertThat(rows.get(0).amount()).isEqualByComparingTo("-3.50");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("10.00");
        assertThat(rows.get(2).problem()).isEqualTo(RowProblem.AMOUNT_UNREADABLE);
    }

    @Test
    void invertSignFlipsEveryAmountAfterReadingIt() {
        String file = "Date,Amount\n2026-08-01,3.50\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        ReadRow row =
                reader().read(
                                utf8(file),
                                ImportFormat.CSV,
                                GBP,
                                new ReadOptions(null, null, true, mapping, null, null, null))
                        .rows()
                        .get(0);

        assertThat(row.amount()).isEqualByComparingTo("-3.50");
    }

    @Test
    void aShortRowAndAForeignCurrencyRowAreRowProblemsNotFileProblems() {
        String file =
                "Date,Amount,Currency\n2026-08-01\n2026-08-02,-1.00,USD\n2026-08-03,-1.00,gbp\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(
                                0,
                                CsvColumnRole.DATE,
                                1,
                                CsvColumnRole.AMOUNT,
                                2,
                                CsvColumnRole.CURRENCY),
                        null,
                        null);

        List<ReadRow> rows = reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)).rows();

        assertThat(rows.get(0).problem()).isEqualTo(RowProblem.COLUMNS_SHORT);
        assertThat(rows.get(1).problem()).isEqualTo(RowProblem.CURRENCY_MISMATCH);
        assertThat(rows.get(2).kind()).isEqualTo(ReadRow.Kind.READY);
    }

    @Test
    void aMappedColumnThatDoesNotExistIsRefusedNamingTheField() {
        String file = "Date,Amount\n2026-08-01,-1.00\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 5, CsvColumnRole.AMOUNT),
                        null,
                        null);

        assertThatThrownBy(() -> reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)))
                .isInstanceOfSatisfying(
                        InvalidRequestException.class,
                        refused ->
                                assertThat(refused.params())
                                        .containsEntry("field", "options.columns"));
    }

    @Test
    void aHeaderRowWithNoDataPreviewsAsZeroRows() {
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.AMOUNT),
                        null,
                        null);

        assertThat(reader().read(utf8("Date,Amount\n"), ImportFormat.CSV, GBP, csv(mapping)).rows())
                .isEmpty();
    }

    @Test
    void textIsCutToWhatTheLedgerStoresAndTheCutIsRecorded() {
        String file = "Date,Payee,Amount\n2026-08-01," + "P".repeat(120) + ",-1.00\n";
        CsvMapping mapping =
                new CsvMapping(
                        ',',
                        1,
                        ImportDateFormat.ISO,
                        '.',
                        null,
                        Map.of(
                                0,
                                CsvColumnRole.DATE,
                                1,
                                CsvColumnRole.PAYEE,
                                2,
                                CsvColumnRole.AMOUNT),
                        null,
                        null);

        ReadRow row = reader().read(utf8(file), ImportFormat.CSV, GBP, csv(mapping)).rows().get(0);

        assertThat(row.payee()).hasSize(80);
        assertThat(row.payeeCut()).isTrue();
    }

    // ------------------------------------------------------------------------------- internals

    private static CsvMapping csvMapping(ImportDateFormat format) {
        return new CsvMapping(
                ',',
                1,
                format,
                '.',
                ',',
                Map.of(
                        0,
                        CsvColumnRole.DATE,
                        1,
                        CsvColumnRole.PAYEE,
                        2,
                        CsvColumnRole.REFERENCE,
                        3,
                        CsvColumnRole.AMOUNT),
                null,
                null);
    }

    private static CsvMapping csvLike() {
        return new CsvMapping(
                ',',
                1,
                ImportDateFormat.ISO,
                '.',
                null,
                Map.of(0, CsvColumnRole.DATE, 1, CsvColumnRole.PAYEE, 2, CsvColumnRole.AMOUNT),
                null,
                null);
    }

    private static ReadOptions csv(CsvMapping mapping) {
        return new ReadOptions(null, null, false, mapping, null, null, null);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRefused(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(code));
    }

    private static void assertMalformed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ImportRefusal.class,
                        refusal -> {
                            assertThat(refusal.errorCode())
                                    .isEqualTo(ErrorCode.IMPORT_FILE_MALFORMED);
                            assertThat(refusal.params()).isEqualTo(Map.of("reason", reason));
                        });
    }
}
