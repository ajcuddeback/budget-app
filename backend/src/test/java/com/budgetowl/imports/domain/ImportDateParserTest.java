package com.budgetowl.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.imports.domain.ImportDateParser.Parsed;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Dates are read strictly, as calendar dates, from an allowlist (docs/features/file-import.md rule
 * 6, and the threat model's "a hostile date such as 2026-02-30 is accepted as the 28th").
 *
 * <p>The clock is fixed to 25 November 2026, so the range and the two-digit-year window do not move
 * under the suite.
 */
class ImportDateParserTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    private final ImportDateParser parser = new ImportDateParser(CLOCK);

    @Test
    void refusesThirtiethOfFebruaryRatherThanReadingTheTwentyEighth() {
        // The default SMART resolver reads this as 2026-02-28. STRICT with uuuu refuses it.
        assertThat(parser.parse("2026-02-30", ImportDateFormat.ISO))
                .isEqualTo(new Parsed.Refused(RowProblem.DATE_UNREADABLE));
    }

    @Test
    void readsTwentyNinthOfFebruaryInALeapYearOnly() {
        assertThat(parser.parse("2024-02-29", ImportDateFormat.ISO))
                .isEqualTo(new Parsed.Day(LocalDate.of(2024, 2, 29)));
        assertThat(parser.parse("2026-02-29", ImportDateFormat.ISO))
                .isEqualTo(new Parsed.Refused(RowProblem.DATE_UNREADABLE));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "0000-01-01, DATE_OUT_OF_RANGE",
        "1899-12-31, DATE_OUT_OF_RANGE",
        "2026-12-27, DATE_OUT_OF_RANGE",
        "+10000-01-01, DATE_UNREADABLE",
        "٢٠٢٦-٠٨-٠١, DATE_UNREADABLE",
        "２０２６-０８-０１, DATE_UNREADABLE",
        "45678, DATE_UNREADABLE",
        "2026-8-1, DATE_UNREADABLE",
        "2026-13-01, DATE_UNREADABLE",
        "Aug 1 2026, DATE_UNREADABLE",
    })
    void refusesWhatTheCalendarOrTheRangeDoesNotAllow(String text, RowProblem expected) {
        assertThat(parser.parse(text, ImportDateFormat.ISO))
                .isEqualTo(new Parsed.Refused(expected));
    }

    @Test
    void acceptsUpToThirtyOneDaysAhead() {
        assertThat(parser.parse("2026-12-26", ImportDateFormat.ISO))
                .isEqualTo(new Parsed.Day(LocalDate.of(2026, 12, 26)));
    }

    @Test
    void readsOnlyTheDateDigitsOfOfxDtPostedSoMidnightNeverMovesADay() {
        // 23:30 in California on 31 July is 06:30 UTC on 1 August. Converting through an instant
        // would file it in the wrong month.
        assertThat(parser.parseOfx("20260731233000[-8:PST]"))
                .isEqualTo(new Parsed.Day(LocalDate.of(2026, 7, 31)));
        assertThat(parser.parseOfx("20260801120000.000[-5:EST]"))
                .isEqualTo(new Parsed.Day(LocalDate.of(2026, 8, 1)));
        assertThat(parser.parseOfx("20260801")).isEqualTo(new Parsed.Day(LocalDate.of(2026, 8, 1)));
    }

    @Test
    void refusesAnOfxDateWhoseTailIsNotTheShapeOfOne() {
        assertThat(parser.parseOfx("20260801<script>"))
                .isEqualTo(new Parsed.Refused(RowProblem.DATE_UNREADABLE));
        assertThat(parser.parseOfx("20260230"))
                .isEqualTo(new Parsed.Refused(RowProblem.DATE_UNREADABLE));
    }

    @Test
    void readsTwoDigitYearsInTheWindowEndingNextYear() {
        assertThat(parser.parse("8/3/27", ImportDateFormat.QIF_MONTH_DAY_YEAR))
                .isEqualTo(new Parsed.Refused(RowProblem.DATE_OUT_OF_RANGE));
        assertThat(parser.parse("8/3/26", ImportDateFormat.QIF_MONTH_DAY_YEAR))
                .isEqualTo(new Parsed.Day(LocalDate.of(2026, 8, 3)));
        assertThat(parser.parse("8/3/28", ImportDateFormat.QIF_MONTH_DAY_YEAR))
                .as("28 is past the window's end, so it is 1928, not 2028")
                .isEqualTo(new Parsed.Day(LocalDate.of(1928, 8, 3)));
        assertThat(parser.parse("1/1/00", ImportDateFormat.QIF_MONTH_DAY_YEAR))
                .isEqualTo(new Parsed.Day(LocalDate.of(2000, 1, 1)));
    }

    @Test
    void readsQuickensApostropheAndItsSpacePadding() {
        assertThat(parser.parseQif("8/ 3'26", ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR))
                .isEqualTo(new Parsed.Day(LocalDate.of(2026, 8, 3)));
        assertThat(parser.parseQif("8/3/98", ImportDateFormat.QIF_MONTH_DAY_APOSTROPHE_YEAR))
                .isEqualTo(new Parsed.Day(LocalDate.of(1998, 8, 3)));
    }

    @Test
    void aFileWhoseDaysAreAllTwelveOrLessIsAmbiguousAndTheUsersToResolve() {
        List<ImportDateFormat> readings =
                parser.readingsOfAll(List.of("03/08/2026", "05/07/2026", "11/12/2026"), false);

        assertThat(readings)
                .as("dd/MM and MM/dd read these to different days; the person must choose")
                .contains(ImportDateFormat.DAY_MONTH_YEAR, ImportDateFormat.MONTH_DAY_YEAR);
    }

    @Test
    void oneDayAboveTwelveMakesTheFormatUnambiguous() {
        List<ImportDateFormat> readings =
                parser.readingsOfAll(List.of("03/08/2026", "25/07/2026"), false);

        assertThat(readings).containsExactly(ImportDateFormat.DAY_MONTH_YEAR);
    }

    @Test
    void formatsThatReadTheSameDatesAreOneChoiceNotTwo() {
        // dd/MM/uuuu and d/M/uuuu read 25/07/2026 identically; asking about it would be noise.
        assertThat(parser.readingsOfAll(List.of("25/07/2026"), false))
                .containsExactly(ImportDateFormat.DAY_MONTH_YEAR);
    }

    @Test
    void oneRowOutOfRangeDoesNotMakeAnUnambiguousFileAmbiguous() {
        assertThat(parser.readingsOfAll(List.of("2026-08-01", "1899-01-01"), false))
                .containsExactly(ImportDateFormat.ISO);
    }
}
