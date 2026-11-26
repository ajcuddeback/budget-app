package com.budgetowl.debt.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.debt.service.BalanceConfirmation;
import com.budgetowl.debt.service.DebtChanges;
import com.budgetowl.debt.service.DebtPaymentService;
import com.budgetowl.debt.service.DebtService;
import com.budgetowl.debt.service.NewDebt;
import com.budgetowl.debt.service.NewDebtPayment;
import com.budgetowl.debt.service.PaymentChanges;
import com.budgetowl.debt.web.DebtRequests.ConfirmBalanceRequest;
import com.budgetowl.debt.web.DebtRequests.CorrectPaymentRequest;
import com.budgetowl.debt.web.DebtRequests.CreateDebtRequest;
import com.budgetowl.debt.web.DebtRequests.LogPaymentRequest;
import com.budgetowl.debt.web.DebtRequests.PreviewPaymentRequest;
import com.budgetowl.debt.web.DebtRequests.UpdateDebtRequest;
import com.budgetowl.debt.web.DebtResponses.DebtDetailResponse;
import com.budgetowl.debt.web.DebtResponses.DebtsResponse;
import com.budgetowl.debt.web.DebtResponses.PaymentPageResponse;
import com.budgetowl.debt.web.DebtResponses.PaymentPreviewResponse;
import com.budgetowl.debt.web.DebtResponses.PaymentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's debts, their terms and their payments.
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside the services. An id in a path is a lookup key — another household's debt is a {@code 404},
 * never a {@code 403}, so there is no oracle to build.
 *
 * <p>The route takes the debt's <b>id</b> rather than a slug, although the canvas shows one ({@code
 * /debt-plan/visa-8823}): a slug would need a second unique key per household and would leak the
 * debt's name into browser history and server logs.
 *
 * <p>Archive and un-archive are sub-resources rather than a field on {@code PATCH}, as accounts do
 * it: retiring something is a different act from editing it. Confirming a balance is one too,
 * because it can write a correction row and reopen a settled debt.
 *
 * <p>Both credential transports reach every route with identical authorization (ADR-0018). Roles
 * are enforced in the service layer, never here.
 */
@RestController
@RequestMapping("/api/debts")
// Deliberately NOT @Validated — see DebtPlanController and ArchitectureTest, which refuses it.
// Spring MVC validates constrained handler parameters and @Valid bodies natively and answers 400;
// @Validated would swap that for an unhandled ConstraintViolationException and a 500.
public class DebtController {

    private final DebtService debts;
    private final DebtPaymentService payments;

    public DebtController(DebtService debts, DebtPaymentService payments) {
        this.debts = debts;
        this.payments = payments;
    }

    /**
     * Every debt, with its derived balance, its rung, what it is paying and when it clears.
     *
     * @param includeArchived default false: an archived debt disappears from the list and from the
     *     ladder, and each row carries {@code archived} so a client that asks for them can tell
     */
    @GetMapping
    DebtsResponse list(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return DebtsResponse.of(debts.list(caller.userId(), includeArchived));
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<DebtDetailResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateDebtRequest body) {
        DebtDetailResponse created =
                DebtDetailResponse.of(
                        debts.create(
                                caller.userId(),
                                new NewDebt(
                                        body.name(),
                                        body.kind(),
                                        body.servicer(),
                                        body.currency(),
                                        body.balance(),
                                        body.balanceDate(),
                                        body.apr(),
                                        body.minimumPayment(),
                                        body.originalBalance(),
                                        body.payment(),
                                        body.monthsLeft(),
                                        body.payoffMonth(),
                                        body.accountId(),
                                        body.billId())));
        return ResponseEntity.created(URI.create("/api/debts/" + created.debt().id()))
                .body(created);
    }

