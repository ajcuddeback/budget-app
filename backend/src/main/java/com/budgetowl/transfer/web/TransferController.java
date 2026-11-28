package com.budgetowl.transfer.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.transaction.web.TransactionResponses.TransactionResponse;
import com.budgetowl.transfer.service.NewTransfer;
import com.budgetowl.transfer.service.TransferChanges;
import com.budgetowl.transfer.service.TransferService;
import com.budgetowl.transfer.service.TransferView;
import com.budgetowl.transfer.web.TransferRequests.CreateTransferRequest;
import com.budgetowl.transfer.web.TransferRequests.SplitTransferRequest;
import com.budgetowl.transfer.web.TransferRequests.UpdateTransferRequest;
import com.budgetowl.transfer.web.TransferResponses.SplitResponse;
import com.budgetowl.transfer.web.TransferResponses.TransferResponse;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money moving between two of the household's own accounts.
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside {@link TransferService}. An id in a path is a lookup key — another household's is a {@code
 * 404}, never a {@code 403}.
 *
 * <p><b>A transfer is addressed by its group id</b>, not by either leg's transaction id: the pair
 * is the object, and naming one leg would make the other's id a second name for the same thing. The
 * legs remain visible in the ledger, where each account has to show its own movement, and {@code
 * TransactionService} refuses to edit or delete either of them on its own.
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<TransferResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateTransferRequest body) {
        TransferView created =
                transfers.create(
                        caller.userId(),
                        new NewTransfer(
                                body.fromAccountId(),
                                body.toAccountId(),
                                body.date(),
                                body.amount(),
                                body.amountReceived(),
                                body.note()));
        return ResponseEntity.created(URI.create("/api/transfers/" + created.id()))
                .body(TransferResponse.of(created));
    }

    @GetMapping("/{id}")
    TransferResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return TransferResponse.of(transfers.get(caller.userId(), id));
    }

    /** Rewrites both legs. The accounts are not changeable — see {@code TransferChanges}. */
    @PatchMapping("/{id}")
    TransferResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransferRequest body) {
        return TransferResponse.of(
                transfers.update(
                        caller.userId(),
                        id,
                        new TransferChanges(
                                body.date(),
                                body.amount(),
                                body.amountReceived(),
                                body.noteChange(),
                                body.version())));
    }

    /**
     * Turns the pair back into two ordinary rows, which come back with no category and no payee
     * (file-import.md rule 12). Answers with both rows, in the ledger's own shape.
     */
    @PostMapping("/{id}/split")
    SplitResponse split(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody SplitTransferRequest body) {
        return new SplitResponse(
                transfers.split(caller.userId(), id, body.version()).stream()
                        .map(TransactionResponse::of)
                        .toList());
    }

    /** Removes both legs, in one database transaction. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        transfers.delete(caller.userId(), id);
    }
}
