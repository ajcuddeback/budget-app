package com.budgetowl.instance.web;

import java.util.UUID;

/** Response bodies for the setup endpoints. */
public final class SetupResponses {

    private SetupResponses() {}

    /**
     * What a visitor may learn before signing in: whether to show a create-first-account screen or
     * a login, and which login routes exist. Nothing about the operator, the household or its
     * members.
     */
    public record SetupStatusResponse(
            boolean setupComplete,
            boolean registrationOpen,
            boolean passwordLoginEnabled,
            boolean oidcEnabled) {}

    /**
     * The first user and their household.
     *
     * <p>No session and no token: setup creates the account, and the operator signs in with it like
     * anybody else. An endpoint that both creates the administrator and hands out a credential has
     * two chances to be wrong instead of one.
     */
    public record FirstUserResponse(
            UUID userId,
            String email,
            String displayName,
            UUID householdId,
            String householdName,
            String baseCurrency) {}
}
