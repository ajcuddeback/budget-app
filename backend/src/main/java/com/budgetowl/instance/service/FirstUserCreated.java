package com.budgetowl.instance.service;

import java.util.UUID;

/** The instance's first user and the household they now own. */
public record FirstUserCreated(
        UUID userId,
        String email,
        String displayName,
        UUID householdId,
        String householdName,
        String baseCurrency) {}
