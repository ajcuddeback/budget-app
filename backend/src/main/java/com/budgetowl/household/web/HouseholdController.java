package com.budgetowl.household.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import com.budgetowl.household.service.CurrentHousehold;
import com.budgetowl.household.service.HouseholdService;
import com.budgetowl.household.web.HouseholdRequests.ChangeMemberRoleRequest;
import com.budgetowl.household.web.HouseholdRequests.UpdateHouseholdRequest;
import com.budgetowl.household.web.HouseholdRequests.UpdateOwnMembershipRequest;
import com.budgetowl.household.web.HouseholdResponses.HouseholdResponse;
import com.budgetowl.household.web.HouseholdResponses.MemberResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household and its members.
 *
 * <p>Everything is {@code /current}: an instance holds exactly one household (ADR-0026), so there
 * is no id in any of these paths and nothing to tamper with. The household is resolved from the
 * caller's verified membership in the service layer — the parameter an attacker would swap does not
 * exist.
 *
 * <p>No role is checked here. Not because it does not matter but because the service is where it is
 * enforced, and a check in two places is a check that will disagree with itself.
 */
@RestController
@RequestMapping("/api/households/current")
public class HouseholdController {

    private final HouseholdService households;

    public HouseholdController(HouseholdService households) {
        this.households = households;
    }

    @GetMapping
    HouseholdResponse current(TransportAuthentication caller) {
        return response(households.current(caller.userId()));
    }

    /** {@code OWNER} only, enforced in the service. */
    @PutMapping
    HouseholdResponse update(
            TransportAuthentication caller, @Valid @RequestBody UpdateHouseholdRequest body) {
        return response(households.rename(caller.userId(), body.name(), body.baseCurrency()));
    }

    @GetMapping("/members")
    PageResponse<MemberResponse> members(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<MemberResponse> all =
                households.members(caller.userId()).stream()
                        .map(HouseholdController::response)
                        .toList();
        return PageResponse.of(all, page, size);
    }

    /**
     * {@code OWNER} only, and <b>never on yourself</b> — not even for an owner. A rule with an
     * exception for the most privileged role is where a privilege-escalation path starts.
     */
    @PatchMapping("/members/{id}")
    MemberResponse changeRole(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeMemberRoleRequest body) {
        return response(households.changeRole(caller.userId(), id, body.role()));
    }

    /**
     * A member's own display currency and language. Self only, and it changes what they see rather
     * than what is recorded (ADR-0022, ADR-0023) — two people in one household seeing two
     * currencies over identical data is a normal case.
     */
    @PatchMapping("/members/me")
    MemberResponse updateOwnMembership(
            TransportAuthentication caller, @Valid @RequestBody UpdateOwnMembershipRequest body) {
        return response(
                households.updateOwnPreferences(
                        caller.userId(), body.displayCurrency(), body.locale()));
    }

    /**
     * Removing a member, or leaving. Access only — the household's financial data stays with the
     * household (ADR-0017), and the last owner cannot go at all.
     */
    @DeleteMapping("/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(TransportAuthentication caller, @PathVariable UUID id) {
        households.removeMember(caller.userId(), id);
    }

    private static HouseholdResponse response(CurrentHousehold household) {
        return new HouseholdResponse(
                household.householdId(),
                household.name(),
                household.baseCurrency(),
                household.role());
    }

    private static MemberResponse response(HouseholdMemberSummary member) {
        return new MemberResponse(
                member.id(),
                member.userId(),
                member.email(),
                member.displayName(),
                member.role(),
                member.joinedAt(),
                member.displayCurrency(),
                member.locale());
    }
}
