package com.budgetowl.bill.domain;

import static com.budgetowl.bill.domain.BillFixtures.bill;
import static com.budgetowl.bill.domain.BillFixtures.household;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillOccurrenceTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final LocalDate DUE = LocalDate.of(2026, 8, 1);

    @Test
    void anOccurrenceCopiesTheFiguresInRatherThanPointingAtTheBill() {
        Bill rent = bill(household(), "1200.00", Recurrence.monthly(1));

        BillOccurrence occurrence = occurrence(rent, "1200.00");

        assertThat(occurrence.householdId()).isEqualTo(rent.householdId());
        assertThat(occurrence.billId()).isEqualTo(rent.id());
        assertThat(occurrence.period()).isEqualTo(AUGUST);
        assertThat(occurrence.dueDate()).isEqualTo(DUE);
        assertThat(occurrence.status()).isEqualTo(OccurrenceStatus.DUE);
        assertThat(occurrence.statusSource()).isEqualTo(StatusSource.MANUAL);

        // The point of copying: changing the bill does not rewrite a month already answered.
        rent.expect(Money.of("1300.00", "GBP"), GBP);
        assertThat(occurrence.expectedAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void overdueIsDerivedFromTheDateAndTheStatusAndIsNeverStored() {
        BillOccurrence occurrence =
                occurrence(bill(household(), "50.00", Recurrence.monthly(1)), "50.00");

        assertThat(occurrence.isOverdue(DUE.plusDays(1))).isTrue();
        assertThat(occurrence.isOverdue(DUE)).isFalse();
        assertThat(occurrence.isOverdue(DUE.minusDays(1))).isFalse();

        // Paid is not overdue however long ago it was due — which is why there is no OVERDUE
        // status and nothing has to be swept.
        occurrence.markPaid(Money.of("50.00", "GBP"), DUE, null, StatusSource.MANUAL, GBP);
        assertThat(occurrence.isOverdue(DUE.plusYears(1))).isFalse();
    }

    @Test
    void thePaidFieldsAreClearedWheneverTheStatusStopsBeingPaid() {
        BillOccurrence occurrence =
                occurrence(bill(household(), "50.00", Recurrence.monthly(1)), "50.00");
        occurrence.markPaid(
                Money.of("54.30", "GBP"), DUE, UUID.randomUUID(), StatusSource.MANUAL, GBP);

        assertThat(occurrence.actualAmount()).isPresent();
        assertThat(occurrence.transactionId()).isPresent();

        occurrence.markSkipped();

        // ck_bill_occurrences_paid_fields says the same thing. A SKIPPED row carrying an actual
        // amount would make "what did we really pay this month" quietly wrong.
        assertThat(occurrence.status()).isEqualTo(OccurrenceStatus.SKIPPED);
        assertThat(occurrence.actualAmount()).isEmpty();
        assertThat(occurrence.paidDate()).isEmpty();
        assertThat(occurrence.transactionId()).isEmpty();

        occurrence.markPaid(Money.of("54.30", "GBP"), DUE, null, StatusSource.MANUAL, GBP);
        occurrence.markDue();
        assertThat(occurrence.actualAmount()).isEmpty();
    }

    @Test
    void aPaidAmountMayDifferFromTheBudgetedOneButNotInCurrencyAndNotBelowZero() {
        BillOccurrence occurrence =
                occurrence(bill(household(), "120.00", Recurrence.monthly(1)), "120.00");

        occurrence.markPaid(Money.of("134.10", "GBP"), DUE, null, StatusSource.MANUAL, GBP);
        // Keeping both is the whole reason the column exists (ADR-0025).
        assertThat(occurrence.expectedAmount()).isEqualByComparingTo("120.00");
        assertThat(occurrence.actualAmount().orElseThrow()).isEqualByComparingTo("134.10");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                occurrence.markPaid(
                                        Money.of("1.00", "EUR"),
                                        DUE,
                                        null,
                                        StatusSource.MANUAL,
                                        GBP));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                occurrence.markPaid(
                                        Money.of(new java.math.BigDecimal("-1.00"), GBP),
                                        DUE,
                                        null,
                                        StatusSource.MANUAL,
                                        GBP));
    }

    static BillOccurrence occurrence(Bill bill, String expected) {
        return BillOccurrence.recordedFor(bill, AUGUST, DUE, Money.of(expected, "GBP"));
    }
}
