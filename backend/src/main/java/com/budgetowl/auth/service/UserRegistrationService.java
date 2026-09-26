package com.budgetowl.auth.service;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.PasswordCredentialRepository;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates a user with a password — the two ways that happens being first-run setup and accepting an
 * invitation. Registration is closed everywhere else: after the first user, an invitation link is
 * the only route in (docs/features/authentication-and-households.md).
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> Both callers are already inside a transaction
 * that must succeed or fail whole — a user created without their household membership, or a first
 * user created by the caller that lost the setup race, is exactly the state this feature cannot
 * have. Joining the caller's transaction is the behaviour; starting one here would hide it.
 */
@Service
public class UserRegistrationService {

    private final UserAccountRepository users;
    private final PasswordCredentialRepository credentials;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    public UserRegistrationService(
            UserAccountRepository users,
            PasswordCredentialRepository credentials,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            EntityManager entityManager) {
        this.users = users;
        this.credentials = credentials;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
    }

    /**
     * Inserts the user, then sets their password with a separate statement.
     *
     * <p>Two statements because {@code users.password_hash} is nullable — an OIDC-provisioned user
     * has none — and {@link UserAccount} deliberately does not map the column at all, so that no
     * service can put a hash in a response by accident. The explicit flush is not decoration: the
     * update is a bulk statement against a row that only exists in the persistence context until
     * something pushes it out.
     */
    public UserAccount register(
            String email, String displayName, String rawPassword, boolean instanceAdministrator) {
        passwordPolicy.requireAcceptable(rawPassword);
        String address = email.strip();
        if (users.existsByEmail(address)) {
            // Reachable only by a racing caller: setup permits one user and an invitation is
            // addressed to somebody who is not a member yet. Generic on purpose — this response
            // must not confirm an address to whoever holds an invitation link.
            throw new ConflictException(
                    ErrorCode.CONFLICT, "a user with that address already exists");
        }
        UserAccount user =
                users.save(
                        instanceAdministrator
                                ? UserAccount.instanceAdministrator(address, displayName.strip())
                                : UserAccount.member(address, displayName.strip()));
        entityManager.flush();
        credentials.updatePassword(user.id(), passwordEncoder.encode(rawPassword));
        return user;
    }
}
