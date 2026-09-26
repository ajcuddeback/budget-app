package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.PasswordCredential;
import com.budgetowl.auth.persistence.PasswordCredentialRepository;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.NotFoundException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing your own password.
 *
 * <p>The current password is verified first, so a stolen but still-open session cannot quietly take
 * permanent ownership of the account. That is the whole point of asking for it: the attacker
 * already has the session, and what this stops them doing is keeping it after the real user closes
 * the laptop.
 *
 * <p>Every one of the user's sessions and tokens is then revoked, <b>including the one that made
 * this request</b>. Changing a password you think has leaked and staying signed in everywhere it
 * leaked to would be worth very little, so the user signs in again afterwards — on purpose.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final PasswordCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final DeviceService devices;

    public PasswordService(
            PasswordCredentialRepository credentials,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            DeviceService devices) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.devices = devices;
    }

    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        PasswordCredential credential =
                credentials
                        .findById(userId)
                        .orElseThrow(() -> new NotFoundException("no such user"));
        if (!PasswordPolicy.isEncodable(currentPassword)
                || !credential.matches(currentPassword, passwordEncoder::matches)) {
            // 403 rather than 401: the caller is authenticated and stays authenticated. Answering
            // 401 here would sign a user out for mistyping their old password.
            throw new ForbiddenException(
                    ErrorCode.CURRENT_PASSWORD_INCORRECT, "current password did not match");
        }
        passwordPolicy.requireAcceptable(newPassword);
        credentials.updatePassword(userId, passwordEncoder.encode(newPassword));
        devices.revokeEverythingFor(userId);
        log.info("password changed userId={}", userId);
    }
}
