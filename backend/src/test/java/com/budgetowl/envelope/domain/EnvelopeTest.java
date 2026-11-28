package com.budgetowl.envelope.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.time.YearMonth;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The guards {@code Envelope} enforces for a caller that never passes through a controller. */
class EnvelopeTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Test
    void anEnvelopeKnowsWhereItLivesWhatMonthItIsForAndWhatWasDecided() {
        Household household = household();

        Envelope envelope = Envelope.of(household, "Groceries", AUGUST, Money.of("400.00", "GBP"));

        assertThat(envelope.householdId()).isEqualTo(household.id());
        assertThat(envelope.name()).isEqualTo("Groceries");
        assertThat(envelope.period()).isEqualTo(AUGUST);
        assertThat(envelope.amount()).isEqualByComparingTo("400.00");
        assertThat(envelope.categoryId()).isEmpty();
        assertThat(envelope.isArchived()).isFalse();
    }

    @Test
    void thePeriodIsKeptAsAMonthWhateverDayWasGiven() {
        // The period is a LABEL (ADR-0031): only the year and month mean anything.
        Envelope envelope = envelope("400.00");

        assertThat(envelope.period()).isEqualTo(AUGUST);
    }

    @Test
    void anAmountOfZeroIsAllowedAndANegativeOneIsNot() {
        Household household = household();

        // Deciding to spend nothing on something is a real decision.
        assertThat(Envelope.of(household, "Takeaways", AUGUST, Money.of("0.00", "GBP")).amount())
                .isEqualByComparingTo("0.00");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Envelope.of(household, "Nope", AUGUST, Money.of("-1.00", "GBP")))
                .withMessageContaining("negative");
    }

    @Test
    void anAmountInAnotherCurrencyIsRefused() {
        Household household = household();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Envelope.of(household, "Reise", AUGUST, Money.of("50.00", "EUR")))
                .withMessageContaining("base currency");
    }

    @Test
    void aNameIsTrimmedAndBounded() {
        Envelope envelope = envelope("400.00");

        envelope.rename("  Food shopping  ");
        assertThat(envelope.name()).isEqualTo("Food shopping");

        assertThatIllegalArgumentException().isThrownBy(() -> envelope.rename("   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> envelope.rename("x".repeat(Envelope.NAME_MAX_LENGTH + 1)));
        assertThatNullPointerException().isThrownBy(() -> envelope.rename(null));

        envelope.rename("x".repeat(Envelope.NAME_MAX_LENGTH));
        assertThat(envelope.name()).hasSize(Envelope.NAME_MAX_LENGTH);
    }

    @Test
    void theAmountCanBeReconsideredAndTheCategoryClearedOrSet() {
        Envelope envelope = envelope("400.00");
        UUID category = UUID.randomUUID();

        envelope.setAmount(Money.of("450.00", "GBP"), GBP);
        assertThat(envelope.amount()).isEqualByComparingTo("450.00");

        envelope.fileUnder(category);
        assertThat(envelope.categoryId()).contains(category);

        // Not every envelope has a category — "Adhoc" is a perfectly good one.
        envelope.fileUnder(null);
        assertThat(envelope.categoryId()).isEmpty();

        assertThatNullPointerException().isThrownBy(() -> envelope.setAmount(null, GBP));
    }

    @Test
    void archivingIsReversibleAndIdempotent() {
        Envelope envelope = envelope("400.00");

        envelope.archive();
        envelope.archive();
        assertThat(envelope.isArchived()).isTrue();

        envelope.unarchive();
        assertThat(envelope.isArchived()).isFalse();
    }

    @Test
    void anEnvelopeNeedsAHouseholdAPeriodAndAnAmount() {
        assertThatNullPointerException()
                .isThrownBy(() -> Envelope.of(null, "Groceries", AUGUST, Money.of("1.00", "GBP")));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> Envelope.of(household(), "Groceries", null, Money.of("1.00", "GBP")));
        assertThatNullPointerException()
                .isThrownBy(() -> Envelope.of(household(), "Groceries", AUGUST, null));
    }

    static Envelope envelope(String amount) {
        return Ids.withId(Envelope.of(household(), "Groceries", AUGUST, Money.of(amount, "GBP")));
    }

    static Household household() {
        return Ids.withId(Household.named("Ada and Grace", GBP));
    }
}
