package com.budgetowl.admin.service;

import java.time.Instant;

/**
 * How this instance lets people sign in.
 *
 * <p><b>There is no client secret on this record, and there never will be.</b> A secret that is
 * read back lands in a browser cache, a screenshot, an HTTP log and a bug report, and there is no
 * operational need for it: an administrator who cannot remember it replaces it, which is also what
 * they should do if they are unsure whether it leaked.
 *
 * @param oidcClientSecretSet whether one is configured — the only thing any surface may know
 * @param passwordLoginLocked whether password login cannot currently be turned off, because no
 *     {@code OWNER} has completed an OIDC login yet. The console states the rule beside the switch
 *     rather than offering a control that fails
 */
public record SettingsView(
        boolean registrationOpen,
        boolean passwordLoginEnabled,
        boolean passwordLoginLocked,
        boolean oidcEnabled,
        String oidcIssuerUri,
        String oidcClientId,
        boolean oidcClientSecretSet,
        boolean oidcProvisioningEnabled,
        Instant oidcOwnerLoginAt,
        Instant updatedAt,
        long version) {}
