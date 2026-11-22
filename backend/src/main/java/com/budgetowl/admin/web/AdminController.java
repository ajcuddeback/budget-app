package com.budgetowl.admin.web;

import com.budgetowl.admin.service.AdminService;
import com.budgetowl.admin.service.SettingsChanges;
import com.budgetowl.admin.web.AdminRequests.UpdateSettingsRequest;
import com.budgetowl.admin.web.AdminResponses.AccountsResponse;
import com.budgetowl.admin.web.AdminResponses.InstanceResponse;
import com.budgetowl.admin.web.AdminResponses.SettingsResponse;
import com.budgetowl.auth.service.TransportAuthentication;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The instance an operator runs.
 *
 * <p><b>Every route here requires the instance administrator</b> (ADR-0029), enforced in {@link
 * AdminService} and not by hiding the link. The console hides it from everyone else; the API
 * refuses them. A household {@code OWNER} is refused exactly like a {@code VIEWER}: the two are
 * different axes, and being trusted with a household's money is not being trusted with the server's
 * login configuration.
 *
 * <p>A refusal is {@code 403 not-instance-admin}, not {@code 404}. Another household's row is
 * hidden because its existence is a secret; this instance's existence is not a secret from the
 * people signed in to it.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/instance")
    InstanceResponse instance(TransportAuthentication caller) {
        return InstanceResponse.of(admin.instance(caller.userId()));
    }

    @GetMapping("/settings")
    SettingsResponse settings(TransportAuthentication caller) {
        return SettingsResponse.of(admin.settings(caller.userId()));
    }

    /** The client secret may be sent here. It is never sent back — see {@code AdminResponses}. */
    @PatchMapping("/settings")
    SettingsResponse updateSettings(
            TransportAuthentication caller, @Valid @RequestBody UpdateSettingsRequest body) {
        return SettingsResponse.of(
                admin.updateSettings(
                        caller.userId(),
                        new SettingsChanges(
                                body.registrationOpen(),
                                body.passwordLoginEnabled(),
                                body.oidcEnabled(),
                                body.oidcIssuerUri(),
                                body.oidcClientId(),
                                body.oidcClientSecretChange(),
                                body.oidcProvisioningEnabled(),
                                body.version())));
    }

    @GetMapping("/users")
    AccountsResponse accounts(TransportAuthentication caller) {
        return AccountsResponse.of(admin.accounts(caller.userId()));
    }
}
