package com.budgetowl.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.domain.Ids;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class HouseholdMemberTest {

    private final Household household = Household.named("Home", Currency.getInstance("GBP"));
    private final UserAccount ada = UserAccount.member("ada@example.com", "Ada");

    @Test
    void aNewMemberHasNoPreferencesOfTheirOwn() {
        // Null means "fall back to the household's base currency" / "the platform locale"
        // (ADR-0022, ADR-0023) — a meaning, not an unknown.
        HouseholdMember member = HouseholdMember.of(household, ada, HouseholdRole.MEMBER);

        assertThat(member.displayCurrency()).isEmpty();
        assertThat(member.locale()).isEmpty();
        assertThat(member.role()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(member.household()).isSameAs(household);
        assertThat(member.user()).isSameAs(ada);
        assertThat(member.joinedAt()).isNull();
    }

    @Test
    void refusesToExistWithoutAHouseholdAUserOrARole() {
        assertThatNullPointerException()
                .isThrownBy(() -> HouseholdMember.of(null, ada, HouseholdRole.MEMBER));
        assertThatNullPointerException()
                .isThrownBy(() -> HouseholdMember.of(household, null, HouseholdRole.MEMBER));
        assertThatNullPointerException().isThrownBy(() -> HouseholdMember.of(household, ada, null));
    }

    @Test
    void aMemberMaySeeTheirOwnCurrencyAndLanguage() {
        // One household, two people, two languages is a normal case rather than an edge one.
        HouseholdMember member = HouseholdMember.of(household, ada, HouseholdRole.MEMBER);

        member.preferDisplayCurrency(Currency.getInstance("EUR"));
        member.preferLocale(Locale.forLanguageTag("fr-CA"));

        assertThat(member.displayCurrency()).contains(Currency.getInstance("EUR"));
        assertThat(member.locale()).contains(Locale.forLanguageTag("fr-CA"));
    }

    @Test
    void clearingAPreferenceFallsBackToTheHouseholdAndThePlatform() {
        HouseholdMember member = HouseholdMember.of(household, ada, HouseholdRole.MEMBER);
        member.preferDisplayCurrency(Currency.getInstance("EUR"));
        member.preferLocale(Locale.forLanguageTag("fr-CA"));

        member.preferDisplayCurrency(null);
        member.preferLocale(null);

        assertThat(member.displayCurrency()).isEmpty();
        assertThat(member.locale()).isEmpty();
    }

    @Test
    void aRoleCanBeChangedButNotRemoved() {
        HouseholdMember member = HouseholdMember.of(household, ada, HouseholdRole.OWNER);

        member.changeRoleTo(HouseholdRole.VIEWER);

        assertThat(member.role()).isEqualTo(HouseholdRole.VIEWER);
        assertThatNullPointerException().isThrownBy(() -> member.changeRoleTo(null));
    }

    @Test
    void identityComesFromTheRow() {
        HouseholdMember saved =
                Ids.withId(HouseholdMember.of(household, ada, HouseholdRole.MEMBER));
        HouseholdMember sameRow =
                Ids.withId(
                        HouseholdMember.of(household, ada, HouseholdRole.OWNER), Ids.idOf(saved));
        HouseholdMember unsaved = HouseholdMember.of(household, ada, HouseholdRole.MEMBER);

        assertThat(saved).isEqualTo(sameRow);
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(saved).isNotEqualTo("not a member");
        assertThat(saved.toString()).contains("MEMBER");
    }
}
