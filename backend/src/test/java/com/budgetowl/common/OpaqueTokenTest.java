package com.budgetowl.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shape of a credential this application hands out.
 *
 * <p>The hash shape is not a detail: {@code ck_auth_tokens_token_hash_sha256} and {@code
 * ck_household_invitations_token_hash_sha256} both require lowercase hex, so a token hashed any
 * other way cannot be stored at all — which is the schema's way of making a plaintext credential
 * unwritable.
 */
class OpaqueTokenTest {

    @Test
    void hashesToLowercaseHexSha256() {
        assertThat(OpaqueToken.generate().sha256Hex()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hashesTheSameValueTheSameWayEveryTime() {
        assertThat(OpaqueToken.of("a-known-value").sha256Hex())
                .isEqualTo(OpaqueToken.of("a-known-value").sha256Hex());
    }

    @Test
    void hashesDifferentValuesDifferently() {
        assertThat(OpaqueToken.of("one").sha256Hex())
                .isNotEqualTo(OpaqueToken.of("two").sha256Hex());
    }

    @Test
    void generatesAValueNobodyCanGuess() {
        Set<String> seen = new HashSet<>();
        for (int issued = 0; issued < 1_000; issued++) {
            seen.add(OpaqueToken.generate().value());
        }

        assertThat(seen).hasSize(1_000);
        assertThat(OpaqueToken.generate().value()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void doesNotPrintItself() {
        OpaqueToken token = OpaqueToken.generate();

        assertThat(token.toString())
                .as("a credential that prints itself ends up in a log")
                .doesNotContain(token.value())
                .isEqualTo("OpaqueToken[redacted]");
    }
}
