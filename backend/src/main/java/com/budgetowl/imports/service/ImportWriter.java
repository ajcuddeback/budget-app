package com.budgetowl.imports.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.RedactedThrowable;
import com.budgetowl.imports.domain.CsvMapping;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportBatch;
import com.budgetowl.imports.domain.ImportCounts;
import com.budgetowl.imports.domain.ReadRow;
import com.budgetowl.imports.persistence.ImportBatchRepository;
import com.budgetowl.imports.persistence.ImportLedgerRepository;
import com.budgetowl.imports.persistence.ImportPayees;
import com.budgetowl.imports.service.Classified.Classification;
import com.budgetowl.transaction.domain.Transaction;
import com.budgetowl.transaction.domain.TransactionStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one import: the batch and every accepted row, in <b>one database transaction</b>, or
 * nothing at all (docs/features/file-import.md rule 1, ADR-0037).
 *
 * <p>Inside that transaction it re-reads the account, re-classifies the file against the ledger as
 * it is now, and compares the digest with the one the person was shown. Doing all three in the same
 * transaction as the insert narrows the window a concurrent import could slip through; the unique
 * indexes close it. A race lost on {@code ux_transactions_household_external_id} or on the batch's
 * digest rolls back every row, the batch and any payee this commit created, and is answered {@code
 * 409 import-preview-stale}.
 *
 * <p><b>What is written</b>: every {@code NEW} row, plus each suspected duplicate the person chose
 * to include. Every row is {@code CLEARED} (a statement lists what the bank processed), has no
 * category (it lands in "Needs a category"), carries its {@code external_id} and the batch's id,
 * and is attached to nothing — no bill occurrence, envelope entry, debt payment or transfer (rule
 * 10).
 */
@Service
public class ImportWriter {

    private static final String DUPLICATE_ROW_INDEX = "ux_transactions_household_external_id";
    private static final String DUPLICATE_BATCH_INDEX = "uq_import_batches_household_digest";

    private final AccountRepository accounts;
    private final ImportBatchRepository batches;
    private final ImportLedgerRepository ledger;
    private final ImportPayees payees;
    private final ImportClassifier classifier;
    private final ImportMappingService mappings;

    public ImportWriter(
            AccountRepository accounts,
            ImportBatchRepository batches,
            ImportLedgerRepository ledger,
            ImportPayees payees,
            ImportClassifier classifier,
            ImportMappingService mappings) {
        this.accounts = accounts;
        this.batches = batches;
        this.ledger = ledger;
        this.payees = payees;
        this.classifier = classifier;
        this.mappings = mappings;
    }

    /** What the person decided, and what to save alongside. */
    public record Decisions(
            String previewDigest,
            boolean includeSuspected,
            Map<Integer, Boolean> overrides,
            CsvMapping mappingToSave,
            String charsetToSave,
            boolean invertSign,
            List<String> headerLabels) {

        @Override
        public String toString() {
            return "Decisions[overrides=" + overrides.size() + "]";
        }
    }

    /** The batch written, and the classification it was written from. */
    public record Written(ImportBatch batch, ImportClassifier.Result classification) {}

    /**
     * @throws ConflictException {@code import-preview-stale} when the digest no longer matches or a
     *     concurrent commit won; {@code account-archived}
     * @throws com.budgetowl.common.InvalidRequestException {@code validation-failed} for an
     *     override that names anything but a suspected duplicate
     */
    @Transactional
    public Written write(
            UUID actorId,
            UUID householdId,
            UUID accountId,
            FileReading reading,
            Decisions decisions) {
        Account account =
                accounts.findByIdAndHouseholdId(accountId, householdId)
                        .orElseThrow(() -> new NotFoundException("no such account"));
        if (account.isArchived()) {
            throw new ConflictException(
                    ErrorCode.ACCOUNT_ARCHIVED, "the account is archived; restore it to import");
        }

        ImportClassifier.Result classification = classifier.classify(householdId, account, reading);
        if (!classification.digest().equals(decisions.previewDigest())) {
            throw stale();
        }

        Map<Integer, Classified> byRecord = new java.util.HashMap<>();
        for (Classified row : classification.rows()) {
            byRecord.put(row.row().record(), row);
        }
        for (Integer record : decisions.overrides().keySet()) {
            Classified named = byRecord.get(record);
            if (named == null || named.classification() != Classification.SUSPECTED) {
                // Only a suspected duplicate is the person's to decide. An exact duplicate is
                // already in the ledger; an unreadable row has nothing to write.
                throw ImportSettingsReader.invalid("overrides", "only a suspected duplicate");
            }
        }

        List<Classified> toWrite = new ArrayList<>();
        int duplicate = 0;
        int excluded = 0;
        int unreadable = 0;
        int zero = 0;
        for (Classified row : classification.rows()) {
            switch (row.classification()) {
                case NEW -> toWrite.add(row);
                case SUSPECTED -> {
                    boolean include =
                            decisions
                                    .overrides()
                                    .getOrDefault(row.row().record(), decisions.includeSuspected());
                    if (include) {
                        toWrite.add(row);
                    } else {
                        excluded++;
                    }
                }
                case DUPLICATE -> duplicate++;
                case UNREADABLE -> unreadable++;
                case ZERO -> zero++;
            }
        }
        ImportCounts counts =
                new ImportCounts(toWrite.size(), duplicate, excluded, unreadable, zero);

        try {
            ImportBatch batch =
                    batches.saveAndFlush(
                            ImportBatch.record(
                                    account,
                                    actorId,
                                    reading.format(),
                                    decisions.previewDigest(),
                                    counts));
            Map<String, UUID> payeeIds = payees.getOrCreate(householdId, payeeNames(toWrite));
            Transaction last = null;
            for (Classified row : toWrite) {
                last = ledger.save(transactionFor(account, batch, row, payeeIds));
            }
            if (last != null) {
                ledger.saveAndFlush(last);
            }
            if (decisions.mappingToSave() != null) {
                mappings.saveWithCommit(
                        account,
                        decisions.mappingToSave(),
                        decisions.charsetToSave(),
                        decisions.invertSign(),
                        decisions.headerLabels());
            }
            return new Written(batch, classification);
        } catch (DataIntegrityViolationException lost) {
            // Never logged with its message: a constraint's DETAIL carries the failing row, which
            // here is a line of somebody's bank statement (docs/memory/gotchas.md).
            String constraint = RedactedThrowable.violatedConstraint(lost).orElse("");
            if (DUPLICATE_ROW_INDEX.equals(constraint)
                    || DUPLICATE_BATCH_INDEX.equals(constraint)) {
                throw stale();
            }
            throw lost;
        }
    }

    private static Transaction transactionFor(
            Account account, ImportBatch batch, Classified classified, Map<String, UUID> payeeIds) {
        ReadRow row = classified.row();
        Transaction transaction =
                Transaction.of(
                        account,
                        row.date(),
                        Money.of(row.amount(), account.currency()),
                        TransactionStatus.CLEARED);
        transaction.payTo(row.payee() == null ? null : payeeIds.get(row.payee()));
        transaction.annotate(row.note());
        transaction.importedAs(classified.externalId());
        transaction.importedIn(batch.id());
        return transaction;
    }

    private static Set<String> payeeNames(List<Classified> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Classified row : rows) {
            Optional.ofNullable(row.row().payee()).ifPresent(names::add);
        }
        return names;
    }

    private static ConflictException stale() {
        return new ConflictException(
                ErrorCode.IMPORT_PREVIEW_STALE, "the ledger changed since the preview");
    }
}
