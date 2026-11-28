package com.budgetowl.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * The projection arithmetic, which everything about bills and income rests on.
 *
 * <p>Tested as a pure function rather than through the API, because the cases that matter are
 * calendar edges — February, leap years, five-Wednesday months — and reaching them through HTTP
 * would mean arranging a date rather than asserting one.
 */
class RecurrenceTest {

    @Test
    void aMonthlyBillFallsOnItsDayEveryMonth() {
        Recurrence rent = Recurrence.monthly(15);

        assertThat(rent.datesIn(YearMonth.of(2026, 8))).containsExactly(LocalDate.of(2026, 8, 15));
        assertThat(rent.datesIn(YearMonth.of(2026, 9))).containsExactly(LocalDate.of(2026, 9, 15));
    }

    @Test
    void aMonthlyBillDueOnThe31stIsClampedToTheMonthsLastDay() {
        Recurrence rent = Recurrence.monthly(31);

        assertThat(rent.datesIn(YearMonth.of(2026, 1))).containsExactly(LocalDate.of(2026, 1, 31));
        assertThat(rent.datesIn(YearMonth.of(2026, 4)))
                .as("April has 30 days; the bill is still due, on the last of them")
                .containsExactly(LocalDate.of(2026, 4, 30));
        assertThat(rent.datesIn(YearMonth.of(2026, 2))).containsExactly(LocalDate.of(2026, 2, 28));
    }

    @Test
    void aLeapYearGivesFebruaryItsExtraDay() {
        assertThat(Recurrence.monthly(30).datesIn(YearMonth.of(2028, 2)))
                .containsExactly(LocalDate.of(2028, 2, 29));
    }

    @Test
    void aYearlyBillAppearsInItsOwnMonthAndNoOther() {
        Recurrence insurance = Recurrence.yearly(3, 20);

        assertThat(insurance.datesIn(YearMonth.of(2026, 3)))
                .containsExactly(LocalDate.of(2026, 3, 20));
        assertThat(insurance.datesIn(YearMonth.of(2026, 4))).isEmpty();
        assertThat(insurance.datesIn(YearMonth.of(2027, 3)))
                .containsExactly(LocalDate.of(2027, 3, 20));
    }

    @Test
    void aWeeklyBillFallsOnEveryMatchingWeekday() {
        Recurrence weekly = Recurrence.weekly(DayOfWeek.WEDNESDAY);

        // August 2026 starts on a Saturday, so its Wednesdays are the 5th, 12th, 19th and 26th.
        assertThat(weekly.datesIn(YearMonth.of(2026, 8)))
                .containsExactly(
                        LocalDate.of(2026, 8, 5),
                        LocalDate.of(2026, 8, 12),
                        LocalDate.of(2026, 8, 19),
                        LocalDate.of(2026, 8, 26));
    }

    @Test
    void aWeeklyBillHandlesAMonthWithFiveOfThem() {
        Recurrence weekly = Recurrence.weekly(DayOfWeek.FRIDAY);

        assertThat(weekly.datesIn(YearMonth.of(2026, 1)))
                .as("January 2026 has five Fridays")
                .hasSize(5)
                .startsWith(LocalDate.of(2026, 1, 2))
                .endsWith(LocalDate.of(2026, 1, 30));
    }

    @Test
    void aWeeklyBillWhoseWeekdayStartsTheMonthIncludesTheFirst() {
        // 1 August 2026 is a Saturday. The nextOrSame adjuster has to include it, not skip to the
        // 8th — an off-by-one here would silently lose one occurrence a month.
        assertThat(Recurrence.weekly(DayOfWeek.SATURDAY).datesIn(YearMonth.of(2026, 8)))
                .startsWith(LocalDate.of(2026, 8, 1));
    }

    @Test
    void aFortnightlyBillKeepsStepWithItsAnchorAcrossMonths() {
        Recurrence fortnightly =
                Recurrence.fortnightly(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 8, 5));

        assertThat(fortnightly.datesIn(YearMonth.of(2026, 8)))
                .containsExactly(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 19));
        // September must continue the same fortnight, not restart counting from the 1st. August
        // has four Wednesdays, so a naive per-month count would put September on the wrong ones.
        assertThat(fortnightly.datesIn(YearMonth.of(2026, 9)))
                .containsExactly(
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 16),
                        LocalDate.of(2026, 9, 30));
    }

    @Test
    void aFortnightlyBillWorksForMonthsBeforeItsAnchor() {
        // A household can set a bill up with an anchor in the future; the past still has to
        // compute. floorMod rather than % is what makes the negative week count land right.
        Recurrence fortnightly =
                Recurrence.fortnightly(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 8, 5));

        assertThat(fortnightly.datesIn(YearMonth.of(2026, 7)))
                .containsExactly(LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 22));
    }

    @Test
    void anAnchorThatIsNotOnTheWeekdayStillAlignsTheCount() {
        // The anchor is a Friday but the schedule is Wednesdays. It aligns to its own week's
        // Wednesday rather than throwing, because a household typing a date should not have to
        // know which weekday it lands on.
        Recurrence fortnightly =
                Recurrence.fortnightly(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 8, 7));

        assertThat(fortnightly.datesIn(YearMonth.of(2026, 8)))
                .containsExactly(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 19));
    }

    @Test
    void anIrregularScheduleProjectsNothingAndIsNotAnError() {
        // A bill somebody cannot predict is still a bill they want listed. Recording a status
        // against a period is what creates its occurrence.
        assertThat(Recurrence.irregular().datesIn(YearMonth.of(2026, 8))).isEmpty();
    }

    @Test
    void theSameMonthAlwaysGivesTheSameAnswer() {
        // No clock, no database, no configuration — which is what lets occurrences be projected
        // on read instead of generated by a job (ADR-0032).
        Recurrence weekly = Recurrence.weekly(DayOfWeek.TUESDAY);

        assertThat(weekly.datesIn(YearMonth.of(2026, 8)))
                .isEqualTo(weekly.datesIn(YearMonth.of(2026, 8)));
    }

    @Test
    void impossibleParametersAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Recurrence.monthly(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Recurrence.monthly(32));
        assertThatIllegalArgumentException().isThrownBy(() -> Recurrence.yearly(13, 1));
        assertThatNullPointerException().isThrownBy(() -> Recurrence.weekly(null));
        assertThatNullPointerException()
                .isThrownBy(() -> Recurrence.fortnightly(DayOfWeek.MONDAY, null));
        assertThatNullPointerException().isThrownBy(() -> Recurrence.irregular().datesIn(null));
    }
}
