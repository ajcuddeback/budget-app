package com.budgetowl.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import org.junit.jupiter.api.Test;

/**
 * The verification behaviour. That the hash cannot be read back out is proved separately, by {@code
 * SecretsAreUnreadableTest}, which is the test that fails if someone adds a getter.
 */
class PasswordCredentialTest {

    private static final String ENCODED = "{bcrypt}$2a$12$0123456789012345678901";

    @Test
    void aUserWithNoPasswordMatchesNothing() {
        // An OIDC-provisioned user. This must not throw: the caller's unknown-user and
        // wrong-password paths have to stay indistinguishable.
        PasswordCredential credential = new PasswordCredential();

        assertThat(credential.isSet()).isFalse();
        assertThat(credential.matches("anything", (raw, encoded) -> true)).isFalse();
    }

    @Test
    void matchesThroughTheSuppliedMatcherAndNotByComparingStrings() {
        PasswordCredential credential = new PasswordCredential();
        credential.replaceWith(ENCODED);

        assertThat(credential.isSet()).isTrue();
        assertThat(credential.matches("hunter2", (raw, encoded) -> encoded.equals(ENCODED)))
                .isTrue();
        assertThat(credential.matches("hunter2", (raw, encoded) -> false)).isFalse();
    }

    @Test
    void treatsANullPasswordAsNoMatchRatherThanAnError() {
        PasswordCredential credential = new PasswordCredential();
        credential.replaceWith(ENCODED);

        assertThat(credential.matches(null, (raw, encoded) -> true)).isFalse();
    }

    @Test
    void refusesToVerifyWithoutAMatcherOrToStoreANullHash() {
        PasswordCredential credential = new PasswordCredential();

        assertThatNullPointerException().isThrownBy(() -> credential.matches("hunter2", null));
        assertThatNullPointerException().isThrownBy(() -> credential.replaceWith(null));
    }

    @Test
    void canBeClearedWithoutDeletingTheUser() {
        PasswordCredential credential = new PasswordCredential();
        credential.replaceWith(ENCODED);

        credential.clear();

        assertThat(credential.isSet()).isFalse();
    }

    @Test
    void identityComesFromTheRow() {
        PasswordCredential saved = Ids.withUserId(new PasswordCredential());
        PasswordCredential sameRow = Ids.withUserId(new PasswordCredential(), saved.userId());

        assertThat(saved).isEqualTo(sameRow).isNotEqualTo(Ids.withUserId(new PasswordCredential()));
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(saved).isNotEqualTo("not a credential");
        PasswordCredential unsaved = new PasswordCredential();
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(new PasswordCredential());
    }

    @Test
    void saysWhetherAPasswordIsSetWithoutSayingWhatItIs() {
        PasswordCredential credential = Ids.withUserId(new PasswordCredential());
        credential.replaceWith(ENCODED);

        assertThat(credential.toString()).contains("set=true").doesNotContain(ENCODED);
    }
}
