package com.budgetowl.household.service;

import com.budgetowl.auth.service.DeviceService;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.household.persistence.HouseholdMemberRepository;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The household and its members.
 *
 * <p>Every method starts by resolving the caller's membership and checking their role ({@link
 * MembershipService}), because that is where authorization lives — not in an annotation on a
 * controller, and never from an id in the request.
 *
 * <p>Two rules here are worth stating because they are easy to write around and expensive to get
 * wrong:
 *
 * <ul>
 *   <li><b>Nobody may change their own role, including an {@code OWNER}.</b> Not "an owner may not
 *       demote themselves" — nobody, at all. A rule with an exception for the most privileged role
 *       is the rule a privilege-escalation path is built out of.
 *   <li><b>The last-owner rule belongs to the database.</b> The check below exists to give a clear
 *       message before the attempt; it is not the control. Two owners removing each other
 *       concurrently both see one owner remaining under {@code READ COMMITTED} — that is write
 *       skew, and no amount of care in this class fixes it. {@code
 *       ck_households_at_least_one_owner} fires at {@code COMMIT}, outside every try/catch in here,
 *       and is handled by the global error handler.
 * </ul>
 */
@Service
public class HouseholdService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdService.class);

    private final HouseholdMemberRepository members;
    private final MembershipService memberships;
    private final DeviceService devices;

    public HouseholdService(
            HouseholdMemberRepository members,
            MembershipService memberships,
            DeviceService devices) {
        this.members = members;
        this.memberships = memberships;
        this.devices = devices;
    }

    /** Empty for a signed-in user with no membership — an empty state, not an error. */
    @Transactional(readOnly = true)
    public Optional<CurrentHousehold> findCurrent(UUID userId) {
        return memberships.find(userId).map(HouseholdService::view);
    }

    @Transactional(readOnly = true)
    public CurrentHousehold current(UUID userId) {
        return view(memberships.requireMembership(userId));
    }

    @Transactional
    public CurrentHousehold rename(UUID actorId, String name, String baseCurrency) {
        HouseholdMember membership = memberships.requireOwnership(actorId);
        Household household = membership.household();
        household.rename(name.strip());
        household.changeBaseCurrency(currency(baseCurrency, "baseCurrency"));
        log.info("household updated householdId={} byUserId={}", household.id(), actorId);
        return view(membership);
    }

    @Transactional(readOnly = true)
    public List<HouseholdMemberSummary> members(UUID actorId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        return members.findSummariesByHouseholdId(membership.household().id());
    }

    /**
     * @throws ForbiddenException if the caller is not an {@code OWNER}, or is the member being
     *     changed
     */
    @Transactional
    public HouseholdMemberSummary changeRole(UUID actorId, UUID memberId, HouseholdRole newRole) {
        HouseholdMember actor = memberships.requireOwnership(actorId);
        HouseholdMember target = require(memberId, actor.household().id());

        if (target.user().id().equals(actorId)) {
            throw new ForbiddenException(
                    ErrorCode.OWN_ROLE_UNCHANGEABLE, "a member may not change their own role");
        }
        // No last-owner pre-check here, deliberately. Demoting the last owner can only ever be
        // self-demotion, which the rule above already refuses — and if that ever stops being true,
        // ck_households_at_least_one_owner still fires at COMMIT and the error handler still
        // answers 409 last-owner. A guard that cannot be reached is a guard nobody can trust.

        target.changeRoleTo(newRole);
        log.info(
                "member role changed householdId={} memberId={} byUserId={} role={}",
                actor.household().id(),
                memberId,
                actorId,
                newRole);
        return summarise(target);
    }

    /**
     * A member's own display currency and language, which change what they see and never what is
     * recorded (ADR-0022, ADR-0023). Self only — there is no route to another member's preferences,
     * and no role may set them.
     *
     * <p>{@code null} means "follow the household", which is a meaning rather than an omission.
     */
    @Transactional
    public HouseholdMemberSummary updateOwnPreferences(
            UUID actorId, String displayCurrency, String locale) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        membership.preferDisplayCurrency(
                displayCurrency == null ? null : currency(displayCurrency, "displayCurrency"));
        membership.preferLocale(locale == null ? null : locale(locale));
        return summarise(membership);
    }

    /**
     * Removes a member, or leaves.
     *
     * <p>Access only. The household's financial data belongs to the household rather than to the
     * person (ADR-0017), so nothing they recorded is deleted and nothing is reassigned. Their
     * sessions and tokens are revoked in the same breath, because a removal that leaves a live
     * mobile token has not removed anybody.
     */
    @Transactional
    public void removeMember(UUID actorId, UUID memberId) {
        HouseholdMember actor = memberships.requireMembership(actorId);
        HouseholdMember target = require(memberId, actor.household().id());
        boolean leaving = target.user().id().equals(actorId);
        if (!leaving && !actor.role().isOwner()) {
            throw new ForbiddenException(ErrorCode.OWNER_ONLY, "only an owner may remove a member");
        }
        if (target.role().isOwner() && onlyOwner(actor.household().id())) {
            throw new ConflictException(
                    ErrorCode.LAST_OWNER,
                    leaving ? "the last owner may not leave" : "the last owner may not be removed");
        }

        UUID removedUserId = target.user().id();
        members.delete(target);
        devices.revokeEverythingFor(removedUserId);
        log.info(
                "membership removed householdId={} memberId={} byUserId={} leaving={}",
                actor.household().id(),
                memberId,
                actorId,
                leaving);
    }

    private HouseholdMember require(UUID memberId, UUID householdId) {
        return members.findByIdAndHouseholdId(memberId, householdId)
                .orElseThrow(() -> new NotFoundException("no such member"));
    }

    private boolean onlyOwner(UUID householdId) {
        return members.countByHouseholdIdAndRole(householdId, HouseholdRole.OWNER) <= 1;
    }

    private static CurrentHousehold view(HouseholdMember membership) {
        Household household = membership.household();
        return new CurrentHousehold(
                household.id(),
                household.name(),
                household.baseCurrency().getCurrencyCode(),
                membership.role(),
                membership.id(),
                membership.displayCurrency().map(Currency::getCurrencyCode).orElse(null),
                membership.locale().map(Locale::toLanguageTag).orElse(null));
    }

    private static HouseholdMemberSummary summarise(HouseholdMember member) {
        return new HouseholdMemberSummary(
                member.id(),
                member.household().id(),
                member.user().id(),
                member.user().email(),
                member.user().displayName(),
                member.role(),
                member.joinedAt(),
                member.displayCurrency().map(Currency::getCurrencyCode).orElse(null),
                member.locale().map(Locale::toLanguageTag).orElse(null));
    }

    private static Currency currency(String code, String field) {
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "unknown currency for " + field,
                    Map.of("field", field));
        }
    }

    private static Locale locale(String languageTag) {
        Locale parsed = Locale.forLanguageTag(languageTag.strip());
        if (parsed.getLanguage().isEmpty()) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unparseable locale", Map.of("field", "locale"));
        }
        return parsed;
    }
}
