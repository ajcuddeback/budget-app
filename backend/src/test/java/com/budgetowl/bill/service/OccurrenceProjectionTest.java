package com.budgetowl.bill.service;

import static com.budgetowl.bill.domain.BillFixtures.bill;
import static com.budgetowl.bill.domain.BillFixtures.household;
import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.bill.domain.Bill;
import com.budgetowl.bill.domain.BillOccurrence;
import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The merge between what a schedule implies and what a household recorded.
 *
 * <p>These are the cases that decide whether editing a bill destroys a month somebody had already
 * settled. ADR-0032 names the three steps; this asserts each of them and the ordering they produce.
 */
class OccurrenceProjectionTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final Bill RENT = bill(household(), "1200.00", Recurrence.monthly(15));

    @Test
    void anUntouchedPeriodIsJustItsProjection() {
        List<LocalDate> dates = List.of(day(7), day(14), day(21), day(28));

        List<ProjectedOccurrence> merged = OccurrenceProjection.merge(dates, List.of());

        // The property that makes the scheduler unnecessary: no rows reads as every occurrence
        // still being DUE, and that is the same screen either way.
        assertThat(merged)
                .extracting(ProjectedOccurrence::dueDate)
                .containsExactlyElementsOf(dates);
        assertThat(merged).allMatch(occurrence -> !occurrence.isRecorded());
    }

    @Test
    void aRecordedRowOnAProjectedDateIsThatOccurrence() {
        BillOccurrence paid = recorded(day(15));

        List<ProjectedOccurrence> merged =
                OccurrenceProjection.merge(List.of(day(15)), List.of(paid));

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().recorded()).isSameAs(paid);
    }

    @Test
    void aWeeklyBillHoldsOneRecordPerPaymentAndNotOnePerMonth() {
        List<LocalDate> fridays = List.of(day(7), day(14), day(21), day(28));
        BillOccurrence first = recorded(day(7));
        BillOccurrence third = recorded(day(21));

        List<ProjectedOccurrence> merged =
                OccurrenceProjection.merge(fridays, List.of(third, first));

        // The defect this shape exists to prevent: a month-keyed occurrence would have let the
        // household tick off one Friday and silently refuse the rest.
        assertThat(merged).hasSize(4);
        assertThat(merged.get(0).recorded()).isSameAs(first);
        assertThat(merged.get(1).isRecorded()).isFalse();
        assertThat(merged.get(2).recorded()).isSameAs(third);
        assertThat(merged.get(3).isRecorded()).isFalse();
    }

    @Test
    void aSettledMonthStaysSettledWhenTheDueDayMoves() {
        BillOccurrence paidOnThe15th = recorded(day(15));

        // The household has since moved rent to the 20th. The projection now says the 20th.
        List<ProjectedOccurrence> merged =
                OccurrenceProjection.merge(List.of(day(20)), List.of(paidOnThe15th));

        // Step 2. Without it, August would show a paid 15th beside an unpaid 20th and the month
        // would appear to owe rent twice.
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().recorded()).isSameAs(paidOnThe15th);
        assertThat(merged.getFirst().dueDate())
                .as("the slot it fills. The row keeps its own date — see the record's Javadoc")
                .isEqualTo(day(20));
        assertThat(merged.getFirst().recorded().dueDate()).isEqualTo(day(15));
    }

    @Test
    void aRowThatLinesUpWithNothingIsStillShown() {
        BillOccurrence paid = recorded(day(3));

        // A bill that was monthly and is now yearly projects nothing in August at all.
        List<ProjectedOccurrence> merged = OccurrenceProjection.merge(List.of(), List.of(paid));

        // Step 3. The household asserted this; a schedule edit does not get to delete it.
        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().recorded()).isSameAs(paid);
        assertThat(merged.getFirst().dueDate()).isEqualTo(day(3));
    }

    @Test
    void anIrregularBillsRecordsAreAllItHas() {
        BillOccurrence one = recorded(day(9));
        BillOccurrence two = recorded(day(23));

        List<ProjectedOccurrence> merged = OccurrenceProjection.merge(List.of(), List.of(two, one));

        // Projecting nothing is not the same as having nothing, and the order is still the month's.
        assertThat(merged)
                .extracting(ProjectedOccurrence::dueDate)
                .containsExactly(day(9), day(23));
    }

    @Test
    void moreRecordsThanDatesFillsTheDatesFirstAndThenStandsAlone() {
        BillOccurrence earlier = recorded(day(2));
        BillOccurrence later = recorded(day(28));

        // One projected date, two records: the bill used to fall on the 2nd and now falls on the
        // 14th, and something was also recorded on the 28th.
        List<ProjectedOccurrence> merged =
                OccurrenceProjection.merge(List.of(day(14)), List.of(later, earlier));

        // Step 2 fills the projected date from the earliest leftover, so the month shows one rent
        // and not two. Step 3 keeps the other rather than dropping it on the floor. The order is
        // the month's, whatever order the rows arrived in — two members must see the same thing.
        assertThat(merged)
                .extracting(ProjectedOccurrence::dueDate)
                .containsExactly(day(14), day(28));
        assertThat(merged.get(0).recorded()).isSameAs(earlier);
        assertThat(merged.get(1).recorded()).isSameAs(later);
    }

    private static BillOccurrence recorded(LocalDate dueDate) {
        return BillOccurrence.recordedFor(RENT, AUGUST, dueDate, Money.of("1200.00", "GBP"));
    }

    private static LocalDate day(int dayOfMonth) {
        return AUGUST.atDay(dayOfMonth);
    }
}
