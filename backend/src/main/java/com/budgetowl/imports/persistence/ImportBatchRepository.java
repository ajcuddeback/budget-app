package com.budgetowl.imports.persistence;

import com.budgetowl.imports.domain.ImportBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Import batches. Every method takes a {@code householdId} or the entity itself — see {@code
 * AccountRepository}, and {@code ArchitectureTest}, which fails the build otherwise.
 */
public interface ImportBatchRepository extends Repository<ImportBatch, UUID> {

    ImportBatch saveAndFlush(ImportBatch batch);

    void delete(ImportBatch batch);

    Optional<ImportBatch> findByIdAndHouseholdId(UUID id, UUID householdId);

    /** The batch a retried commit already wrote — what makes commit idempotent (ADR-0037). */
    Optional<ImportBatch> findByHouseholdIdAndPreviewDigest(UUID householdId, String previewDigest);

    /**
     * The history, newest first. {@code created_at} then {@code id}, so two batches written in one
     * second still come back in a stable order (docs/memory/gotchas.md, "a random UUID is not a
     * tiebreak" — here it is only the last key, after the timestamp).
     *
     * @param accountId null for every account
     */
    @Query(
            value =
                    """
                    select b from ImportBatch b
                    where b.householdId = :householdId
                      and (:accountId is null or b.accountId = :accountId)
                    order by b.createdAt desc, b.id desc
                    """,
            countQuery =
                    """
                    select count(b) from ImportBatch b
                    where b.householdId = :householdId
                      and (:accountId is null or b.accountId = :accountId)
                    """)
    Page<ImportBatch> findHistory(
            @Param("householdId") UUID householdId,
            @Param("accountId") UUID accountId,
            Pageable pageable);
}
