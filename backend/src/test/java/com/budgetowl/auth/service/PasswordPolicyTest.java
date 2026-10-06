package com.budgetowl.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What counts as an acceptable password: length beats complexity, and no composition rules.
 *
 * <p>The refusal must never quote the password back — a validation message that echoes its input is
 * how a password reaches a log.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsALongPassphraseWithNoSpecialCharacters() {
        assertThatCode(() -> policy.requireAcceptable("correct place example battery"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "elevenchars"})
    void refusesAnythingUnderTwelveCharacters(String tooShort) {
        assertThatThrownBy(() -> policy.requireAcceptable(tooShort))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesNull() {
        assertThatThrownBy(() -> policy.requireAcceptable(null))
                .isInstanceOf(InvalidRequestException.class);
    }

    /** Not a policy choice: BCrypt ignores everything past 72 bytes and the encoder throws. */
    @Test
    void refusesAPasswordTooLongForTheEncoder() {
        assertThatThrownBy(() -> policy.requireAcceptable("x".repeat(73)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(PasswordPolicy.isEncodable("x".repeat(73))).isFalse();
    }

    @Test
    void countsBytesRatherThanCharacters() {
        // Twenty-five characters, seventy-five bytes: the encoder counts the bytes.
        assertThat(PasswordPolicy.isEncodable("🔐".repeat(19))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"correcthorsebatterystaple", "passwordpassword", "PasswordPassword"})
    void refusesAPasswordFromTheBundledBreachedList(String breached) {
        assertThatThrownBy(() -> policy.requireAcceptable(breached))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesWithoutEverQuotingThePassword() {
        String submitted = "correcthorsebatterystaple";

        assertThatThrownBy(() -> policy.requireAcceptable(submitted))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(
                        failure -> {
                            InvalidRequestException refusal = (InvalidRequestException) failure;
                            assertThat(refusal.getMessage()).doesNotContain(submitted);
                            assertThat(refusal.params().toString()).doesNotContain(submitted);
                        });
    }
}
