package com.budgetowl.income.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.common.schedule.ScheduleKind;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomeSourceTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void anIncomeSourceTakesItsHouseholdFromTheHouseholdAndNotFromAParameter() {
        Household household = household();

        IncomeSource source = source(household, "2400.00", Recurrence.monthly(28));

        assertThat(source.householdId()).isEqualTo(household.id());
        assertThat(source.amount()).isEqualByComparingTo("2400.00");
        assertThat(source.isActive()).isTrue();
        assertThat(source.accountId()).isEmpty();
        // A typed figure and one derived from history are different claims, and nothing writes the
        // second one yet.
        assertThat(source.sourceOfTruth()).isEqualTo(SourceOfTruth.MANUAL_ESTIMATE);
    }

    @Test
    void theScheduleSurvivesTheRoundTripThroughItsColumns() {
        IncomeSource source =
                source(
                        household(),
                        "900.00",
                        Recurrence.fortnightly(DayOfWeek.FRIDAY, LocalDate.of(2099, 1, 1)));

        Recurrence read = source.recurrence();
        assertThat(read.kind()).isEqualTo(ScheduleKind.FORTNIGHTLY);
        assertThat(read.dayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(read.anchorDate()).isEqualTo(LocalDate.of(2099, 1, 1));
        assertThat(read.dayOfMonth()).isNull();
    }

    @Test
    void reschedulingReplacesEveryParameterAndNotOnlyTheOnesTheNewKindUses() {
        IncomeSource source = source(household(), "900.00", Recurrence.yearly(12, 20));

        source.rescheduleTo(Recurrence.weekly(DayOfWeek.MONDAY));

        assertThat(source.recurrence().dayOfMonth()).isNull();
        assertThat(source.recurrence().month()).isNull();
        assertThat(source.recurrence().dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void incomeIsDenominatedInTheHouseholdsCurrencyAndIsNeverNegative() {
        IncomeSource source = source(household(), "2400.00", Recurrence.monthly(28));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> source.expect(Money.of("1.00", "EUR"), GBP))
                .withMessageContaining("base currency");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source.expect(Money.of(new BigDecimal("-1.00"), GBP), GBP));

        // Zero is allowed: a source somebody expects nothing from this year is still a source.
        source.expect(Money.of("0.00", "GBP"), GBP);
        assertThat(source.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void aNameIsTrimmedAndBounded() {
        IncomeSource source = source(household(), "2400.00", Recurrence.monthly(28));

        source.rename("  Salary  ");
        assertThat(source.name()).isEqualTo("Salary");

        assertThatIllegalArgumentException().isThrownBy(() -> source.rename("   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> source.rename("x".repeat(IncomeSource.NAME_MAX_LENGTH + 1)));
        assertThatNullPointerException().isThrownBy(() -> source.rename(null));
    }

    @Test
    void anAccountIsOptionalAndCanBeTakenBackOff() {
        IncomeSource source = source(household(), "2400.00", Recurrence.monthly(28));
        UUID current = UUID.randomUUID();

        source.landsIn(current);
        assertThat(source.accountId()).contains(current);

        // A household may track income it cannot attribute, and may stop attributing it.
        source.landsIn(null);
        assertThat(source.accountId()).isEmpty();
    }

    @Test
    void retiringASourceIsReversible() {
        IncomeSource source = source(household(), "2400.00", Recurrence.monthly(28));

        source.deactivate();
        assertThat(source.isActive()).isFalse();
        source.activate();
        assertThat(source.isActive()).isTrue();
    }

    @Test
    void aSourceNeedsAHousehold() {
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                IncomeSource.of(
                                        null,
                                        "Salary",
                                        Money.of("1.00", "GBP"),
                                        Recurrence.monthly(1),
                                        GBP));
    }

    private static IncomeSource source(Household household, String amount, Recurrence recurrence) {
        return Ids.withId(
                IncomeSource.of(household, "Salary", Money.of(amount, "GBP"), recurrence, GBP));
    }

    private static Household household() {
        return Ids.withId(Household.named("Ada and Grace", GBP));
    }
}
