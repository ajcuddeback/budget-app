package com.budgetowl.household.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.persistence.HouseholdMemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves <b>which household</b> the caller is acting in and <b>what their role permits</b> — the
 * two axes of every authorization decision in the product (ADR-0008, amended by ADR-0017 and
 * ADR-0026), both in the service layer and neither in a controller.
 *
 * <p>The household is read from the caller's <em>verified membership</em> and never from the
 * request. An instance holds exactly one household (ADR-0026), so this looks like ceremony today —
 * it is not. Since ADR-0026 removed the possibility of cross-household leakage, role enforcement
 * and authentication carry the weight household scoping used to share, and this is where both are
 * applied.
 *
 * <p>A signed-in user with no membership is a real and supported state: an OIDC-provisioned user
 * before anyone invited them. They authenticate successfully and get {@code 403} from a household
 * endpoint — not a crash, not an empty success.
 *
 * <p>Not {@code @Transactional}: every method here is called from inside a service method that
 * already owns one, and the membership it returns has to be readable and mutable there.
 */
@Service
public class MembershipService {

    private final HouseholdMemberRepository members;

    public MembershipService(HouseholdMemberRepository members) {
        this.members = members;
    }

    /** Present only when the user actually belongs to the household. */
    public Optional<HouseholdMember> find(UUID userId) {
        return members.findByUserId(userId);
    }

    /**
     * @throws ForbiddenException when the caller has no membership at all
     */
    public HouseholdMember requireMembership(UUID userId) {
        return find(userId)
                .orElseThrow(
                        () ->
                                new ForbiddenException(
                                        ErrorCode.NOT_A_MEMBER,
                                        "caller has no household membership"));
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}, who may read and never write
     */
    public HouseholdMember requireWriteAccess(UUID userId) {
        HouseholdMember membership = requireMembership(userId);
        if (!membership.role().canWrite()) {
            throw new ForbiddenException(ErrorCode.READ_ONLY_ROLE, "role may not write");
        }
        return membership;
    }

    /**
     * @throws ForbiddenException for anyone but an {@code OWNER} — invitations, role changes,
     *     removals and household settings are theirs alone
     */
    public HouseholdMember requireOwnership(UUID userId) {
        HouseholdMember membership = requireMembership(userId);
        if (!membership.role().isOwner()) {
            throw new ForbiddenException(ErrorCode.OWNER_ONLY, "role is not OWNER");
        }
        return membership;
    }
}
