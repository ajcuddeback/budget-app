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
import com.budgetowl.household.persistence.HouseholdRepository;
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
    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final UserAccountRepository users;
    private final MembershipService memberships;
    private final UserRegistrationService registration;
    private final AuthRateLimiter rateLimiter;
    private final InvitationProperties properties;
    private final Clock clock;

    public InvitationService(
            HouseholdInvitationRepository invitations,
            HouseholdRepository households,
            HouseholdMemberRepository members,
            UserAccountRepository users,
            MembershipService memberships,
            UserRegistrationService registration,
            AuthRateLimiter rateLimiter,
            InvitationProperties properties,
            Clock clock) {
        this.invitations = invitations;
        this.households = households;
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
     * <p>Single-use is a <b>conditional UPDATE</b> taken before anything else happens in this
     * transaction ({@code HouseholdInvitationRepository.markAccepted}), for the same reason
     * first-run setup is claimed that way: reading the invitation and then writing it cannot be
     * single-use, because two holders of one link read it at the same instant and both see it
     * unused. The loser blocks on the row lock, matches nothing, and is answered exactly as if the
     * link had never existed. The membership insert is in the same transaction, so a failure later
     * un-spends the invitation rather than stranding it.
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
        HouseholdInvitation found =
                invitations
                        .findByTokenHash(tokenHash)
                        .filter(invitation -> invitation.isUsableAt(now))
                        .orElseThrow(() -> unusable(keys));

        UUID invitationId = found.id();
        UUID householdId = found.household().id();
        HouseholdRole role = found.role();
        String invitedEmail = found.email();

        // Claim it before anything else happens, and take the row lock as the claim. The read
        // above cannot be the check: two holders of the same link read it at the same instant,
        // both see an unused invitation, and both join on one single-use token. The loser of the
        // conditional UPDATE is answered exactly as if the link had never existed.
        if (invitations.markAccepted(invitationId, now) == 0) {
            throw unusable(keys);
        }

        Optional<UserAccount> existing = users.findByEmail(invitedEmail);
        UUID joiningId =
                existing.map(UserAccount::id)
                        .orElseGet(() -> register(invitedEmail, displayName, rawPassword).id());

        if (members.existsByHouseholdIdAndUserId(householdId, joiningId)) {
            throw new ConflictException(
                    ErrorCode.INVITATION_REFUSED, "the address cannot be invited");
        }

        // Re-read both: claiming the invitation and registering the user are bulk statements that
        // clear the persistence context, so anything loaded before them is detached by now.
        Household household = households.findById(householdId).orElseThrow(() -> unusable(keys));
        UserAccount joining = users.findById(joiningId).orElseThrow(() -> unusable(keys));

        members.save(HouseholdMember.of(household, joining, role));
        rateLimiter.recordSuccess(keys);

        log.info(
                "invitation accepted householdId={} invitationId={} userId={} created={}",
                householdId,
                invitationId,
                joiningId,
                existing.isEmpty());
        return new AcceptedInvitation(household.id(), household.name(), role, existing.isEmpty());
    }

    /**
     * The one answer to every unusable link: expired, revoked, already accepted, never existed, and
     * lost the race to accept. Telling them apart tells whoever found a link in a chat backup what
     * happened to it, and "already accepted" in particular confirms that somebody joined.
     */
    private NotFoundException unusable(List<String> rateLimiterKeys) {
        rateLimiter.recordFailure(rateLimiterKeys);
        return new NotFoundException(ErrorCode.INVITATION_UNUSABLE, "invitation is not usable");
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
