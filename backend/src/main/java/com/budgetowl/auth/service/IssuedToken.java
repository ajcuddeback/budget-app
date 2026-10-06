package com.budgetowl.auth.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly issued bearer token, on its way to the device that asked for it.
 *
 * <p><b>The only object in the application that carries a token value</b>, and it exists for one
 * response. What is stored is the SHA-256; the value here is never written to the database, never
 * logged and never returned again — if the device loses it, it asks for another one.
 *
 * @param value the credential, shown exactly once
 */
public record IssuedToken(UUID id, String value, String deviceLabel, Instant expiresAt) {

    /** Redacted: a record's generated {@code toString} would print the token. */
    @Override
    public String toString() {
        return "IssuedToken[id=" + id + ", expiresAt=" + expiresAt + "]";
    }
}
