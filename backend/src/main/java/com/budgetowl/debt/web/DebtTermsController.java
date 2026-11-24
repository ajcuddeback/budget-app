package com.budgetowl.debt.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.debt.service.DebtTermsService;
import com.budgetowl.debt.service.TermsToSolve;
import com.budgetowl.debt.web.DebtRequests.SolveTermsRequest;
import com.budgetowl.debt.web.DebtResponses.SolvedTermsResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solving a term set without saving it — for the terms editor and the add form.
 *
 * <p><b>A pure calculation.</b> It reads no stored data: no debt, no plan, no household's figures.
 * It discloses nothing because it knows nothing, which is why it is the one route in this slice
 * with no id in it and nothing to be another household's.
 *
 * <p>It is nevertheless <b>authenticated and rate-limited</b>. The reason is cost rather than
 * confidentiality: this is the first endpoint in the app whose work depends on the numbers in the
 * request — the solver's 600-month and 64-step caps bound one call, and {@code
 * TermsSolveRateLimiter} bounds a loop of them. A caller over the limit gets {@code 429} with a
 * finite {@code Retry-After}.
 *
 * <p>{@code POST} rather than {@code GET} despite reading nothing: the term set is a body with
 * amounts in it, and amounts do not belong in a URL where they would reach access logs and browser
 * history. It is not idempotent in the HTTP sense anyway, because it is rate-limited.
 */
@RestController
@RequestMapping("/api/debt-terms")
public class DebtTermsController {

    private final DebtTermsService terms;

    public DebtTermsController(DebtTermsService terms) {
        this.terms = terms;
    }

    /**
     * Fills in whichever of {@code apr}, {@code payment} and the horizon was left out.
     *
     * <p>All three is {@code 422 terms-over-determined} naming the fields; fewer than two is a
     * {@code 400}; a set with no answer is {@code 422 terms-unsolvable} naming the field at fault.
     * A payment that never clears the balance is a {@code 200} with {@code never-clears} beside a
     * null horizon — accepted, because a card's real minimum is a percentage of the balance and a
     * household entering last month's flat figure is not making a mistake.
     */
    @PostMapping("/solve")
    SolvedTermsResponse solve(
            TransportAuthentication caller, @Valid @RequestBody SolveTermsRequest body) {
        return SolvedTermsResponse.of(
                terms.solve(
                        caller.userId(),
                        new TermsToSolve(
                                body.balance(),
                                body.currency(),
                                body.apr(),
                                body.payment(),
                                body.monthsLeft(),
                                body.payoffMonth(),
                                body.firstPaymentMonth())));
    }
}
