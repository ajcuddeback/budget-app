package com.budgetowl.imports.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.imports.domain.ImportBatch;
import com.budgetowl.imports.persistence.ImportBatchRepository;
import com.budgetowl.imports.persistence.ImportLedgerRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The imports a household has made, and undoing one (decision 5).
 *
 * <p>Every read is open to any member; undo needs {@code OWNER} or {@code MEMBER}, checked before
 * the batch is looked up. Another household's batch is a {@code 404}, the same answer as one that
 * never existed.
 */
@Service
public class ImportHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ImportHistoryService.class);

    private final ImportBatchRepository batches;
    private final ImportLedgerRepository ledger;
    private final AccountRepository accounts;
    private final MembershipService memberships;

    public ImportHistoryService(
            ImportBatchRepository batches,
            ImportLedgerRepository ledger,
            AccountRepository accounts,
            MembershipService memberships) {
        this.batches = batches;
        this.ledger = ledger;
        this.accounts = accounts;
        this.memberships = memberships;
    }

    /** A page of history, newest first. */
    public record HistoryPage(
            List<ImportBatchView> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    /**
     * @param accountId null for every account; otherwise it must be this household's
     */
    @Transactional(readOnly = true)
    public HistoryPage list(UUID actorId, UUID accountId, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        if (accountId != null) {
            requireAccount(accountId, householdId);
        }
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        Page<ImportBatch> found =
                batches.findHistory(
                        householdId, accountId, PageRequest.of(Math.max(page, 0), clamped));
        Map<UUID, Account> named = new HashMap<>();
        List<ImportBatchView> views =
                found.getContent().stream()
                        .map(
                                batch ->
                                        view(
                                                batch,
                                                named.computeIfAbsent(
                                                        batch.accountId(),
                                                        id -> requireAccount(id, householdId)),
                                                false))
                        .toList();
        return new HistoryPage(
                views,
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ImportBatchView get(UUID actorId, UUID batchId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        ImportBatch batch = requireBatch(batchId, householdId);
        return view(batch, requireAccount(batch.accountId(), householdId), false);
    }

    /**
     * Undo: removes the batch's rows that are not transfer legs, and the batch, in one transaction.
     * A row since paired into a transfer stays — removing one leg would leave money that left one
     * account and arrived nowhere — and loses its link to the batch. Links from a bill occurrence,
     * envelope entry or debt payment to a removed row are cleared by their own foreign keys, which
     * is what the confirmation tells the person before they agree.
     */
    @Transactional
    public void undo(UUID actorId, UUID batchId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        ImportBatch batch = requireBatch(batchId, householdId);
        int removed = ledger.deleteRemovable(householdId, batch.id());
        // The bulk delete cleared the persistence context, so the batch is read again before it is
        // removed rather than removed as a detached copy.
        batches.delete(requireBatch(batchId, householdId));
        log.info(
                "import undone householdId={} importBatchId={} accountId={} byUserId={} removed={}",
                householdId,
                batch.id(),
                batch.accountId(),
                actorId,
                removed);
    }

    /** The batch a retried commit already wrote, if this digest names one for this account. */
    @Transactional(readOnly = true)
    public Optional<ImportBatchView> alreadyWritten(
            UUID householdId, Account account, String previewDigest) {
        return batches.findByHouseholdIdAndPreviewDigest(householdId, previewDigest)
                .filter(batch -> batch.accountId().equals(account.id()))
                .map(batch -> view(batch, account, false));
    }

    @Transactional(readOnly = true)
    public ImportBatchView view(ImportBatch batch, Account account, boolean created) {
        return new ImportBatchView(
                batch.id(),
                account.id(),
                account.name(),
                batch.format(),
                batch.counts(),
                batch.importedBy().orElse(null),
                batch.createdAt(),
                ledger.countRemovable(batch.householdId(), batch.id()),
                created);
    }

    private ImportBatch requireBatch(UUID batchId, UUID householdId) {
        return batches.findByIdAndHouseholdId(batchId, householdId)
                .orElseThrow(() -> new NotFoundException("no such import"));
    }

    private Account requireAccount(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }
}
