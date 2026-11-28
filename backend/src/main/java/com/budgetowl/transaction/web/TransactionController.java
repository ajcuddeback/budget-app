package com.budgetowl.transaction.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.transaction.service.LedgerFilter;
import com.budgetowl.transaction.service.NewTransaction;
import com.budgetowl.transaction.service.TransactionChanges;
import com.budgetowl.transaction.service.TransactionPage;
import com.budgetowl.transaction.service.TransactionService;
import com.budgetowl.transaction.service.TransactionView;
import com.budgetowl.transaction.web.TransactionRequests.CreateTransactionRequest;
import com.budgetowl.transaction.web.TransactionRequests.UpdateTransactionRequest;
import com.budgetowl.transaction.web.TransactionResponses.LedgerResponse;
import com.budgetowl.transaction.web.TransactionResponses.TransactionResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * The household's transactions.
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside {@link TransactionService}. An {@code id} in a path is a lookup key — another household's
 * transaction is a {@code 404}, never a {@code 403}.
 *
 * <p><b>Filters are explicit typed query parameters</b>, never a generic query language in the URL
 * (docs/guides/api-style.md). Each is optional and each is a bound parameter in one query, not a
 * fragment of assembled SQL.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactions;

    public TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    /**
     * The ledger, newest first.
     *
     * @param accountId one account's register; omitted for every account
     * @param categoryId one category; omitted for every category. Cannot express "uncategorised" —
     *     use {@code uncategorised=true}, because an omitted category already means "any"
     * @param uncategorised only transactions with no category: the work queue a person acts on
     * @param from inclusive lower bound on the date; {@code to} is the inclusive upper bound
     * @param search matched case-insensitively against the note and the payee's name
     */
    @GetMapping
    LedgerResponse list(
            TransportAuthentication caller,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean uncategorised,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        TransactionPage found =
                transactions.list(
                        caller.userId(),
                        new LedgerFilter(accountId, categoryId, uncategorised, from, to, search),
                        page,
                        size);

        return new LedgerResponse(
                found.transactions().stream().map(TransactionController::response).toList(),
                found.pageTotal() == null ? null : found.pageTotal().amount(),
                found.pageTotal() == null ? null : found.pageTotal().currency().getCurrencyCode(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<TransactionResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateTransactionRequest body) {
        TransactionView created =
                transactions.create(
                        caller.userId(),
                        new NewTransaction(
                                body.accountId(),
                                body.date(),
                                body.amount(),
                                body.payeeName(),
                                body.categoryId(),
                                body.note(),
                                body.status()));
        return ResponseEntity.created(URI.create("/api/transactions/" + created.id()))
                .body(response(created));
    }

    @GetMapping("/{id}")
    TransactionResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return response(transactions.get(caller.userId(), id));
    }

    /**
     * A partial update. An absent field is left alone; an explicit {@code null} on {@code
     * payeeName}, {@code categoryId} or {@code note} clears it. {@code version} says which version
     * the caller read, and a stale one is {@code 409 stale-version}.
     */
    @PatchMapping("/{id}")
    TransactionResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionRequest body) {
        return response(
                transactions.update(
                        caller.userId(),
                        id,
                        new TransactionChanges(
                                body.date(),
                                body.amount(),
                                body.payeeNameChange(),
                                body.categoryIdChange(),
                                body.noteChange(),
                                body.status(),
                                body.version())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        transactions.delete(caller.userId(), id);
    }

    /**
     * Payee names beginning with what has been typed, for the entry form's autocomplete.
     *
     * <p>Names only, and at most ten. The client needs a string to put in the field — the payee's
     * id is resolved server-side when the transaction is saved — and returning the household's
     * whole payee list to a form would leak more than the question asked for.
     */
    @GetMapping("/payee-suggestions")
    List<String> suggestPayees(
            TransportAuthentication caller, @RequestParam(defaultValue = "") String typed) {
        return transactions.suggestPayees(caller.userId(), typed);
    }

    private static TransactionResponse response(TransactionView transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.accountId(),
                transaction.accountName(),
                transaction.date(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.payeeId(),
                transaction.payeeName(),
                transaction.categoryId(),
                transaction.categoryName(),
                transaction.categoryParentName(),
                transaction.note(),
                transaction.status(),
                transaction.transferGroupId(),
                transaction.transfer(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.version());
    }
}
