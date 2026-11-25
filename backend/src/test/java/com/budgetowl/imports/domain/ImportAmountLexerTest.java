package com.budgetowl.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.imports.domain.ImportAmountLexer.Direction;
import com.budgetowl.imports.domain.ImportAmountLexer.Lexed;
import com.budgetowl.imports.domain.ImportAmountLexer.Style;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The amount lexer refuses everything {@code BigDecimal}, {@code double} and {@code NumberFormat}
 * would accept and must not (docs/features/file-import.md rule 7 and its threat model).
 *
 * <p>Every hostile input in the threat model's list is here, each refused with its own code — and
 * each refused <b>fast</b>: {@code 1e+10000000} costs seconds through {@code BigDecimal}, so a test
 * that only checked the answer would pass against an implementation that got there the slow way.
 */
class ImportAmountLexerTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Style POINT = Style.of('.', null);
    private static final Style GROUPED = Style.of('.', ',');
    private static final Style EUROPEAN = Style.of(',', '.');

    @ParameterizedTest(name = "{0} is refused as {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "1E5|AMOUNT_UNREADABLE",
                "1e+10000000|AMOUNT_UNREADABLE",
                "1E+10000000|AMOUNT_UNREADABLE",
                "NaN|AMOUNT_UNREADABLE",
                "Infinity|AMOUNT_UNREADABLE",
                "-Infinity|AMOUNT_UNREADABLE",
                "0x1p3|AMOUNT_UNREADABLE",
                "١٢٣|AMOUNT_UNREADABLE",
                "１２３|AMOUNT_UNREADABLE",
                "12abc|AMOUNT_UNREADABLE",
                "1.2.3|AMOUNT_UNREADABLE",
                "--5|AMOUNT_UNREADABLE",
                "(5)-|AMOUNT_UNREADABLE",
                "-(5)|AMOUNT_UNREADABLE",
                "+-5|AMOUNT_UNREADABLE",
                ".5|AMOUNT_UNREADABLE",
                "5.|AMOUNT_UNREADABLE",
                "1234567890123456|AMOUNT_TOO_LARGE",
                "1.23456|AMOUNT_TOO_PRECISE",
                "$12.00|CURRENCY_MISMATCH",
                "12.00 EUR|CURRENCY_MISMATCH",
                "€5|CURRENCY_MISMATCH",
            })
    void refusesWhatEveryOtherParserWouldAccept(String text, RowProblem expected) {
        long started = System.nanoTime();

        Lexed lexed = ImportAmountLexer.lex(text, POINT, GBP, Direction.NONE);

        assertThat(lexed).isEqualTo(new Lexed.Refused(expected));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("refused before anything expensive could run")
                .isLessThan(Duration.ofMillis(200));
    }

    @Test
    void refusesTextLongerThanSixtyFourCharactersBeforeLookingAtIt() {
        String long65 = "1".repeat(65);

        assertThat(ImportAmountLexer.lex(long65, POINT, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Refused(RowProblem.AMOUNT_UNREADABLE));
    }

    @Test
    void refusesABlankCellAsMissing() {
        assertThat(ImportAmountLexer.lex("   ", POINT, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Refused(RowProblem.MISSING_FIELD));
    }

    @ParameterizedTest(name = "{0} reads as {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "12.34|12.34",
                "-3.50|-3.5",
                "+3.50|3.5",
                "3.50-|-3.5",
                "(3.50)|-3.5",
                "−3.50|-3.5",
                "£3.50|3.5",
                "-£3.50|-3.5",
                "£-3.50|-3.5",
                "GBP 12.00|12",
                "12.00 GBP|12",
                "£(3.50)|-3.5",
                "0012.3400|12.34",
                "999999999999999.9999|999999999999999.9999",
                "0|0",
            })
    void readsEveryHonestShapeExactly(String text, String expected) {
        assertThat(ImportAmountLexer.lex(text, POINT, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Amount(new BigDecimal(expected)));
    }

    @Test
    void readsAGroupingSeparatorOnlyBetweenGroupsOfThree() {
        assertThat(ImportAmountLexer.lex("1,234,567.89", GROUPED, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Amount(new BigDecimal("1234567.89")));
        // Twelve fifty with a comma for a decimal point, read under a comma-grouping style, is
        // NOT twelve hundred and fifty: a separator in the wrong place is refused.
        assertThat(ImportAmountLexer.lex("12,50", GROUPED, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Refused(RowProblem.AMOUNT_UNREADABLE));
        assertThat(ImportAmountLexer.lex("1,2345.00", GROUPED, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Refused(RowProblem.AMOUNT_UNREADABLE));
    }

    @Test
    void readsTheEuropeanStyle() {
        assertThat(ImportAmountLexer.lex("1.234,56", EUROPEAN, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Amount(new BigDecimal("1234.56")));
    }

    @Test
    void ofxAcceptsEitherDecimalSeparatorAndNoGrouping() {
        assertThat(ImportAmountLexer.lex("-3,50", Style.OFX, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Amount(new BigDecimal("-3.5")));
        assertThat(ImportAmountLexer.lex("1.234,56", Style.OFX, GBP, Direction.NONE))
                .isEqualTo(new Lexed.Refused(RowProblem.AMOUNT_UNREADABLE));
    }

    @Test
    void aDirectionColumnIsTheOneSignMarker() {
        assertThat(ImportAmountLexer.lex("3.50", POINT, GBP, Direction.OUT))
                .isEqualTo(new Lexed.Amount(new BigDecimal("-3.5")));
        assertThat(ImportAmountLexer.lex("3.50", POINT, GBP, Direction.IN))
                .isEqualTo(new Lexed.Amount(new BigDecimal("3.5")));
        // A sign in the cell as well as a direction is two answers to one question.
        assertThat(ImportAmountLexer.lex("-3.50", POINT, GBP, Direction.OUT))
                .isEqualTo(new Lexed.Refused(RowProblem.AMOUNT_UNREADABLE));
    }

    @Test
    void anAmountNeverPrintsItself() {
        assertThat(new Lexed.Amount(new BigDecimal("98765.4321")).toString())
                .doesNotContain("98765");
    }
}
