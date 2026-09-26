package com.budgetowl.household.service;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.auth.service.AuthRateLimiter;
import com.budgetowl.auth.service.UserRegistrationService;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.OpaqueToken;
import com.budgetowl.config.InvitationProperties;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdInvitation;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.household.persistence.HouseholdInvitationRepository;
import com.budgetowl.household.persistence.HouseholdMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations: the only way to join a household once the first user exists.
 *
 * <p>There is no SMTP server on a self-hosted box and requiring one would be a bad first experience
 * (ADR-0016), so an invitation is a <b>link the owner shares however they like</b>. That makes the
 * link a credential, and it is treated as one throughout: high-entropy, stored as a SHA-256, never
 * logged, never written into a URL we keep, single-use, expiring, and revocable.
 *
 * <p><b>Every unusable invitation gets the same answer.</b> Expired, revoked, already accepted and
 * never existed are one response with one code, because telling them apart tells whoever found a
 * link in a chat backup what happened to it — and "already accepted" in particular confirms that
 * somebody joined.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final HouseholdInvitationRepository invitations;
    private final HouseholdMemberRepository members;
    private final UserAccountRepository users;
    private final MembershipService memberships;
    private final UserRegistrationService registration;
    private final AuthRateLimiter rateLimiter;
    private final InvitationProperties properties;
    private final Clock clock;

    public InvitationService(
            HouseholdInvitationRepository invitations,
            HouseholdMemberRepository members,
            UserAccountRepository users,
            MembershipService memberships,
            UserRegistrationService registration,
            AuthRateLimiter rateLimiter,
            InvitationProperties properties,
            Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.users = users;
        this.memberships = memberships;
        this.registration = registration;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws ConflictException if the address is already a member — refused without confirming
     *     that it is, because an owner-only endpoint is still a place not to build an oracle
     */
    @Transactional
    public CreatedInvitation create(UUID actorId, String email, HouseholdRole role) {
        HouseholdMember actor = memberships.requireOwnership(actorId);
        Household household = actor.household();
        String address = email.strip();

        if (members.existsByHouseholdIdAndUserEmail(household.id(), address)) {
            throw new ConflictException(
                    ErrorCode.INVITATION_REFUSED, "the address cannot be invited");
        }

        OpaqueToken token = OpaqueToken.generate();
        HouseholdInvitation invitation =
                invitations.save(
                        HouseholdInvitation.create(
                                household,
                                address,
                                role,
                                token.sha256Hex(),
                                clock.instant().plus(properties.timeToLive()),
                                actor.user()));

        log.info(
                "invitation created householdId={} invitationId={} byUserId={} role={}",
                household.id(),
                invitation.id(),
                actorId,
                role);
        return new CreatedInvitation(
                invitation.id(), invitation.email(), role, token.value(), invitation.expiresAt());
    }

    @Transactional
    public void revoke(UUID actorId, UUID invitationId) {
        HouseholdMember actor = memberships.requireOwnership(actorId);
        HouseholdInvitation invitation =
                invitations
                        .findByIdAndHouseholdId(invitationId, actor.household().id())
                        .orElseThrow(() -> new NotFoundException("no such invitation"));
        if (invitation.acceptedAt() != null) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "an accepted invitation cannot be revoked");
        }
        invitation.revokeAt(clock.instant());
        log.info(
                "invitation revoked householdId={} invitationId={} byUserId={}",
                actor.household().id(),
                invitationId,
                actorId);
    }

    /**
     * Joins the household, creating the user if this is their first sight of the instance.
     *
     * <p>Single-use and expiry are enforced <em>in this transaction</em>: the invitation is marked
     * accepted alongside the membership insert, so the two cannot diverge, and a second attempt
     * finds it spent. Two simultaneous attempts are stopped by the database as well — {@code
     * uq_household_members_household_id_user_id} for an existing user, {@code uq_users_email} for a
     * new one.
     *
     * @param currentUserId the signed-in caller, if any. Accepting while signed in is refused:
     *     "join as whoever happens to be logged in" is how a forwarded link adds the wrong person
     *     to a household.
     */
    @Transactional
    public AcceptedInvitation accept(
            String presentedToken,
            String displayName,
            String rawPassword,
            UUID currentUserId,
            String clientIp) {
        String tokenHash = OpaqueToken.of(presentedToken).sha256Hex();
        List<String> keys =
                List.of(AuthRateLimiter.ip(clientIp), AuthRateLimiter.tokenHash(tokenHash));
        rateLimiter.requireAllowed(keys);

        if (currentUserId != null) {
            throw new ConflictException(
                    ErrorCode.INVITATION_REQUIRES_SIGN_OUT,
                    "sign out before accepting an invitation");
        }

        Instant now = clock.instant();
        HouseholdInvitation invitation =
                invitations
                        .findByTokenHash(tokenHash)
                        .filter(found -> found.isUsableAt(now))
                        .orElseThrow(
                                () -> {
                                    rateLimiter.recordFailure(keys);
                                    return new NotFoundException(
                                            ErrorCode.INVITATION_UNUSABLE,
                                            "invitation is not usable");
                                });

        Household household = invitation.household();
        Optional<UserAccount> existing = users.findByEmail(invitation.email());
        UserAccount joining =
                existing.orElseGet(() -> register(invitation.email(), displayName, rawPassword));

        if (members.existsByHouseholdIdAndUserId(household.id(), joining.id())) {
            throw new ConflictException(
                    ErrorCode.INVITATION_REFUSED, "the address cannot be invited");
        }

        invitation.acceptAt(now);
        members.save(HouseholdMember.of(household, joining, invitation.role()));
        rateLimiter.recordSuccess(keys);

        log.info(
                "invitation accepted householdId={} invitationId={} userId={} created={}",
                household.id(),
                invitation.id(),
                joining.id(),
                existing.isEmpty());
        return new AcceptedInvitation(
                household.id(), household.name(), invitation.role(), existing.isEmpty());
    }

    /**
     * The invited address is the new user's address, and the request cannot choose another one. The
     * email on an invitation is a label rather than an authorization — but letting the joiner name
     * themselves anything would turn it into an account-creation endpoint with a token attached.
     */
    private UserAccount register(String email, String displayName, String rawPassword) {
        if (displayName == null || displayName.isBlank() || rawPassword == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a new user needs a display name and a password",
                    Map.of("fields", List.of("displayName", "password")));
        }
        return registration.register(email, displayName, rawPassword, false);
    }
}
