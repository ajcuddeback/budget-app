package com.budgetowl.household.service;

import com.budgetowl.household.domain.HouseholdRole;
import java.util.UUID;

/**
 * What the joiner is told after accepting: which household they are now in, and as what.
 *
 * <p>No session and no token comes back with it. Accepting a link proves possession of the link,
 * not of the account, so the new member signs in afterwards like anybody else — an invitation that
 * also handed out a credential would turn a forwarded link into an account takeover.
 */
public record AcceptedInvitation(
        UUID householdId, String householdName, HouseholdRole role, boolean userCreated) {}
