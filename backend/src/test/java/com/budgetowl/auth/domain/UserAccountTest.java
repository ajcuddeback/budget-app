package com.budgetowl.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import org.junit.jupiter.api.Test;

class UserAccountTest {

    @Test
    void aNewUserIsActive() {
        UserAccount user = UserAccount.member("ada@example.com", "Ada");

        assertThat(user.isActive()).isTrue();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(user.displayName()).isEqualTo("Ada");
        assertThat(user.createdAt()).isNull();
        assertThat(user.updatedAt()).isNull();
    }

    @Test
    void anOrdinaryUserIsNotAnInstanceAdministrator() {
        assertThat(UserAccount.member("ada@example.com", "Ada").isInstanceAdministrator())
                .isFalse();
    }

    @Test
    void theFirstUserOfAFreshInstanceIsItsAdministrator() {
        assertThat(
                        UserAccount.instanceAdministrator("operator@example.com", "Operator")
                                .isInstanceAdministrator())
                .isTrue();
    }

    @Test
    void refusesToExistWithoutAnEmailOrADisplayName() {
        assertThatNullPointerException().isThrownBy(() -> UserAccount.member(null, "Ada"));
        assertThatNullPointerException()
                .isThrownBy(() -> UserAccount.member("ada@example.com", null));
    }

    @Test
    void aDisabledUserIsNoLongerActive() {
        UserAccount user = UserAccount.member("ada@example.com", "Ada");

        user.disable();
        assertThat(user.isActive()).isFalse();
        assertThat(user.status()).isEqualTo(UserStatus.DISABLED);

        user.enable();
        assertThat(user.isActive()).isTrue();
    }

    @Test
    void canBeRenamedButNotToNothing() {
        UserAccount user = UserAccount.member("ada@example.com", "Ada");

        user.rename("Ada Lovelace");

        assertThat(user.displayName()).isEqualTo("Ada Lovelace");
        assertThatNullPointerException().isThrownBy(() -> user.rename(null));
    }

    @Test
    void twoUsersAreTheSameWhenTheyShareAnIdentity() {
        UserAccount saved = Ids.withId(UserAccount.member("ada@example.com", "Ada"));
        UserAccount sameRow =
                Ids.withId(UserAccount.member("ada@example.com", "Ada"), Ids.idOf(saved));
        UserAccount otherRow = Ids.withId(UserAccount.member("grace@example.com", "Grace"));

        assertThat(saved).isEqualTo(saved).isEqualTo(sameRow).isNotEqualTo(otherRow);
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
    }

    @Test
    void anUnsavedUserIsEqualToNothingAtAll() {
        // Identity comes from the row. Two unsaved instances are not "the same user" just because
        // they happen to hold the same values.
        UserAccount unsaved = UserAccount.member("ada@example.com", "Ada");

        assertThat(unsaved).isNotEqualTo(UserAccount.member("ada@example.com", "Ada"));
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo("not a user");
    }

    @Test
    void neverNamesItsOwnerInItsStringForm() {
        // This is the string that ends up in a log line. Member addresses are PII and a target
        // list for whoever finds the instance.
        UserAccount user = Ids.withId(UserAccount.member("ada@example.com", "Ada"));

        assertThat(user.toString()).doesNotContain("ada@example.com").doesNotContain("Ada");
    }
}
