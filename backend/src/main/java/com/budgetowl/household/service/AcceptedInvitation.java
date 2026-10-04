package com.budgetowl.household.service;

import com.budgetowl.household.domain.HouseholdRole;
import java.util.UUID;

/**
 * What the joiner is told after accepting: which household they are now in, and as what.
 *
 * <p>No session and no token comes back with it. Accepting a link proves possession of the link,
 * not of the account, so the new member signs in afterwards like anybody else — an invitation that
 * also handed out a credential would turn a forwarded link into an account takeover.
 *
 * <p>{@code email} is the address of the account now in the household, read back from the account
 * rather than from the invitation. A joiner who has just been registered has no other way to learn
 * what to sign in with: the inviter typed the address, normalisation may have changed it, and there
 * is no email delivery to tell them. Returning it only here — after a token has been consumed by a
 * successful accept — tells the new member their own address and nobody else anything.
 */
public record AcceptedInvitation(
        UUID householdId,
        String householdName,
        HouseholdRole role,
        boolean userCreated,
        String email) {}
