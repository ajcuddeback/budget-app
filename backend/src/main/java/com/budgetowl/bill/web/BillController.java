package com.budgetowl.bill.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.bill.service.BillChanges;
import com.budgetowl.bill.service.BillService;
import com.budgetowl.bill.service.BillView;
import com.budgetowl.bill.service.NewBill;
import com.budgetowl.bill.service.StatusRecord;
import com.budgetowl.bill.web.BillRequests.CreateBillRequest;
import com.budgetowl.bill.web.BillRequests.RecordStatusRequest;
import com.budgetowl.bill.web.BillRequests.UpdateBillRequest;
import com.budgetowl.bill.web.BillResponses.BillResponse;
import com.budgetowl.bill.web.BillResponses.BillsResponse;
import com.budgetowl.bill.web.BillResponses.OccurrenceResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's bills, and what it has recorded about them.
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside {@link BillService}. An id in a path is a lookup key — another household's is a {@code
 * 404}, never a {@code 403}.
 *
 * <p>An occurrence is addressed by its <b>due date</b>, not by an id: for most of its life it has
 * no row (ADR-0032). {@code PUT} rather than {@code POST}, because the URL is valid before anything
 * exists at it and recording the same status twice is the same request twice.
 *
 * <p>Both the month and the due date are bound by Spring as a {@code YearMonth} and a {@code
 * LocalDate}. A malformed one is a {@code 400} before any of this runs, and neither ever reaches a
 * query as text.
 */
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillService bills;

    public BillController(BillService bills) {
        this.bills = bills;
    }

    /**
     * One month's bills, each with the occurrences its schedule implies.
     *
     * @param period the month, as {@code 2026-08}. Required: defaulting to "now" on the server
     *     would make the answer depend on the server's clock rather than on the person's
     */
    @GetMapping
    BillsResponse list(
            TransportAuthentication caller,
            @RequestParam YearMonth period,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return BillsResponse.of(bills.list(caller.userId(), period, includeInactive));
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<BillResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateBillRequest body) {
        BillView created =
                bills.create(
                        caller.userId(),
                        new NewBill(
                                body.name(),
                                body.expectedAmount(),
                                body.schedule().toRecurrence(),
                                body.categoryId(),
                                body.varies(),
                                body.tolerance()));
        return ResponseEntity.created(URI.create("/api/bills/" + created.id()))
                .body(BillResponse.of(created));
    }

    @GetMapping("/{id}")
    BillResponse one(
            TransportAuthentication caller, @PathVariable UUID id, @RequestParam YearMonth period) {
        return BillResponse.of(bills.get(caller.userId(), id, period));
    }

    @PatchMapping("/{id}")
    BillResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBillRequest body) {
        return BillResponse.of(
                bills.update(
                        caller.userId(),
                        id,
                        new BillChanges(
                                body.name(),
                                body.expectedAmount(),
                                body.schedule() == null ? null : body.schedule().toRecurrence(),
                                body.categoryIdChange(),
                                body.amountVaries(),
                                body.tolerance(),
                                body.active(),
                                body.version())));
    }

    /**
     * Deletes the bill and everything recorded about it. No transaction is deleted.
     *
     * <p>Retiring it — {@code PATCH} with {@code active: false} — is the usual answer and keeps the
     * record of the periods it was paid in.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        bills.delete(caller.userId(), id);
    }

    // ----------------------------------------------------------------------------- occurrences

    /**
     * Records a status against one occurrence — the one-tap action on the page.
     *
     * <p>Idempotent, and it creates the row if there is not one. A date the bill's schedule does
     * not imply is a {@code 404}: the URL names an occurrence that does not and cannot exist. An
     * {@code IRREGULAR} bill is the exception — it projects nothing, so recording against a date is
     * what brings the occurrence into being.
     */
    @PutMapping("/{id}/occurrences/{dueDate}")
    OccurrenceResponse record(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable LocalDate dueDate,
            @Valid @RequestBody RecordStatusRequest body) {
        return OccurrenceResponse.of(
                bills.record(
                        caller.userId(),
                        id,
                        dueDate,
                        new StatusRecord(body.status(), body.actualAmount(), body.paidDate())));
    }

    /**
     * Forgets what was recorded, so the date reads as its projection again.
     *
     * <p>Not the same as recording {@code DUE}: that is an assertion and this is the absence of
     * one. A household that ticked the wrong bill off wants the record gone.
     */
    @DeleteMapping("/{id}/occurrences/{dueDate}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable LocalDate dueDate) {
        bills.forget(caller.userId(), id, dueDate);
    }
}
