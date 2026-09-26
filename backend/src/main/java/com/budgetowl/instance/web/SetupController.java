package com.budgetowl.instance.web;

import com.budgetowl.instance.service.FirstUserCreated;
import com.budgetowl.instance.service.SetupService;
import com.budgetowl.instance.service.SetupStatus;
import com.budgetowl.instance.web.SetupRequests.CreateFirstUserRequest;
import com.budgetowl.instance.web.SetupResponses.FirstUserResponse;
import com.budgetowl.instance.web.SetupResponses.SetupStatusResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * First run.
 *
 * <p><b>Two of the five public routes in the entire application</b> (the others being login, token
 * issue and invitation acceptance). Being public is an explicit, reviewed exception to
 * non-negotiable #2, and both are listed by name in {@code SecurityConfig} — adding a third is a
 * security change.
 *
 * <p>Neither endpoint decides anything. "Is this instance still fresh?" is not answered by reading
 * a count and trusting it, because two callers can read the same count; it is claimed in the
 * service's transaction. This controller validates input and returns what the service decided.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/status")
    SetupStatusResponse status() {
        SetupStatus status = setup.status();
        return new SetupStatusResponse(
                status.setupComplete(),
                status.registrationOpen(),
                status.passwordLoginEnabled(),
                status.oidcEnabled());
    }

    @PostMapping("/first-user")
    ResponseEntity<FirstUserResponse> createFirstUser(
            @Valid @RequestBody CreateFirstUserRequest request) {
        FirstUserCreated created =
                setup.createFirstUser(
                        request.email(),
                        request.displayName(),
                        request.password(),
                        request.householdName(),
                        request.baseCurrency());
        return ResponseEntity.created(URI.create("/api/households/current"))
                .body(
                        new FirstUserResponse(
                                created.userId(),
                                created.email(),
                                created.displayName(),
                                created.householdId(),
                                created.householdName(),
                                created.baseCurrency()));
    }
}
