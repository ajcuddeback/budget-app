package com.budgetowl.envelope.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.envelope.service.EntryChanges;
import com.budgetowl.envelope.service.EnvelopeChanges;
import com.budgetowl.envelope.service.EnvelopeEntryPage;
import com.budgetowl.envelope.service.EnvelopeEntryView;
import com.budgetowl.envelope.service.EnvelopePage;
import com.budgetowl.envelope.service.EnvelopeService;
import com.budgetowl.envelope.service.EnvelopeView;
import com.budgetowl.envelope.service.NewEntry;
import com.budgetowl.envelope.service.NewEnvelope;
import com.budgetowl.envelope.web.EnvelopeRequests.CreateEntryRequest;
import com.budgetowl.envelope.web.EnvelopeRequests.CreateEnvelopeRequest;
import com.budgetowl.envelope.web.EnvelopeRequests.UpdateEntryRequest;
import com.budgetowl.envelope.web.EnvelopeRequests.UpdateEnvelopeRequest;
import com.budgetowl.envelope.web.EnvelopeResponses.EntryResponse;
import com.budgetowl.envelope.web.EnvelopeResponses.EnvelopePageResponse;
import com.budgetowl.envelope.web.EnvelopeResponses.EnvelopeResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.YearMonth;
import java.util.List;
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
 * The household's envelopes, and their logs.
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside {@link EnvelopeService}. An id in a path is a lookup key — another household's is a {@code
 * 404}, never a {@code 403}.
 *
 * <p>An entry's path names its envelope as well as the entry. That is not decoration: an entry of
 * the right household but the wrong envelope would otherwise be editable through a URL naming an
 * envelope it is not in, and the response would show it moving between remaining figures.
 */
@RestController
@RequestMapping("/api/envelopes")
public class EnvelopeController {

    private final EnvelopeService envelopes;

    public EnvelopeController(EnvelopeService envelopes) {
        this.envelopes = envelopes;
    }

    /**
     * One month's envelopes, each with its remaining figure.
     *
     * @param period the month, as {@code 2026-08}. Required: an envelope without a period is not a
     *     thing, and defaulting to "now" on the server would make the answer depend on the server's
     *     clock rather than on the person's
     */
    @GetMapping
    EnvelopePageResponse list(
            TransportAuthentication caller,
            @RequestParam YearMonth period,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        EnvelopePage found = envelopes.list(caller.userId(), period, includeArchived, page, size);
        return new EnvelopePageResponse(
                found.envelopes().stream().map(EnvelopeController::response).toList(),
                found.totalAllocated().amount(),
                found.totalSpent().amount(),
                found.totalRemaining().amount(),
                found.totalAllocated().currency().getCurrencyCode(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /** The months this household has envelopes for, newest first — for a period picker. */
    @GetMapping("/periods")
    List<String> periods(TransportAuthentication caller) {
        return envelopes.periods(caller.userId()).stream().map(YearMonth::toString).toList();
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<EnvelopeResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateEnvelopeRequest body) {
        EnvelopeView created =
                envelopes.create(
                        caller.userId(),
                        new NewEnvelope(
                                body.name(), body.period(), body.amount(), body.categoryId()));
        return ResponseEntity.created(URI.create("/api/envelopes/" + created.id()))
                .body(response(created));
    }

    @GetMapping("/{id}")
    EnvelopeResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return response(envelopes.get(caller.userId(), id));
    }

    @PatchMapping("/{id}")
    EnvelopeResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEnvelopeRequest body) {
        return response(
                envelopes.update(
                        caller.userId(),
                        id,
                        new EnvelopeChanges(
                                body.name(),
                                body.amount(),
                                body.categoryIdChange(),
                                body.version())));
    }

    @PostMapping("/{id}/archive")
    EnvelopeResponse archive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(envelopes.archive(caller.userId(), id));
    }

    @PostMapping("/{id}/unarchive")
    EnvelopeResponse unarchive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(envelopes.unarchive(caller.userId(), id));
    }

    /** Deletes the envelope and its log. No transaction is deleted; attached ones are detached. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        envelopes.delete(caller.userId(), id);
    }

    // --------------------------------------------------------------------------------- entries

    @GetMapping("/{id}/entries")
    PageResponse<EntryResponse> log(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        EnvelopeEntryPage found = envelopes.log(caller.userId(), id, page, size);
        return new PageResponse<>(
                found.entries().stream().map(EnvelopeController::response).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /**
     * Logs spend — the action somebody takes eighty times a month.
     *
     * <p>An amount alone is enough. Supply {@code transactionId} instead to attach a transaction
     * already recorded, in which case the figure comes from it.
     */
    @PostMapping("/{id}/entries")
    ResponseEntity<EntryResponse> logEntry(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody CreateEntryRequest body) {
        EnvelopeEntryView created =
                envelopes.logEntry(
                        caller.userId(),
                        id,
                        new NewEntry(
                                body.amount(),
                                body.transactionId(),
                                body.date(),
                                body.payeeName(),
                                body.note()));
        return ResponseEntity.created(
                        URI.create("/api/envelopes/" + id + "/entries/" + created.id()))
                .body(response(created));
    }

    @PatchMapping("/{id}/entries/{entryId}")
    EntryResponse updateEntry(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateEntryRequest body) {
        return response(
                envelopes.updateEntry(
                        caller.userId(),
                        id,
                        entryId,
                        new EntryChanges(
                                body.date(),
                                body.amount(),
                                body.payeeNameChange(),
                                body.noteChange(),
                                body.version())));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteEntry(
            TransportAuthentication caller, @PathVariable UUID id, @PathVariable UUID entryId) {
        envelopes.deleteEntry(caller.userId(), id, entryId);
    }

    private static EnvelopeResponse response(EnvelopeView envelope) {
        return new EnvelopeResponse(
                envelope.id(),
                envelope.name(),
                envelope.period().toString(),
                envelope.categoryId(),
                envelope.categoryName(),
                envelope.categoryParentName(),
                envelope.amount().amount(),
                envelope.spent().amount(),
                envelope.remaining().amount(),
                envelope.amount().currency().getCurrencyCode(),
                envelope.entryCount(),
                envelope.empty(),
                envelope.archived(),
                envelope.createdAt(),
                envelope.updatedAt(),
                envelope.version());
    }

    private static EntryResponse response(EnvelopeEntryView entry) {
        return new EntryResponse(
                entry.id(),
                entry.envelopeId(),
                entry.date(),
                entry.amount().amount(),
                entry.amount().currency().getCurrencyCode(),
                entry.payeeId(),
                entry.payeeName(),
                entry.note(),
                entry.source(),
                entry.transactionId(),
                entry.createdAt(),
                entry.updatedAt(),
                entry.version());
    }
}
