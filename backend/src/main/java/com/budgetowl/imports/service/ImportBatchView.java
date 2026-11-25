package com.budgetowl.imports.service;

import com.budgetowl.imports.domain.ImportCounts;
import com.budgetowl.imports.domain.ImportFormat;
import java.time.Instant;
import java.util.UUID;

/**
 * One committed import, as the history and the success screen show it. No file name, because none
 * is stored (decision 4).
 *
 * @param removable how many of its rows an undo would remove now: the ones still in the ledger and
 *     not since paired into a transfer
 * @param created whether this request wrote it, or found it already written by an earlier attempt
 *     at the same commit
 */
public record ImportBatchView(
        UUID id,
        UUID accountId,
        String accountName,
        ImportFormat format,
        ImportCounts counts,
        UUID importedBy,
        Instant createdAt,
        long removable,
        boolean created) {

    @Override
    public String toString() {
        return "ImportBatchView[id=" + id + "]";
    }
}
