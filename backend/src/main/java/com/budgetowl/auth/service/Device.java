package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.CredentialTransport;
import java.time.Instant;

/**
 * One entry in a user's logged-in devices list: a web session or a mobile token, uniformly.
 *
 * <p><b>{@code id} is a handle, not a credential.</b> For a token it is the row's id, which is
 * useless without the token itself. For a session it is a SHA-256 of the session id — because the
 * session id <em>is</em> the cookie, and a devices screen that listed real session ids would hand
 * every one of a user's live credentials to anyone who could read one response.
 */
public record Device(
        String id,
        CredentialTransport kind,
        String label,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean current) {}
