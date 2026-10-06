package com.budgetowl.household.service;

import com.budgetowl.household.domain.HouseholdRole;
import java.util.UUID;

/**
 * The caller's household as seen through their own membership: what it is, and what they are in it.
 * One object because the two are never useful apart — a household with no role attached is an
 * invitation to forget the role check.
 *
 * @param displayCurrency this member's preference, null when they follow the household's base
 *     currency (ADR-0022)
 * @param locale this member's preference, null when they follow the platform's (ADR-0023)
 */
public record CurrentHousehold(
        UUID householdId,
        String name,
        String baseCurrency,
        HouseholdRole role,
        UUID membershipId,
        String displayCurrency,
        String locale) {}
