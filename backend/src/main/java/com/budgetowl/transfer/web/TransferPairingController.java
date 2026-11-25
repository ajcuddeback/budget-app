package com.budgetowl.transfer.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.transfer.service.TransferService;
import com.budgetowl.transfer.service.TransferService.PairRequest;
import com.budgetowl.transfer.web.TransferRequests.PairTransactionRequest;
import com.budgetowl.transfer.web.TransferResponses.TransferResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "These two rows are one transfer": {@code POST /api/transactions/{id}/pair}
 * (docs/features/file-import.md rule 12).
 *
 * <p>A non-CRUD action on a transaction, so a sub-resource of it (api-style.md). It lives with
 * transfers because what it makes is a transfer, and answers in the {@code GET /api/transfers/{id}}
 * shape. Both ids are lookup keys resolved against the caller's household; the role is checked
 * before either is looked up.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransferPairingController {

    private final TransferService transfers;

    public TransferPairingController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/{id}/pair")
    TransferResponse pair(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody PairTransactionRequest body) {
        return TransferResponse.of(
                transfers.pair(
                        caller.userId(),
                        id,
                        new PairRequest(
                                body.withTransactionId(), body.version(), body.withVersion())));
    }
}
