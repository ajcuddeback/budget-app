package com.budgetowl.imports.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.imports.service.ImportBatchView;
import com.budgetowl.imports.service.ImportHistoryService;
import com.budgetowl.imports.service.ImportService;
import com.budgetowl.imports.service.ImportService.CommitChoices;
import com.budgetowl.imports.service.ImportService.ImportRequest;
import com.budgetowl.imports.service.ImportSettings;
import com.budgetowl.imports.web.ImportRequests.CommitImportRequest;
import com.budgetowl.imports.web.ImportRequests.ImportOptionsRequest;
import com.budgetowl.imports.web.ImportRequests.OverrideRequest;
import com.budgetowl.imports.web.ImportRequests.PreviewImportRequest;
import com.budgetowl.imports.web.ImportRequests.SuspectedChoice;
import com.budgetowl.imports.web.ImportResponses.BatchResponse;
import com.budgetowl.imports.web.ImportResponses.HistoryResponse;
import com.budgetowl.imports.web.ImportResponses.PreviewResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * File import: preview, commit, history and undo (docs/features/file-import.md).
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership in
 * the services, which also check the role — first, before the account is looked up and before the
 * file is decoded. Both credential transports reach every route with identical authorization
 * (ADR-0018); CSRF applies to the cookie transport, in the security configuration.
 *
 * <p>Deliberately <b>not</b> {@code @Validated} — {@code ArchitectureTest} refuses it, and Spring
 * MVC already validates constrained parameters and {@code @Valid} bodies with a {@code 400}.
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService imports;
    private final ImportHistoryService history;

    public ImportController(ImportService imports, ImportHistoryService history) {
        this.imports = imports;
        this.history = history;
    }

    /** Parse and classify. <b>Writes nothing.</b> {@code OWNER} or {@code MEMBER}. */
    @PostMapping("/preview")
    PreviewResponse preview(
            TransportAuthentication caller, @Valid @RequestBody PreviewImportRequest body) {
        return PreviewResponse.of(
                imports.preview(
                        caller.userId(),
                        new ImportRequest(
                                body.accountId(),
                                body.format(),
                                body.file(),
                                settings(body.options()))));
    }

    /**
     * Re-parse, verify the digest, write atomically. {@code 201} with a {@code Location}; {@code
     * 200} with the existing batch when this is the retry of a commit that already succeeded.
     */
    @PostMapping
    ResponseEntity<BatchResponse> commit(
            TransportAuthentication caller, @Valid @RequestBody CommitImportRequest body) {
        Map<Integer, Boolean> overrides = new HashMap<>();
        if (body.overrides() != null) {
            for (OverrideRequest override : body.overrides()) {
                overrides.put(override.row(), override.include());
            }
        }
        ImportBatchView batch =
                imports.commit(
                        caller.userId(),
                        new ImportRequest(
                                body.accountId(),
                                body.format(),
                                body.file(),
                                settings(body.options())),
                        new CommitChoices(
                                body.previewDigest(),
                                body.suspectedDuplicates() == SuspectedChoice.INCLUDE,
                                overrides));
        if (!batch.created()) {
            return ResponseEntity.ok(BatchResponse.of(batch));
        }
        return ResponseEntity.created(URI.create("/api/imports/" + batch.id()))
                .body(BatchResponse.of(batch));
    }

    /** Newest first, paginated. Any member. */
    @GetMapping
    HistoryResponse list(
            TransportAuthentication caller,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return HistoryResponse.of(history.list(caller.userId(), accountId, page, size));
    }

    @GetMapping("/{id}")
    BatchResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return BatchResponse.of(history.get(caller.userId(), id));
    }

    /** Undo (decision 5): the batch's rows that are not transfer legs, in one transaction. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void undo(TransportAuthentication caller, @PathVariable UUID id) {
        history.undo(caller.userId(), id);
    }

    private static ImportSettings settings(ImportOptionsRequest options) {
        return options == null ? null : options.settings();
    }
}
