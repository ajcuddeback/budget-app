package com.budgetowl.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A high-entropy credential that is shown to its owner once and stored only as a hash: a mobile
 * bearer token (ADR-0018) and an invitation link token.
 *
 * <p>256 bits from {@link SecureRandom}. The stored form is a <b>lowercase-hex SHA-256</b>, which
 * is the shape {@code ck_auth_tokens_token_hash_sha256} and {@code
 * ck_household_invitations_token_hash_sha256} enforce — so a plaintext credential cannot reach the
 * table even by mistake, and a database dump yields nothing that works.
 *
 * <p>A fast hash is the right one here, unlike for a password: the value is already 256 bits of
 * randomness, so there is nothing to guess and stretching would only cost every request. Lookup is
 * an index probe on the hash rather than a byte comparison of a secret, which is why the threat
 * model rules out timing attacks on it.
 *
 * <p>{@link #toString()} is redacted. A credential that prints itself ends up in a log.
 */
public final class OpaqueToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ENTROPY_BYTES = 32;

    private final String value;

    private OpaqueToken(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static OpaqueToken generate() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return new OpaqueToken(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }

    /** Wraps a token that arrived from a client, so it can be hashed for lookup. */
    public static OpaqueToken of(String presented) {
        return new OpaqueToken(presented);
    }

    /** The value to hand to its owner, once. Never persisted, never logged. */
    public String value() {
        return value;
    }

    public String sha256Hex() {
        return sha256Hex(value);
    }

    /** Also used for session handles, which are cookies and therefore credentials too. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    @Override
    public String toString() {
        return "OpaqueToken[redacted]";
    }
}
