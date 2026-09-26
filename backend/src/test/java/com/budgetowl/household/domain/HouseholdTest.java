package com.budgetowl.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class HouseholdTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void aNewHouseholdKnowsItsNameAndBaseCurrency() {
        Household household = Household.named("Home", GBP);

        assertThat(household.name()).isEqualTo("Home");
        assertThat(household.baseCurrency()).isEqualTo(GBP);
        assertThat(household.id()).isNull();
        assertThat(household.createdAt()).isNull();
        assertThat(household.updatedAt()).isNull();
    }

    @Test
    void refusesToExistWithoutANameOrACurrency() {
        assertThatNullPointerException().isThrownBy(() -> Household.named(null, GBP));
        assertThatNullPointerException().isThrownBy(() -> Household.named("Home", null));
    }

    @Test
    void canBeRenamed() {
        Household household = Household.named("Home", GBP);

        household.rename("The Lovelaces");

        assertThat(household.name()).isEqualTo("The Lovelaces");
        assertThatNullPointerException().isThrownBy(() -> household.rename(null));
    }

    @Test
    void changingTheBaseCurrencyChangesWhatIsReportedAndNotWhatIsStored() {
        // ADR-0022: amounts stay in their account's own currency and are converted for display.
        Household household = Household.named("Home", GBP);

        household.changeBaseCurrency(EUR);

        assertThat(household.baseCurrency()).isEqualTo(EUR);
        assertThatNullPointerException().isThrownBy(() -> household.changeBaseCurrency(null));
    }

    @Test
    void identityComesFromTheRow() {
        Household saved = Ids.withId(Household.named("Home", GBP));
        Household sameRow = Ids.withId(Household.named("Home", GBP), Ids.idOf(saved));
        Household unsaved = Household.named("Home", GBP);

        assertThat(saved).isEqualTo(sameRow).isNotEqualTo(Ids.withId(Household.named("Home", GBP)));
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(Household.named("Home", GBP));
        assertThat(saved).isNotEqualTo("not a household");
        assertThat(saved.toString()).contains(Ids.idOf(saved).toString());
    }
}
