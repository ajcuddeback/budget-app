package com.budgetowl.bill.domain;

import static com.budgetowl.bill.domain.BillFixtures.bill;
import static com.budgetowl.bill.domain.BillFixtures.household;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.common.schedule.ScheduleKind;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class BillTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void aBillTakesItsHouseholdFromTheHouseholdAndNotFromAParameter() {
        Household household = household();

        Bill bill = bill(household, "1200.00", Recurrence.monthly(1));

        // An id supplied by a caller is a lookup key and never evidence of ownership.
        assertThat(bill.householdId()).isEqualTo(household.id());
        assertThat(bill.expectedAmount()).isEqualByComparingTo("1200.00");
        assertThat(bill.isActive()).isTrue();
        assertThat(bill.amountVaries()).isFalse();
        assertThat(bill.tolerance()).isEmpty();
    }

    @Test
    void theScheduleSurvivesTheRoundTripThroughItsColumns() {
        Bill bill =
                bill(
                        household(),
                        "120.00",
                        Recurrence.fortnightly(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 8, 5)));

        // The entity stores five loose columns; `recurrence()` is the only thing that reassembles
        // them, so a mapping mistake here is a bill that projects the wrong dates and says nothing.
        Recurrence read = bill.recurrence();
        assertThat(read.kind()).isEqualTo(ScheduleKind.FORTNIGHTLY);
        assertThat(read.dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(read.anchorDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(read.dayOfMonth()).isNull();
        assertThat(read.month()).isNull();
    }

    @Test
    void reschedulingReplacesEveryParameterAndNotOnlyTheOnesTheNewKindUses() {
        Bill bill = bill(household(), "120.00", Recurrence.yearly(4, 30));

        bill.rescheduleTo(Recurrence.weekly(DayOfWeek.FRIDAY));

        // A leftover day-of-month on a weekly bill is the kind of state that passes every CHECK
        // and then projects something nobody asked for.
        assertThat(bill.recurrence().dayOfMonth()).isNull();
        assertThat(bill.recurrence().month()).isNull();
        assertThat(bill.recurrence().dayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void aBillIsDenominatedInItsHouseholdsCurrencyAndRefusesAnother() {
        Household household = household();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Bill.of(
                                        household,
                                        "Rent",
                                        Money.of("1200.00", "EUR"),
                                        Recurrence.monthly(1),
                                        GBP))
                .withMessageContaining("base currency");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                bill(household, "1200.00", Recurrence.monthly(1))
                                        .expect(Money.of("1.00", "EUR"), GBP));
    }

    @Test
    void zeroIsAllowedAndNegativeIsNot() {
        Bill bill = bill(household(), "0.00", Recurrence.monthly(1));

        // A bill that is currently nothing is still a bill you want listed. A negative one is a
        // refund, and a refund is a transaction.
        assertThat(bill.expectedAmount()).isEqualByComparingTo("0.00");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bill.expect(Money.of(new BigDecimal("-1.00"), GBP), GBP));
    }

    @Test
    void aToleranceBelongsToABillThatVariesAndToNoOther() {
        Bill bill = bill(household(), "120.00", Recurrence.monthly(14));

        bill.variesBy(true, new BigDecimal("22.00"));
        assertThat(bill.tolerance()).contains(new BigDecimal("22.00"));

        // Both directions: a varying bill without one has nothing to compare against, and a fixed
        // bill with one carries a number nothing would ever read.
        assertThatIllegalArgumentException().isThrownBy(() -> bill.variesBy(true, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bill.variesBy(false, new BigDecimal("22.00")));

        bill.variesBy(false, null);
        assertThat(bill.tolerance()).isEmpty();
    }

    @Test
    void aNameIsTrimmedAndBounded() {
        Bill bill = bill(household(), "10.00", Recurrence.monthly(1));

        bill.rename("  Council tax  ");
        assertThat(bill.name()).isEqualTo("Council tax");

        assertThatIllegalArgumentException().isThrownBy(() -> bill.rename("   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bill.rename("x".repeat(Bill.NAME_MAX_LENGTH + 1)));
    }
}
