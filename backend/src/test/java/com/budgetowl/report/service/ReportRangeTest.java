package com.budgetowl.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class ReportRangeTest {

    @Test
    void countsBothEnds() {
        assertThat(new ReportRange(YearMonth.of(2025, 9), YearMonth.of(2026, 8)).months())
                .isEqualTo(12);
        assertThat(new ReportRange(YearMonth.of(2026, 8), YearMonth.of(2026, 8)).months())
                .isEqualTo(1);
    }

    @Test
    void isHalfOpenOnTheDatesItScans() {
        ReportRange range = new ReportRange(YearMonth.of(2026, 3), YearMonth.of(2028, 2));

        assertThat(range.firstDay()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2028, 3, 1));
    }

    @Test
    void listsEveryMonthInOrder() {
        assertThat(new ReportRange(YearMonth.of(2025, 11), YearMonth.of(2026, 2)).eachMonth())
                .containsExactly(
                        YearMonth.of(2025, 11),
                        YearMonth.of(2025, 12),
                        YearMonth.of(2026, 1),
                        YearMonth.of(2026, 2));
    }

    @Test
    void theRangeBeforeIsTheSameLengthAndEndsTheMonthBefore() {
        assertThat(new ReportRange(YearMonth.of(2026, 8), YearMonth.of(2026, 8)).preceding())
                .isEqualTo(new ReportRange(YearMonth.of(2026, 7), YearMonth.of(2026, 7)));
        assertThat(new ReportRange(YearMonth.of(2026, 6), YearMonth.of(2026, 8)).preceding())
                .isEqualTo(new ReportRange(YearMonth.of(2026, 3), YearMonth.of(2026, 5)));
        assertThat(new ReportRange(YearMonth.of(2026, 1), YearMonth.of(2026, 2)).preceding())
                .isEqualTo(new ReportRange(YearMonth.of(2025, 11), YearMonth.of(2025, 12)));
    }

    @Test
    void acceptsTwentyFourMonthsAndRefusesTwentyFive() {
        assertThat(new ReportRange(YearMonth.of(2024, 9), YearMonth.of(2026, 8)).months())
                .isEqualTo(ReportRange.MAXIMUM_MONTHS);
        assertThatThrownBy(() -> new ReportRange(YearMonth.of(2024, 8), YearMonth.of(2026, 8)))
                .isInstanceOfSatisfying(
                        InvalidRequestException.class,
                        refused -> {
                            assertThat(refused.errorCode())
                                    .isEqualTo(ErrorCode.REPORT_RANGE_TOO_LONG);
                            assertThat(refused.params()).containsEntry("maximumMonths", 24);
                        });
    }

    @Test
    void refusesFromAfterTo() {
        assertThatThrownBy(() -> new ReportRange(YearMonth.of(2026, 9), YearMonth.of(2026, 8)))
                .isInstanceOfSatisfying(
                        InvalidRequestException.class,
                        refused ->
                                assertThat(refused.errorCode())
                                        .isEqualTo(ErrorCode.REPORT_RANGE_INVALID));
    }

    @Test
    void refusesYearZeroAndAComparisonThatWouldReachIt() {
        assertThatThrownBy(() -> new ReportRange(YearMonth.of(0, 12), YearMonth.of(1, 1)))
                .isInstanceOf(InvalidRequestException.class);
        ReportRange first = new ReportRange(YearMonth.of(1, 1), YearMonth.of(1, 1));
        assertThatThrownBy(first::preceding).isInstanceOf(InvalidRequestException.class);
    }
}
