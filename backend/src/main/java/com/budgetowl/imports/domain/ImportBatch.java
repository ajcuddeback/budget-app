package com.budgetowl.imports.domain;

import com.budgetowl.account.domain.Account;
import com.budgetowl.common.HouseholdOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One committed file import.
 *
 * <p><b>Metadata only, by decision</b> (ADR-0038): which account, who, which format, the counts,
 * and the digest of what the person was shown. There is no field for the file, its name, its hash,
 * the bank's closing balance or the account number the statement carried, and there must never be
 * one — a statement holds more than the ledger does, and ADR-0028 limited what is kept about an
 * account to four digits.
 *
 * <p>The counts describe the commit as it happened. They are not kept in step with later edits: a
 * row deleted by hand afterwards still counted as imported here.
 */
@Entity
@Table(name = "import_batches")
public class ImportBatch implements HouseholdOwned {

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "imported_by", updatable = false)
    private UUID importedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false)
    private ImportFormat format;

    @Column(name = "imported_count", nullable = false, updatable = false)
    private int importedCount;

    @Column(name = "duplicate_count", nullable = false, updatable = false)
    private int duplicateCount;

    @Column(name = "excluded_count", nullable = false, updatable = false)
    private int excludedCount;

    @Column(name = "unreadable_count", nullable = false, updatable = false)
    private int unreadableCount;

    @Column(name = "zero_count", nullable = false, updatable = false)
    private int zeroCount;

    @Column(name = "preview_digest", nullable = false, updatable = false)
    private String previewDigest;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ImportBatch() {
        // Hibernate.
    }

    /**
     * @param account the account imported into — an entity, so the household is the account's own
     *     and never a value a caller supplied
     * @param importedBy the acting user, from the security context
     * @throws IllegalArgumentException for a digest that is not 64 lowercase hex characters
     */
    public static ImportBatch record(
            Account account,
            UUID importedBy,
            ImportFormat format,
            String previewDigest,
            ImportCounts counts) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(counts, "counts");
        if (previewDigest == null || !DIGEST.matcher(previewDigest).matches()) {
            throw new IllegalArgumentException("a preview digest is 64 lowercase hex characters");
        }
        ImportBatch batch = new ImportBatch();
        batch.householdId = account.householdId();
        batch.accountId = account.id();
        batch.importedBy = Objects.requireNonNull(importedBy, "importedBy");
        batch.format = Objects.requireNonNull(format, "format");
        batch.previewDigest = previewDigest;
        batch.importedCount = counts.imported();
        batch.duplicateCount = counts.duplicate();
        batch.excludedCount = counts.excluded();
        batch.unreadableCount = counts.unreadable();
        batch.zeroCount = counts.zero();
        return batch;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID accountId() {
        return accountId;
    }

    /** Empty once the user who imported has been deleted. */
    public Optional<UUID> importedBy() {
        return Optional.ofNullable(importedBy);
    }

    public ImportFormat format() {
        return format;
    }

    public ImportCounts counts() {
        return new ImportCounts(
                importedCount, duplicateCount, excludedCount, unreadableCount, zeroCount);
    }

    public String previewDigest() {
        return previewDigest;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    /** Ids only. Nothing here came from the file, and nothing here needs printing either. */
    @Override
    public String toString() {
        return "ImportBatch[id=" + id + ", format=" + format + "]";
    }
}