    /** The terms, each field flagged, with the progress, the projection and the chart series. */
    @GetMapping("/{id}")
    DebtDetailResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return DebtDetailResponse.of(debts.get(caller.userId(), id));
    }

    /**
     * The terms editor.
     *
     * <p>Carries the entered set <b>explicitly</b>: per solver field, a value to enter or an
     * explicit null to release it to the solver. An absent field is left exactly as it was.
     */
    @PatchMapping("/{id}")
    DebtDetailResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDebtRequest body) {
        return DebtDetailResponse.of(
                debts.update(
                        caller.userId(),
                        id,
                        new DebtChanges(
                                body.name(),
                                body.kind(),
                                body.servicerChange(),
                                body.minimumPayment(),
                                body.originalBalanceChange(),
                                body.aprChange(),
                                body.paymentChange(),
                                body.monthsLeftChange(),
                                body.payoffMonthChange(),
                                body.accountIdChange(),
                                body.billIdChange(),
                                body.customOrderChange(),
                                body.version())));
    }

    /** Takes the debt and its own payment history. No transactions, ever (ADR-0035). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        debts.delete(caller.userId(), id);
    }

    @PostMapping("/{id}/archive")
    DebtDetailResponse archive(TransportAuthentication caller, @PathVariable UUID id) {
        return DebtDetailResponse.of(debts.archive(caller.userId(), id));
    }

    @PostMapping("/{id}/unarchive")
    DebtDetailResponse unarchive(TransportAuthentication caller, @PathVariable UUID id) {
        return DebtDetailResponse.of(debts.unarchive(caller.userId(), id));
    }

    /**
     * Confirms or corrects the balance from a statement.
     *
     * <p>Writes the anchor, and a {@code STATEMENT_CORRECTION} row where the figure disagrees with
     * the arithmetic. The one write a settled debt accepts, because a correction is how a household
     * says the balance came back.
     */
    @PostMapping("/{id}/balance")
    DebtDetailResponse confirmBalance(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmBalanceRequest body) {
        return DebtDetailResponse.of(
                debts.confirmBalance(
                        caller.userId(),
                        id,
                        new BalanceConfirmation(body.balance(), body.balanceDate(), body.note())));
    }

    /**
     * The payment history, <b>paginated</b> (docs/guides/api-style.md).
     *
     * <p>A household with years of records has more payments than one response should carry, which
     * the feature doc's threat model names as a finding. Default size 50, maximum 200, and a
     * request over the maximum is clamped rather than rejected.
     */
    @GetMapping("/{id}/payments")
    PaymentPageResponse payments(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PaymentPageResponse.of(payments.list(caller.userId(), id, page, size));
    }

    /**
     * What logging a payment would record, without recording it.
     *
     * <p>Writes nothing. The log-a-payment sheet calls it so the household sees the split and the
     * resulting balance <b>before</b> committing — the server's own figures, so the browser holds
     * no second copy of the interest formula to drift from this one.
     *
     * <p><b>A {@code POST} with a body, although it writes nothing.</b> It began as a {@code GET}
     * with {@code ?amount=}, and a URL is the one part of a request every layer logs: a reverse
     * proxy's access log by default, and Spring's own {@code DispatcherServlet} line at {@code
     * DEBUG}, which masks the decoded parameters and prints the raw query string anyway. It is also
     * why it needs write access rather than membership: this repository's convention is that a
     * non-{@code GET} is a write a {@code VIEWER} is refused, and the preview exists only to serve
     * the log-a-payment sheet, which a {@code VIEWER} never sees. Least privilege costs nobody
     * anything here.
     */
    @PostMapping("/{id}/payments/preview")
    PaymentPreviewResponse previewPayment(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody PreviewPaymentRequest body) {
        return PaymentPreviewResponse.of(
                payments.preview(caller.userId(), id, body.amount(), body.paidDate()));
    }

    /**
     * Logs a payment.
     *
     * <p><b>Writes no transaction and moves no account balance</b> (ADR-0035). An optional {@code
     * transactionId} names the real movement the household already recorded.
     */
    @PostMapping("/{id}/payments")
    ResponseEntity<PaymentResponse> logPayment(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody LogPaymentRequest body) {
        PaymentResponse logged =
                PaymentResponse.of(
                        payments.log(
                                caller.userId(),
                                id,
                                new NewDebtPayment(
                                        body.paidDate(),
                                        body.amount(),
                                        body.transactionId(),
                                        body.billOccurrenceId(),
                                        body.paidFromAccountId(),
                                        body.note())));
        return ResponseEntity.created(URI.create("/api/debts/" + id + "/payments/" + logged.id()))
                .body(logged);
    }

    /**
     * Corrects a payment. The interest that was true on the day is kept — see {@code
     * PaymentChanges}.
     */
    @PatchMapping("/{id}/payments/{paymentId}")
    PaymentResponse correctPayment(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable UUID paymentId,
            @Valid @RequestBody CorrectPaymentRequest body) {
        return PaymentResponse.of(
                payments.correct(
                        caller.userId(),
                        id,
                        paymentId,
                        new PaymentChanges(
                                body.paidDate(),
                                body.amount(),
                                body.transactionIdChange(),
                                body.paidFromAccountIdChange(),
                                body.noteChange(),
                                body.version())));
    }

    /** Removes a payment. The balance moves back; nothing in the ledger is touched. */
    @DeleteMapping("/{id}/payments/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePayment(
            TransportAuthentication caller, @PathVariable UUID id, @PathVariable UUID paymentId) {
        payments.delete(caller.userId(), id, paymentId);
    }
}
