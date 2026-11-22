package com.budgetowl.admin.service;

import com.budgetowl.common.Change;

/**
 * A partial change to how people sign in.
 *
 * <p>{@code oidcClientSecret} is write-only and three-state like any other optional field: absent
 * leaves the stored one alone, a value replaces it, and clearing it removes it. What it cannot do
 * is come back out — see {@link SettingsView}.
 */
public record SettingsChanges(
        Boolean registrationOpen,
        Boolean passwordLoginEnabled,
        Boolean oidcEnabled,
        String oidcIssuerUri,
        String oidcClientId,
        Change<String> oidcClientSecret,
        Boolean oidcProvisioningEnabled,
        long version) {}
