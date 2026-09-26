package com.budgetowl.household.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.common.web.ClientAddress;
import com.budgetowl.household.service.AcceptedInvitation;
import com.budgetowl.household.service.CreatedInvitation;
import com.budgetowl.household.service.InvitationService;
import com.budgetowl.household.web.HouseholdRequests.AcceptInvitationRequest;
import com.budgetowl.household.web.HouseholdRequests.CreateInvitationRequest;
import com.budgetowl.household.web.HouseholdResponses.AcceptInvitationResponse;
import com.budgetowl.household.web.HouseholdResponses.InvitationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitations: creating a link, revoking one, and accepting one.
 *
 * <p>Acceptance is the fifth and last public route in the application, and it is public because it
 * has to be — the person accepting does not have an account yet. What authorizes them is the token
 * in the link, which is why the link is a credential and why this endpoint is rate-limited like
 * login.
 *
 * <p>The token arrives in the path. It is never logged, never written to an access log we keep, and
 * the {@code ApiExceptionHandler} never echoes a path variable into an error — a token in a log
 * line is a working invitation in a log line.
 */
@RestController
@Validated
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** {@code OWNER} only, enforced in the service. */
    @PostMapping("/api/households/current/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResponse create(
            TransportAuthentication caller, @Valid @RequestBody CreateInvitationRequest body) {
        CreatedInvitation created = invitations.create(caller.userId(), body.email(), body.role());
        return new InvitationResponse(
                created.id(),
                created.email(),
                created.role(),
                created.token(),
                "/join/" + created.token(),
                created.expiresAt());
    }

    /** {@code OWNER} only. Revoking is how a link shared with the wrong person is taken back. */
    @DeleteMapping("/api/households/current/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(TransportAuthentication caller, @PathVariable UUID id) {
        invitations.revoke(caller.userId(), id);
    }

    /**
     * Joins the household.
     *
     * <p>Public, and refused for a caller who is already signed in: "join as whoever happens to be
     * logged in" is how a forwarded link adds the wrong person to somebody's finances. Sign out
     * first.
     */
    @PostMapping("/api/invitations/{token}/accept")
    AcceptInvitationResponse accept(
            @PathVariable @Size(max = 256) String token,
            @Valid @RequestBody AcceptInvitationRequest body,
            HttpServletRequest request) {
        UUID currentUserId =
                TransportAuthentication.current().map(TransportAuthentication::userId).orElse(null);
        AcceptedInvitation accepted =
                invitations.accept(
                        token,
                        body.displayName(),
                        body.password(),
                        currentUserId,
                        ClientAddress.of(request));
        return new AcceptInvitationResponse(
                accepted.householdId(),
                accepted.householdName(),
                accepted.role(),
                accepted.userCreated());
    }
}
