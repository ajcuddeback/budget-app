package com.budgetowl.admin.service;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one check every administrative route makes.
 *
 * <p><b>A separate axis from household roles</b> (ADR-0029). {@code MembershipService} answers "may
 * this person touch this household's money"; this answers "may this person operate this server".
 * Neither implies the other, which is why this is its own class rather than another method on that
 * one — a household {@code OWNER} is refused here exactly like a {@code VIEWER}.
 *
 * <p>Read from the stored flag on every request, never from anything the caller supplies and never
 * from a token: {@code TransportAuthentication} carries no authorities, so a session minted before
 * the flag moved cannot still act on it.
 */
@Service
public class AdministratorAccess {

    private final UserAccountRepository users;

    public AdministratorAccess(UserAccountRepository users) {
        this.users = users;
    }

    /**
     * @throws ForbiddenException {@code not-instance-admin} for everybody else, including an {@code
     *     OWNER}, and including a caller whose account has since been removed
     */
    @Transactional(readOnly = true)
    public UserAccount requireAdministrator(UUID actorId) {
        return users.findById(actorId)
                .filter(UserAccount::isInstanceAdministrator)
                .orElseThrow(
                        () ->
                                new ForbiddenException(
                                        ErrorCode.NOT_INSTANCE_ADMIN,
                                        "only the instance administrator may do this"));
    }
}
