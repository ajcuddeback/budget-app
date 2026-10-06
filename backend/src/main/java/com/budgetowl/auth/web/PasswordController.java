package com.budgetowl.auth.web;

import com.budgetowl.auth.service.PasswordService;
import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.auth.web.AuthRequests.ChangePasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Changing your own password — the only password endpoint in this slice.
 *
 * <p>There is no reset-by-email flow and there will not be one: a self-hoster may have no SMTP
 * server, and requiring one to recover an account would be a bad first experience and a hard
 * dependency (ADR-0016). An {@code OWNER} resetting a member's password, and a CLI command that
 * resets any password from the host shell, are the documented routes — both later slices.
 */
@RestController
@RequestMapping("/api/auth/password")
public class PasswordController {

    private final PasswordService passwords;

    public PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /**
     * Succeeds with no content, and signs the caller out everywhere including here: every session
     * and token of theirs is revoked, so the next request needs the new password.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(TransportAuthentication caller, @Valid @RequestBody ChangePasswordRequest body) {
        passwords.changeOwnPassword(caller.userId(), body.currentPassword(), body.newPassword());
    }
}
