package com.budgetowl.imports.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.imports.service.ImportMappingService;
import com.budgetowl.imports.service.ImportMappingService.MappingChanges;
import com.budgetowl.imports.web.ImportRequests.SaveMappingRequest;
import com.budgetowl.imports.web.ImportResponses.MappingResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An account's saved CSV column mapping: settings and the bank's labels, never a cell value.
 *
 * <p>A sub-resource of the account it belongs to, because there is at most one per account and it
 * means nothing without it. The account id in the path is a lookup key: another household's is a
 * {@code 404}.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/import-mapping")
public class ImportMappingController {

    private final ImportMappingService mappings;

    public ImportMappingController(ImportMappingService mappings) {
        this.mappings = mappings;
    }

    /** {@code 404} when the account has none. Any member. */
    @GetMapping
    MappingResponse get(TransportAuthentication caller, @PathVariable UUID accountId) {
        return MappingResponse.of(mappings.get(caller.userId(), accountId));
    }

    /**
     * Save or replace. Replacing needs the {@code version} read. {@code OWNER} or {@code MEMBER}.
     */
    @PutMapping
    MappingResponse put(
            TransportAuthentication caller,
            @PathVariable UUID accountId,
            @Valid @RequestBody SaveMappingRequest body) {
        return MappingResponse.of(
                mappings.put(
                        caller.userId(),
                        accountId,
                        new MappingChanges(body.settings(), body.headerLabels(), body.version())));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID accountId) {
        mappings.delete(caller.userId(), accountId);
    }
}
